package com.backend.shared.review.out;

import com.backend.shared.review.dto.ProductRatingStatsDto;

import java.util.Collection;
import java.util.Map;

/**
 * 리뷰 컨텍스트가 다른 컨텍스트에 공개하는 동기 계약
 */
public interface ReviewApi {

    // 상품별 리뷰 수·별점 합 (삭제한 리뷰 제외). 리뷰가 없는 상품은 키가 없다
    // 상품 컨텍스트가 검색 색인의 별점 점수를 계산할 때 쓴다
    Map<Long, ProductRatingStatsDto> getRatingStats(Collection<Long> productIds);
}
