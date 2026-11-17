package com.backend.boundedContext.chat.app;

import com.backend.boundedContext.chat.app.dto.ChatAnswerDto;
import com.backend.boundedContext.chat.domain.ChatAnswerType;
import com.backend.boundedContext.chat.domain.ChatConversation;
import com.backend.boundedContext.chat.domain.ChatErrorCode;
import com.backend.boundedContext.chat.domain.ChatMessage;
import com.backend.boundedContext.chat.domain.ChatPolicy;
import com.backend.boundedContext.chat.domain.ChatPrompt;
import com.backend.boundedContext.chat.domain.ChatRole;
import com.backend.boundedContext.chat.out.ChatConversationRepository;
import com.backend.boundedContext.chat.out.ChatMessageRepository;
import com.backend.boundedContext.faq.app.FaqFacade;
import com.backend.boundedContext.faq.app.dto.FaqSearchDto;
import com.backend.boundedContext.faq.app.dto.FaqSummaryDto;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 질문과 의미가 가까운 FAQ를 찾아, 그 FAQ만 근거로 LLM이 답변을 만듦(RAG). 질문·답변은 대화 기록에 저장함
 * - 같은 대화의 최근 질문을 FAQ 검색어 앞에 붙이고, 최근 메시지를 프롬프트에 넣어 이어지는 질문에 답함
 * - 근거 FAQ가 없으면 LLM을 부르지 않고 고객센터 안내 문구로 답함
 * - LLM이 실패하거나 시간을 넘기면(spring.ai.openai.chat.timeout) 가장 비슷한 FAQ 답변을 그대로 줌
 * OpenAI 호출 동안 DB 연결을 잡지 않게, 대화 읽기와 저장만 짧은 트랜잭션으로 나눔
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnswerUseCase {

    private final FaqFacade faqFacade;
    private final ChatModel chatModel;
    private final ChatPolicy chatPolicy;
    private final ChatConversationRepository chatConversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final TransactionTemplate transactionTemplate;

    // conversationId가 null이면 새 대화를 시작함. 다른 회원의 대화면 404
    public ChatAnswerDto answer(Long memberId, Long conversationId, String question) {
        // 1. 짧은 읽기: 내 대화인지 확인하고 최근 메시지를 가져옴(오래된 순)
        List<ChatMessage> history = conversationId == null ? List.of() : readHistory(memberId, conversationId);

        // 2. 트랜잭션 밖에서 FAQ 검색·답변 생성
        List<FaqSearchDto.Item> faqs = faqFacade.search(searchText(history, question)).faqs();
        ChatAnswerType answerType;
        String answer;

        if (faqs.isEmpty()) {
            answerType = ChatAnswerType.NO_MATCH;
            answer = ChatPrompt.NO_MATCH_ANSWER;
        } else {
            String generated = generate(history, question, faqs);
            answerType = generated == null ? ChatAnswerType.FAQ : ChatAnswerType.AI;
            answer = generated == null ? faqs.getFirst().answer() : generated;
        }

        // 3. 짧은 저장: 질문·답변을 대화에 남김
        List<Long> sourceFaqIds = faqs.stream().map(FaqSearchDto.Item::faqId).toList();
        Long savedConversationId = transactionTemplate.execute(tx -> save(memberId, conversationId, question, answer, answerType, sourceFaqIds));

        List<FaqSummaryDto> sources = faqs.stream()
                .map(faq -> new FaqSummaryDto(faq.faqId(), faq.category(), faq.categoryLabel(), faq.question()))
                .toList();

        return new ChatAnswerDto(savedConversationId, answer, answerType, sources);
    }

    private List<ChatMessage> readHistory(Long memberId, Long conversationId) {
        return transactionTemplate.execute(tx -> {
            findMine(memberId, conversationId);
            List<ChatMessage> recent = new ArrayList<>(chatMessageRepository.findByConversationIdOrderByIdDesc(conversationId, Limit.of(chatPolicy.getHistoryMessages())));
            return recent.reversed();
        });
    }

    // FAQ 검색어 = 같은 대화의 최근 질문 + 이번 질문. "그럼 며칠 걸려요?"만으로는 맞는 FAQ가 안 나와서 앞 질문을 붙임
    private String searchText(List<ChatMessage> history, String question) {
        List<String> previous = history.stream().filter(m -> m.getRole() == ChatRole.USER).map(ChatMessage::getContent).toList();
        List<String> parts = new ArrayList<>(previous.subList(Math.max(0, previous.size() - chatPolicy.getSearchHistoryQuestions()), previous.size()));
        parts.add(question);
        return String.join("\n", parts);
    }

    // LLM 답변. 실패·시간 초과·빈 답변이면 null
    private String generate(List<ChatMessage> history, String question, List<FaqSearchDto.Item> faqs) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(ChatPrompt.system(faqs)));

        for (ChatMessage m : history) {
            messages.add(m.getRole() == ChatRole.USER ? new UserMessage(m.getContent()) : new AssistantMessage(m.getContent()));
        }
        messages.add(new UserMessage(question));

        try {
            ChatResponse response = chatModel.call(new Prompt(messages));
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

    private Long save(Long memberId, Long conversationId, String question, String answer, ChatAnswerType answerType, List<Long> sourceFaqIds) {
        LocalDateTime now = LocalDateTime.now();
        ChatConversation conversation;

        if (conversationId == null) {
            conversation = chatConversationRepository.save(ChatConversation.start(memberId, question, now));
        } else {
            conversation = findMine(memberId, conversationId);
            conversation.touch(now);
        }

        chatMessageRepository.save(ChatMessage.question(conversation.getId(), question));
        chatMessageRepository.save(ChatMessage.answer(conversation.getId(), answer, answerType, sourceFaqIds));
        return conversation.getId();
    }

    private ChatConversation findMine(Long memberId, Long conversationId) {
        return chatConversationRepository.findByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new DomainException(ChatErrorCode.CONVERSATION_NOT_FOUND));
    }
}
