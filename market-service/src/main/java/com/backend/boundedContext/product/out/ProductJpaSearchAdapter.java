package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

import static com.backend.boundedContext.product.out.ProductSpecifications.categoryIn;
import static com.backend.boundedContext.product.out.ProductSpecifications.discountedAt;
import static com.backend.boundedContext.product.out.ProductSpecifications.inStock;
import static com.backend.boundedContext.product.out.ProductSpecifications.nameContains;
import static com.backend.boundedContext.product.out.ProductSpecifications.statusEquals;

/**
 * DB로 상품을 검색한다. 상품명은 LIKE '%keyword%'(대소문자 무시)로 찾는다.
 * 테스트(engine=jpa)에서 쓰고, ES 검색이 실패하면 대신 쓴다. 관련도순은 최신순, 가격순은 정가 기준이다.
 */
@Repository
@RequiredArgsConstructor
public class ProductJpaSearchAdapter implements ProductSearchPort {

    private final ProductRepository productRepository;

    @Override
    public Page<Long> searchProductIds(ProductSearchQuery query, Pageable pageable) {
        Specification<Product> spec = Specification.allOf(
                statusEquals(ProductSaleStatus.ON_SALE),
                categoryIn(query.categoryIds()),
                nameContains(query.keyword()),
                query.excludeSoldOut() ? inStock() : Specification.unrestricted(),
                query.discountedOnly() ? discountedAt(LocalDateTime.now()) : Specification.unrestricted()
        );
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), toSort(query.sort()));

        return productRepository.findAll(spec, sorted).map(Product::getId);
    }

    private static Sort toSort(ProductSort sort) {
        Sort.Order byId = Sort.Order.desc("id");
        return switch (sort) {
            // 관련도는 DB 검색에 없고, 할인율은 정가·할인가로 계산하는 값이라 정렬 조건으로 쓰기 어려워 최신순으로 대신한다
            case RELEVANCE, LATEST, DISCOUNT_RATE -> Sort.by(Sort.Order.desc("createdAt"), byId);
            case PRICE_ASC -> Sort.by(Sort.Order.asc("price"), byId);
            case PRICE_DESC -> Sort.by(Sort.Order.desc("price"), byId);
        };
    }
}
