package com.backend.boundedContext.product.out.search;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import com.backend.boundedContext.product.domain.ProductSaleStatus;

import java.util.Collection;

/** 상품 검색·비슷한 상품이 같이 쓰는 ES 조건. 구매자에게 보이는 상품의 기준을 한곳에 둔다 */
final class ProductEsQueries {

    private ProductEsQueries() {
    }

    /** 판매 중이고 주어진(사용 중인) 소분류의 상품 */
    static BoolQuery.Builder displayable(BoolQuery.Builder b, Collection<Long> categoryIds) {
        return b.filter(f -> f.term(t -> t.field("status").value(ProductSaleStatus.ON_SALE.name())))
                .filter(f -> f.terms(t -> t.field("categoryId").terms(v -> v.value(
                        categoryIds.stream().map(FieldValue::of).toList()))));
    }
}
