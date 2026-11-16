package com.backend.boundedContext.faq.app;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// FAQ 컨텍스트의 입구
@Component
@RequiredArgsConstructor
public class FaqFacade {

    private final FaqEmbedUseCase faqEmbedUseCase;

    // 비어 있는 FAQ 임베딩을 채움. OpenAI를 부르므로 트랜잭션을 걸지 않음
    public void embedPending() {
        faqEmbedUseCase.embedPending();
    }
}
