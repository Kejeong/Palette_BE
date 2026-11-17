package com.backend;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트는 실제 OpenAI 채팅을 부르지 않음. reply를 그대로 답하고, 마지막으로 받은 프롬프트를 남김.
 * failing을 켜면 예외를 던져 OpenAI 장애·시간 초과를 흉내 냄
 */
@Configuration
public class FakeChatModelConfig {

    @Bean
    @Primary
    public FakeChatModel fakeChatModel() {
        return new FakeChatModel();
    }

    public static class FakeChatModel implements ChatModel {
        public static final String DEFAULT_REPLY = "가짜 AI 답변이에요.";

        public volatile boolean failing;
        public volatile String reply = DEFAULT_REPLY;
        public volatile Prompt lastPrompt;
        public final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            lastPrompt = prompt;
            if (failing) throw new IllegalStateException("가짜 OpenAI 채팅 장애");
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }

        // 테스트마다 처음 상태로 되돌림
        public void reset() {
            failing = false;
            reply = DEFAULT_REPLY;
            lastPrompt = null;
            calls.set(0);
        }
    }
}
