package com.backend.boundedContext.chat.app;

import com.backend.boundedContext.chat.app.dto.ChatAnswerDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 챗봇 컨텍스트의 입구
 */
@Component
@RequiredArgsConstructor
public class ChatFacade {

    private final ChatAnswerUseCase chatAnswerUseCase;

    // FAQ 근거 답변. OpenAI(임베딩·채팅)를 부르므로 트랜잭션을 걸지 않음
    public ChatAnswerDto answer(String question) {
        return chatAnswerUseCase.answer(question);
    }
}
