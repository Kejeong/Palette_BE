package com.backend.boundedContext.chat.app;

import com.backend.FakeChatModelConfig.FakeChatModel;
import com.backend.FakeEmbeddingModelConfig;
import com.backend.FakeEmbeddingModelConfig.FakeEmbeddingModel;
import com.backend.boundedContext.chat.app.dto.ChatAnswerDto;
import com.backend.boundedContext.chat.app.dto.ChatConversationDto;
import com.backend.boundedContext.chat.app.dto.ChatMessageDto;
import com.backend.boundedContext.chat.domain.ChatAnswerType;
import com.backend.boundedContext.chat.domain.ChatPrompt;
import com.backend.boundedContext.chat.domain.ChatRole;
import com.backend.boundedContext.chat.out.ChatRateLimitStore;
import com.backend.boundedContext.faq.app.dto.FaqSummaryDto;
import com.backend.boundedContext.faq.domain.Faq;
import com.backend.boundedContext.faq.domain.FaqCategory;
import com.backend.boundedContext.faq.out.FaqRepository;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FAQ 근거 답변 생성과 대화 기록을 실제 DB(pgvector)로 확인함. 임베딩·채팅은 가짜 모델이 대신함(OpenAI 호출 없음).
 * 가짜 임베딩은 검색어마다 한 칸만 1인 벡터라, FAQ 임베딩을 SQL로 직접 넣어 검색어와의 유사도를 원하는 값으로 맞춤(기준값 0.4)
 */
@SpringBootTest
class ChatAnswerIntegrationTest {

    private static final Long MEMBER_ID = 7L;
    private static final String QUESTION = "돈 넣은 거 돌려받고 싶어요";
    private static final String REFUND_ANSWER = "충전 건마다 남은 금액을 원래 결제 수단으로 취소해요.";

    @Autowired
    private ChatFacade chatFacade;

    @Autowired
    private FaqRepository faqRepository;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private FakeEmbeddingModel fakeEmbeddingModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        fakeChatModel.reset();
        fakeEmbeddingModel.failing = false;
        jdbcTemplate.update("delete from chat_message");
        jdbcTemplate.update("delete from chat_conversation");
        faqRepository.deleteAllById(createdIds);
        jdbcTemplate.update("update faq set embedding = null");
        redisTemplate.delete(List.of(ChatRateLimitStore.REQUEST_COUNT_KEY + MEMBER_ID, ChatRateLimitStore.REQUEST_COUNT_KEY + 8));   // 요청 제한(1분 10번)에 걸리지 않게
    }

    @Test
    @DisplayName("근거 FAQ가 있으면 규칙과 FAQ를 시스템 메시지에, 질문을 사용자 메시지에 넣어 AI가 답하고, 새 대화에 질문·답변을 남김")
    void aiAnswer() {
        Long best = saveWithSimilarity(QUESTION, "충전한 예치금을 환불할 수 있나요?", REFUND_ANSWER, 0.9);
        Long second = saveWithSimilarity(QUESTION, "충전 한도가 있나요?", "1회 최대 100만 원이에요.", 0.5);

        ChatAnswerDto result = chatFacade.answer(MEMBER_ID, null, QUESTION);

        assertThat(result.answerType()).isEqualTo(ChatAnswerType.AI);
        assertThat(result.answer()).isEqualTo(FakeChatModel.DEFAULT_REPLY);
        assertThat(result.sources()).extracting(FaqSummaryDto::faqId).containsExactly(best, second);
        assertThat(result.sources().getFirst().categoryLabel()).isEqualTo("충전");

        String system = fakeChatModel.lastPrompt.getSystemMessage().getText();
        assertThat(system).contains("[FAQ]에 적힌 내용만 근거로", REFUND_ANSWER, "1회 최대 100만 원이에요.");
        assertThat(fakeChatModel.lastPrompt.getUserMessage().getText()).isEqualTo(QUESTION);

        List<ChatMessageDto> messages = chatFacade.getMessages(MEMBER_ID, result.conversationId());
        assertThat(messages).extracting(ChatMessageDto::role).containsExactly(ChatRole.USER, ChatRole.ASSISTANT);
        assertThat(messages.get(0).content()).isEqualTo(QUESTION);
        assertThat(messages.get(1).answerType()).isEqualTo(ChatAnswerType.AI);
        assertThat(messages.get(1).sources()).extracting(FaqSummaryDto::faqId).containsExactly(best, second);
    }

    @Test
    @DisplayName("기준값을 넘는 FAQ가 없으면 LLM을 부르지 않고 고객센터 안내 문구로 답함")
    void noMatch() {
        saveWithSimilarity(QUESTION, "관련 없는 질문", "관련 없는 답변", 0.3);

        ChatAnswerDto result = chatFacade.answer(MEMBER_ID, null, QUESTION);

        assertThat(result.answerType()).isEqualTo(ChatAnswerType.NO_MATCH);
        assertThat(result.answer()).isEqualTo(ChatPrompt.NO_MATCH_ANSWER);
        assertThat(result.sources()).isEmpty();
        assertThat(fakeChatModel.calls).hasValue(0);
    }

    @Test
    @DisplayName("LLM이 실패·시간 초과하거나 빈 답을 주면 가장 비슷한 FAQ 답변을 그대로 줌")
    void fallbackToFaq() {
        saveWithSimilarity(QUESTION, "충전 한도가 있나요?", "1회 최대 100만 원이에요.", 0.5);
        Long best = saveWithSimilarity(QUESTION, "충전한 예치금을 환불할 수 있나요?", REFUND_ANSWER, 0.9);

        fakeChatModel.failing = true;
        ChatAnswerDto failed = chatFacade.answer(MEMBER_ID, null, QUESTION);
        assertThat(failed.answerType()).isEqualTo(ChatAnswerType.FAQ);
        assertThat(failed.answer()).isEqualTo(REFUND_ANSWER);
        assertThat(failed.sources().getFirst().faqId()).isEqualTo(best);

        fakeChatModel.failing = false;
        fakeChatModel.reply = "  ";
        assertThat(chatFacade.answer(MEMBER_ID, null, QUESTION).answerType()).isEqualTo(ChatAnswerType.FAQ);
    }

    @Test
    @DisplayName("질문 임베딩(OpenAI)이 실패하면 FAQ를 찾을 수 없어 502이고, 대화에 남기지 않음")
    void embeddingFailure() {
        fakeEmbeddingModel.failing = true;

        assertThatThrownBy(() -> chatFacade.answer(MEMBER_ID, null, QUESTION))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR"));
        assertThat(fakeChatModel.calls).hasValue(0);
        assertThat(chatFacade.getConversations(MEMBER_ID, PageRequest.of(0, 20)).items()).isEmpty();
    }

    @Test
    @DisplayName("이어지는 질문: 앞 질문을 검색어 앞에 붙여 FAQ를 찾고, 앞 질문·답변을 프롬프트에 순서대로 넣음")
    void followUp() {
        saveWithSimilarity(QUESTION, "충전한 예치금을 환불할 수 있나요?", REFUND_ANSWER, 0.9);
        Long first = chatFacade.answer(MEMBER_ID, null, QUESTION).conversationId();

        String followUp = "그럼 며칠 걸려요?";
        Long refundDays = saveWithSimilarity(QUESTION + "\n" + followUp, "충전 취소는 며칠 걸리나요?", "카드사에 따라 3~7영업일 걸려요.", 0.8);
        fakeChatModel.reply = "3~7영업일 걸려요.";

        ChatAnswerDto result = chatFacade.answer(MEMBER_ID, first, followUp);

        assertThat(result.conversationId()).isEqualTo(first);
        assertThat(result.sources()).extracting(FaqSummaryDto::faqId).containsExactly(refundDays);
        List<Message> prompt = fakeChatModel.lastPrompt.getInstructions();
        assertThat(prompt).extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
        assertThat(prompt).extracting(Message::getText).endsWith(QUESTION, FakeChatModel.DEFAULT_REPLY, followUp);

        // 대화 없이 같은 질문만 하면 앞 질문이 없어 그 FAQ를 찾지 못함
        assertThat(chatFacade.answer(MEMBER_ID, null, followUp).answerType()).isEqualTo(ChatAnswerType.NO_MATCH);
    }

    @Test
    @DisplayName("프롬프트에는 같은 대화의 최근 6개 메시지만 넣음")
    void historyLimit() {
        saveWithSimilarity(QUESTION, "충전한 예치금을 환불할 수 있나요?", REFUND_ANSWER, 0.9);
        Long conversationId = chatFacade.answer(MEMBER_ID, null, QUESTION).conversationId();
        String previous = QUESTION;
        for (int i = 0; i < 4; i++) {
            String question = "질문 " + i;
            saveWithSimilarity(previous + "\n" + question, "FAQ " + i, "답변 " + i, 0.9);   // 검색어(앞 질문 + 이번 질문)에 맞는 FAQ가 있어야 LLM을 부름
            chatFacade.answer(MEMBER_ID, conversationId, question);
            previous = question;
        }

        // 시스템 1 + 최근 6 + 이번 질문 1
        assertThat(fakeChatModel.lastPrompt.getInstructions()).hasSize(8);
        assertThat(chatFacade.getMessages(MEMBER_ID, conversationId)).hasSize(10);
    }

    @Test
    @DisplayName("다른 회원의 대화로 묻거나 조회하면 404이고, 아무것도 남기지 않음")
    void otherMembersConversation() {
        saveWithSimilarity(QUESTION, "충전한 예치금을 환불할 수 있나요?", REFUND_ANSWER, 0.9);
        Long conversationId = chatFacade.answer(MEMBER_ID, null, QUESTION).conversationId();
        fakeChatModel.reset();

        assertThatThrownBy(() -> chatFacade.answer(8L, conversationId, QUESTION))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getResultCode()).isEqualTo("404-CONVERSATION_NOT_FOUND"));
        assertThatThrownBy(() -> chatFacade.getMessages(8L, conversationId))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getResultCode()).isEqualTo("404-CONVERSATION_NOT_FOUND"));
        assertThat(fakeChatModel.calls).hasValue(0);
        assertThat(chatFacade.getMessages(MEMBER_ID, conversationId)).hasSize(2);
    }

    @Test
    @DisplayName("내 대화 목록: 최근 대화 순, 제목은 첫 질문, 다른 회원의 대화는 빠짐")
    void conversations() {
        saveWithSimilarity(QUESTION, "충전한 예치금을 환불할 수 있나요?", REFUND_ANSWER, 0.9);
        Long older = chatFacade.answer(MEMBER_ID, null, QUESTION).conversationId();
        Long newer = chatFacade.answer(MEMBER_ID, null, "두 번째 대화").conversationId();
        chatFacade.answer(8L, null, QUESTION);
        chatFacade.answer(MEMBER_ID, older, "이어서 질문");   // 이어서 물으면 맨 위로

        List<ChatConversationDto> items = chatFacade.getConversations(MEMBER_ID, PageRequest.of(0, 20)).items();

        assertThat(items).extracting(ChatConversationDto::conversationId).containsExactly(older, newer);
        assertThat(items.getFirst().title()).isEqualTo(QUESTION);
    }

    // 검색어 벡터(한 칸만 1)와 코사인 유사도가 similarity가 되도록, 그 칸에 similarity, 다른 칸에 나머지 길이를 둠
    private Long saveWithSimilarity(String searchText, String question, String answer, double similarity) {
        Long id = faqRepository.save(Faq.create(FaqCategory.TOPUP, question, answer, true)).getId();
        createdIds.add(id);
        int dimensions = FakeEmbeddingModelConfig.DIMENSIONS;
        int queryIndex = Math.floorMod(searchText.hashCode(), dimensions);
        float[] v = new float[dimensions];
        v[queryIndex] = (float) similarity;
        v[(queryIndex + 1) % dimensions] = (float) Math.sqrt(1 - similarity * similarity);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        jdbcTemplate.update("update faq set embedding = cast(? as vector) where id = ?", sb.append(']').toString(), id);
        return id;
    }
}
