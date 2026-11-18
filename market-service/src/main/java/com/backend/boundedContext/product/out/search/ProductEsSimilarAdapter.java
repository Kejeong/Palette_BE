package com.backend.boundedContext.product.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.backend.boundedContext.product.domain.ProductSimilarCandidate;
import com.backend.boundedContext.product.domain.ProductSimilarCandidates;
import com.backend.boundedContext.product.out.ProductJpaSimilarAdapter;
import com.backend.boundedContext.product.out.ProductSimilarPort;
import com.backend.boundedContext.product.out.ProductSimilarQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES로 비슷한 상품 후보를 찾는다. 두 가지를 합친다.
 * <ul>
 *   <li>텍스트 후보: 기준 상품의 상품명·설명으로 more_like_this를 돌려 nori 형태소가 겹치는 상품 (점수 = 텍스트 유사도)</li>
 *   <li>같은 소분류 후보: 텍스트가 짧거나 겹치는 말이 없어도 결과가 비지 않게, 같은 소분류의 행동 점수 상위 상품</li>
 * </ul>
 * 인덱스가 최신이 아니거나 ES가 실패하면 DB 구현({@link ProductJpaSimilarAdapter})으로 대신한다.
 */
@Slf4j
@Primary
@Component
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductEsSimilarAdapter implements ProductSimilarPort {
    /** 후보의 점수 재료만 읽는다 */
    private static final List<String> SOURCE_FIELDS = List.of("categoryId", "effectivePrice", "behaviorScore");

    private final ElasticsearchClient client;
    private final ProductJpaSimilarAdapter fallback;
    private final ProductEsIndexAdapter indexAdapter;
    private final String alias;

    public ProductEsSimilarAdapter(ElasticsearchClient client, ProductJpaSimilarAdapter fallback, ProductEsIndexAdapter indexAdapter,
                                   @Value("${custom.product.search.indexAlias}") String alias) {
        this.client = client;
        this.fallback = fallback;
        this.indexAdapter = indexAdapter;
        this.alias = alias;
    }

    @Override
    public ProductSimilarCandidates findCandidates(ProductSimilarQuery query) {
        // 매핑 버전이 낮은 이전 인덱스에는 설명 필드가 없어 텍스트 후보가 오류 없이 비므로 DB로 대신한다
        if (!indexAdapter.isKnownCurrent()) {
            return fallback.findCandidates(query);
        }
        try {
            Map<Long, ProductSimilarCandidate> candidates = new LinkedHashMap<>();
            double referenceTextScore = 0;
            for (ProductSimilarCandidate candidate : textCandidates(query)) {
                if (candidate.productId() == query.productId()) {
                    referenceTextScore = candidate.textScore();   // 기준 상품 자기 자신 = 완전 일치의 점수
                } else {
                    candidates.put(candidate.productId(), candidate);
                }
            }
            sameCategoryCandidates(query).forEach(c -> candidates.putIfAbsent(c.productId(), c));
            return new ProductSimilarCandidates(List.copyOf(candidates.values()), referenceTextScore);
        } catch (Exception e) {
            log.warn("ES 비슷한 상품 조회에 실패해 DB로 대신합니다. productId={}", query.productId(), e);
            return fallback.findCandidates(query);
        }
    }

    /**
     * 기준 상품 텍스트와 형태소가 겹치는 상품. 기준 문서(_id)가 아니라 DB에서 읽은 텍스트로 찾는다 (색인이 늦어도 지금 내용으로 찾는다).
     * 기준 상품은 빼지 않고 함께 찾아(품절이어도) 그 점수를 텍스트 점수의 기준값으로 쓴다.
     * 상품 수가 적어 문서 빈도 기본값(5)이면 거의 나오지 않으므로 1로 낮춘다.
     */
    private List<ProductSimilarCandidate> textCandidates(ProductSimilarQuery query) throws IOException {
        if (query.likeText() == null || query.likeText().isBlank()) return List.of();
        String baseId = String.valueOf(query.productId());
        return search(query.candidateSize() + 1, Query.of(q -> q.bool(b -> ProductEsQueries.displayable(b, query.categoryIds())
                .filter(f -> f.bool(inStockOrBase -> inStockOrBase
                        .should(s -> s.term(t -> t.field("soldOut").value(false)))
                        .should(s -> s.ids(i -> i.values(baseId)))))
                .must(m -> m.moreLikeThis(mlt -> mlt
                        .fields("name", "description")
                        .like(l -> l.text(query.likeText()))
                        .minTermFreq(1)
                        .minDocFreq(1)
                        .maxQueryTerms(25)
                        .minimumShouldMatch("30%"))))), List.of(), true);
    }

    /** 같은 소분류의 품절 아닌 행동 점수 상위 상품 (기준 상품 제외, 텍스트 점수 0) */
    private List<ProductSimilarCandidate> sameCategoryCandidates(ProductSimilarQuery query) throws IOException {
        return search(query.candidateSize(), Query.of(q -> q.bool(b -> ProductEsQueries.displayable(b, query.categoryIds())
                        .filter(f -> f.term(t -> t.field("soldOut").value(false)))
                        .filter(f -> f.term(t -> t.field("categoryId").value(query.categoryId())))
                        .mustNot(m -> m.ids(i -> i.values(String.valueOf(query.productId())))))),
                List.of(field("behaviorScore"), field("createdAt"), field("productId")), false);
    }

    private List<ProductSimilarCandidate> search(int size, Query condition, List<SortOptions> sort, boolean useScore)
            throws IOException {
        return client.search(s -> s
                                .index(alias)
                                .query(condition)
                                .sort(sort)
                                .size(size)
                                .source(src -> src.filter(f -> f.includes(SOURCE_FIELDS))),
                        CandidateSource.class)
                .hits().hits().stream()
                .filter(hit -> hit.source() != null)
                .map(hit -> toCandidate(hit, useScore))
                .toList();
    }

    private static ProductSimilarCandidate toCandidate(Hit<CandidateSource> hit, boolean useScore) {
        CandidateSource source = hit.source();
        double textScore = useScore && hit.score() != null ? hit.score() : 0;
        return new ProductSimilarCandidate(Long.parseLong(hit.id()), source.categoryId(), source.effectivePrice(),
                source.behaviorScore(), textScore);
    }

    private static SortOptions field(String name) {
        return SortOptions.of(o -> o.field(f -> f.field(name).order(SortOrder.Desc)));
    }

    /** 후보 문서에서 읽는 필드 */
    record CandidateSource(long categoryId, long effectivePrice, double behaviorScore) {
    }
}
