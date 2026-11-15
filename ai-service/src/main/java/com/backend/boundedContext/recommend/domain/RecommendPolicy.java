package com.backend.boundedContext.recommend.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 추천 컨텍스트 정책 값 (custom.recommend.*)
@Component
@Getter
public class RecommendPolicy {

    // 한 번에 임베딩하는 상품·설문 수. OpenAI 호출 한 번에 묶어 보낸다
    private final int embeddingBatchSize;

    // 추천 점수 = 프로필과 상품 임베딩의 코사인 유사도 + 겹친 선호 성분 수 × 이 값 + 관심 카테고리면 이 값
    // 유사도(보통 0.1~0.6) 차이가 작을 때 순서를 가르는 정도의 크기로 둔다
    private final double preferredIngredientWeight;
    private final double interestCategoryWeight;

    public RecommendPolicy(@Value("${custom.recommend.embeddingBatchSize}") int embeddingBatchSize,
                           @Value("${custom.recommend.preferredIngredientWeight}") double preferredIngredientWeight,
                           @Value("${custom.recommend.interestCategoryWeight}") double interestCategoryWeight) {
        if (embeddingBatchSize < 1) throw new IllegalArgumentException("custom.recommend.embeddingBatchSize는 1 이상이어야 합니다: " + embeddingBatchSize);
        if (preferredIngredientWeight < 0 || interestCategoryWeight < 0) throw new IllegalArgumentException("추천 가점은 0 이상이어야 합니다");
        this.embeddingBatchSize = embeddingBatchSize;
        this.preferredIngredientWeight = preferredIngredientWeight;
        this.interestCategoryWeight = interestCategoryWeight;
    }
}
