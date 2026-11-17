package com.backend.shared.review.dto;

/**
 * 상품 하나의 리뷰 통계 (삭제한 리뷰 제외). 상품 컨텍스트가 검색 색인의 별점 점수를 계산할 때 쓴다.
 *
 * @param productId   상품 ID
 * @param reviewCount 리뷰 수
 * @param ratingSum   별점 합 (별점은 1~5)
 */
public record ProductRatingStatsDto(Long productId, long reviewCount, long ratingSum) {
}
