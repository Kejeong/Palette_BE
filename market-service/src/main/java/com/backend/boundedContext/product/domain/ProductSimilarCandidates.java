package com.backend.boundedContext.product.domain;

import java.util.List;

/**
 * 비슷한 상품 후보와 텍스트 점수 기준값.
 *
 * @param candidates         후보 (기준 상품 제외)
 * @param referenceTextScore 기준 상품 자기 자신의 텍스트 점수 (같은 질의에서 기준 문서가 받은 점수, 완전 일치의 점수).
 *                           후보 텍스트 점수를 이 값으로 나눠 0~1로 맞춘다. 기준 상품이 색인에 없거나 텍스트로 찾지 않았으면 0
 */
public record ProductSimilarCandidates(List<ProductSimilarCandidate> candidates, double referenceTextScore) {

    public static ProductSimilarCandidates withoutText(List<ProductSimilarCandidate> candidates) {
        return new ProductSimilarCandidates(candidates, 0);
    }
}
