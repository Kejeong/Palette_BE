package com.backend.boundedContext.chat.app;

import com.backend.boundedContext.chat.app.dto.ChatAnswerDto;
import com.backend.boundedContext.chat.app.dto.ChatAnswerDto.Source;
import com.backend.boundedContext.chat.domain.ChatAnswerType;
import com.backend.boundedContext.chat.domain.ChatPrompt;
import com.backend.boundedContext.faq.app.FaqFacade;
import com.backend.boundedContext.faq.app.dto.FaqSearchDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 질문과 의미가 가까운 FAQ를 찾아, 그 FAQ만 근거로 LLM이 답변을 만듦(RAG). OpenAI를 부르므로 트랜잭션 밖에서 부름
 * - 근거 FAQ가 없으면 LLM을 부르지 않고 고객센터 안내 문구로 답함
 * - LLM이 실패하거나 시간을 넘기면(spring.ai.openai.chat.timeout) 가장 비슷한 FAQ 답변을 그대로 줌
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnswerUseCase {

    private final FaqFacade faqFacade;
    private final ChatModel chatModel;

    public ChatAnswerDto answer(String question) {
        List<FaqSearchDto.Item> faqs = faqFacade.search(question).faqs();
        if (faqs.isEmpty()) return new ChatAnswerDto(ChatPrompt.NO_MATCH_ANSWER, ChatAnswerType.NO_MATCH, List.of());

        List<Source> sources = faqs.stream()
                .map(faq -> new Source(faq.faqId(), faq.category(), faq.categoryLabel(), faq.question()))
                .toList();
        String generated = generate(question, faqs);

        if (generated == null) return new ChatAnswerDto(faqs.getFirst().answer(), ChatAnswerType.FAQ, sources);

        return new ChatAnswerDto(generated, ChatAnswerType.AI, sources);
    }

    // LLM 답변. 실패·시간 초과·빈 답변이면 null
    private String generate(String question, List<FaqSearchDto.Item> faqs) {
        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(new SystemMessage(ChatPrompt.system(faqs)), new UserMessage(question))));
            String text = response.getResult().getOutput().getText();

            if (text == null || text.isBlank()) {
                log.warn("chat answer empty faqIds={}", faqs.stream().map(FaqSearchDto.Item::faqId).toList());
                return null;
            }
            return text.strip();
        } catch (RuntimeException e) {
            log.warn("chat answer failed reason={}", e.toString());
            return null;
        }
    }
}
