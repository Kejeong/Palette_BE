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

    public RecommendPolicy(@Value("${custom.recommend.embeddingBatchSize}") int embeddingBatchSize) {
        if (embeddingBatchSize < 1) throw new IllegalArgumentException("custom.recommend.embeddingBatchSize는 1 이상이어야 합니다: " + embeddingBatchSize);
        this.embeddingBatchSize = embeddingBatchSize;
    }
}
