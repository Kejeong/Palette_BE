package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductErrorCode;
import com.backend.boundedContext.product.domain.ProductPolicy;
import com.backend.boundedContext.product.domain.ProductPrice;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.out.ProductDiscountRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.search.ProductSearchDocument;
import com.backend.boundedContext.product.out.search.ProductSearchIndexPort;
import com.backend.global.exception.DomainException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 상품 검색 색인 맞추기. DB가 원본이고 색인은 검색용 사본이다.
 * 변경한 트랜잭션은 {@link #requestIndex}로 요청만 남기고, 커밋 뒤 리스너가 {@link #indexProducts}로 DB의 최신 값을 색인한다.
 * 판매 중인 상품만 색인하고, 판매 중이 아니거나 없는 상품은 색인에서 지운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchIndexSupport {
    private final ApplicationEventPublisher eventPublisher;
    private final ProductRepository productRepository;
    private final ProductDiscountRepository productDiscountRepository;
    private final ProductSupport productSupport;
    private final ProductDiscountSupport productDiscountSupport;
    private final ProductSearchIndexPort productSearchIndexPort;
    private final ProductPolicy productPolicy;
    private final EntityManager entityManager;

    /**
     * 전체 재색인이 실행 중인지. 두 재색인이 겹치면 뒤의 alias 전환이 앞 재색인의 인덱스를 지우고,
     * 앞 재색인의 bulk가 같은 이름으로 매핑 없는 인덱스를 자동 생성한 뒤 alias를 그리로 옮길 수 있어 한 번에 하나만 실행한다 (market 한 대 전제)
     */
    private final AtomicBoolean rebuilding = new AtomicBoolean();

    /** 커밋 뒤에 이 상품들을 다시 색인해 달라고 요청한다 */
    public void requestIndex(Collection<Long> productIds) {
        if (productIds.isEmpty()) return;
        eventPublisher.publishEvent(new ProductSearchIndexRequestedEvent(Set.copyOf(productIds)));
    }

    public void requestIndex(Long productId) {
        requestIndex(Set.of(productId));
    }

    /** 상품들을 DB의 지금 값으로 색인한다. 판매 중이 아니거나 없는 상품은 색인에서 지운다 */
    public void indexProducts(Collection<Long> productIds) {
        if (productIds.isEmpty() || !productSearchIndexPort.isEnabled()) return;

        List<Product> onSale = productRepository.findAllById(productIds).stream()
                .filter(product -> product.getStatus() == ProductSaleStatus.ON_SALE)
                .toList();
        Set<Long> removed = new HashSet<>(productIds);
        onSale.forEach(product -> removed.remove(product.getId()));

        productSearchIndexPort.upsert(toDocuments(onSale));
        productSearchIndexPort.delete(removed);
    }

    /**
     * (from, now] 사이에 할인이 시작했거나 끝난 상품 ID (할인 경계 재색인 대상).
     * from은 지난 성공 실행 시각에서 겹침(overlap)만큼 앞이고, 지난 실행 시각이 없으면(기동 후 첫 실행) now에서 lookback만큼 앞이다.
     */
    public List<Long> findDiscountBoundaryProductIds(LocalDateTime lastSucceededAt, LocalDateTime now) {
        return productDiscountRepository.findProductIdsByBoundaryBetween(discountBoundaryFrom(lastSucceededAt, now), now);
    }

    LocalDateTime discountBoundaryFrom(LocalDateTime lastSucceededAt, LocalDateTime now) {
        if (lastSucceededAt == null) {
            return now.minusMinutes(productPolicy.getDiscountBoundaryLookbackMinutes());
        }
        return lastSucceededAt.minusSeconds(productPolicy.getDiscountBoundaryOverlapSeconds());
    }

    /**
     * 검색 alias가 없으면 전체 재색인하고 색인한 상품 수를, 있거나 다른 재색인이 실행 중이면 빈 값을 돌려준다 (복구 스케줄러가 주기마다 부른다).
     * 첫 배포·ES 장애·데이터 재구성 뒤 사람이 재색인을 부르지 않아도 검색이 채워지게 한다.
     */
    public OptionalInt rebuildIndexIfMissing() {
        if (!productSearchIndexPort.isEnabled() || productSearchIndexPort.aliasExists()) {
            return OptionalInt.empty();
        }
        if (!rebuilding.compareAndSet(false, true)) {
            return OptionalInt.empty();   // 다른 재색인이 alias를 만드는 중이다
        }
        try {
            return OptionalInt.of(rebuild());
        } finally {
            rebuilding.set(false);
        }
    }

    /**
     * 판매 중인 상품 전체를 새 인덱스에 색인하고 검색 alias를 옮긴다. 색인한 상품 수를 돌려준다.
     * 매핑을 바꿨거나, 색인이 어긋났을 때 관리자가 부르고, 기동할 때 alias가 없으면 자동으로 부른다.
     * <p>
     * 재색인 중에 바뀐 상품은 이전 인덱스에만 반영되거나(alias가 있을 때), 쓰지 못하고 버려진다(alias가 없을 때).
     * 그래서 alias를 옮긴 뒤 시작 시각 이후 바뀐 상품과 할인 경계를 지난 상품을 새 인덱스에 한 번 더 반영한다.
     * 커밋 지연을 고려해 할인 경계와 같은 겹침(discountBoundaryOverlapSeconds)만큼 앞에서부터 찾는다.
     * 보정이 끝나기 전 잠깐은 이전 값이 보일 수 있지만, 그 뒤의 변경은 alias(새 인덱스)로 들어가므로 어긋나지 않는다.
     */
    public int rebuildIndex() {
        if (!productSearchIndexPort.isEnabled()) {
            throw new DomainException(ProductErrorCode.SEARCH_INDEX_DISABLED);
        }
        if (!rebuilding.compareAndSet(false, true)) {
            throw new DomainException(ProductErrorCode.SEARCH_INDEX_REBUILDING);
        }
        try {
            return rebuild();
        } finally {
            rebuilding.set(false);
        }
    }

    private int rebuild() {
        LocalDateTime startedAt = LocalDateTime.now();
        String indexName = productSearchIndexPort.createIndex();
        int batchSize = productPolicy.getSearchIndexBatchSize();
        long afterId = 0L;
        int indexed = 0;
        while (true) {
            List<Long> ids = productRepository.findIdsByStatusAfter(ProductSaleStatus.ON_SALE, afterId, Limit.of(batchSize));
            if (ids.isEmpty()) break;

            List<ProductSearchDocument> documents = toDocuments(productRepository.findAllById(ids));
            productSearchIndexPort.bulkIndex(indexName, documents);
            // 읽은 상품·재고를 1차 캐시에서 비운다. 남겨 두면 묶음마다 쌓여 상품 수만큼 메모리를 쓰고,
            // 뒤의 보정이 재색인 중 다른 트랜잭션이 커밋한 변경 대신 이 옛 값을 돌려받는다
            entityManager.clear();
            indexed += documents.size();
            afterId = ids.get(ids.size() - 1);
            if (ids.size() < batchSize) break;
        }
        productSearchIndexPort.switchAlias(indexName);

        Set<Long> changed = new HashSet<>(productRepository.findIdsChangedSince(
                startedAt.minusSeconds(productPolicy.getDiscountBoundaryOverlapSeconds())));
        changed.addAll(findDiscountBoundaryProductIds(startedAt, LocalDateTime.now()));   // 안에서 같은 겹침을 뺀다
        indexProducts(changed);   // 묶음마다 1차 캐시를 비웠으므로 DB의 최신 값을 읽는다
        log.info("상품 검색 전체 재색인을 마쳤습니다. index={}, 상품 {}개, 재색인 중 바뀌어 다시 반영한 상품 {}개",
                indexName, indexed, changed.size());
        return indexed;
    }

    /** 상품들의 색인 문서. 품절 여부와 지금 기본가(할인 중이면 할인가)는 검색 화면과 같은 계산을 쓴다 */
    List<ProductSearchDocument> toDocuments(List<Product> products) {
        if (products.isEmpty()) return List.of();

        List<Long> ids = products.stream().map(Product::getId).toList();
        Map<Long, List<OptionStock>> stocks = productSupport.getOptionStocksByProductId(ids);
        Map<Long, ProductPrice> prices = productDiscountSupport.getPrices(products);

        return products.stream()
                .map(product -> new ProductSearchDocument(
                        product.getId(),
                        product.getName(),
                        product.getCategory().getId(),
                        product.getStatus().name(),
                        ProductSupport.isSoldOut(stocks.getOrDefault(product.getId(), List.of())),
                        prices.get(product.getId()).basePrice(),
                        product.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
                .toList();
    }
}
