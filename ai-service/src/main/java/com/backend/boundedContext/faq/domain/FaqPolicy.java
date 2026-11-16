package com.backend.boundedContext.faq.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// FAQ 컨텍스트 정책 값 (custom.faq.*)
@Component
@Getter
public class FaqPolicy {

    // 한 번에 임베딩하는 FAQ 수. OpenAI 호출 한 번에 묶어 보냄
    private final int embeddingBatchSize;

    public FaqPolicy(@Value("${custom.faq.embeddingBatchSize}") int embeddingBatchSize) {
        if (embeddingBatchSize < 1) throw new IllegalArgumentException("custom.faq.embeddingBatchSize는 1 이상이어야 합니다: " + embeddingBatchSize);
        this.embeddingBatchSize = embeddingBatchSize;
    }
}
