package com.backend.boundedContext.chat.app;

import com.backend.FakeChatModelConfig.FakeChatModel;
import com.backend.FakeEmbeddingModelConfig;
import com.backend.FakeEmbeddingModelConfig.FakeEmbeddingModel;
import com.backend.boundedContext.chat.app.dto.ChatAnswerDto;
import com.backend.boundedContext.chat.domain.ChatAnswerType;
import com.backend.boundedContext.chat.domain.ChatPrompt;
import com.backend.boundedContext.faq.domain.Faq;
import com.backend.boundedContext.faq.domain.FaqCategory;
import com.backend.boundedContext.faq.out.FaqRepository;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FAQ 근거 답변 생성을 실제 DB(pgvector)로 확인함. 임베딩·채팅은 가짜 모델이 대신함(OpenAI 호출 없음).
 * FAQ 임베딩은 SQL로 직접 넣어 질문과의 유사도를 원하는 값으로 맞춤(기준값 0.4)
 */
@SpringBootTest
class ChatAnswerIntegrationTest {

    private static final String QUESTION = "돈 넣은 거 돌려받고 싶어요";

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

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        fakeChatModel.reset();
        fakeEmbeddingModel.failing = false;
        faqRepository.deleteAllById(createdIds);
        jdbcTemplate.update("update faq set embedding = null");
    }

    @Test
    @DisplayName("근거 FAQ가 있으면 규칙과 FAQ를 시스템 메시지에, 질문을 사용자 메시지에 넣어 AI가 답함")
    void aiAnswer() {
        Long best = saveWithSimilarity("충전한 예치금을 환불할 수 있나요?", "충전 건마다 남은 금액을 원래 결제 수단으로 취소해요.", 0.9);
        Long second = saveWithSimilarity("충전 한도가 있나요?", "1회 최대 100만 원이에요.", 0.5);

        ChatAnswerDto result = chatFacade.answer(QUESTION);

        assertThat(result.answerType()).isEqualTo(ChatAnswerType.AI);
        assertThat(result.answer()).isEqualTo(FakeChatModel.DEFAULT_REPLY);
        assertThat(result.sources()).extracting(ChatAnswerDto.Source::faqId).containsExactly(best, second);
        assertThat(result.sources().getFirst().categoryLabel()).isEqualTo("충전");

        String system = fakeChatModel.lastPrompt.getSystemMessage().getText();
        assertThat(system).contains("[FAQ]에 적힌 내용만 근거로","충전 건마다 남은 금액을 원래 결제 수단으로 취소해요.", "1회 최대 100만 원이에요.");
        assertThat(fakeChatModel.lastPrompt.getUserMessage().getText()).isEqualTo(QUESTION);
    }

    @Test
    @DisplayName("기준값을 넘는 FAQ가 없으면 LLM을 부르지 않고 고객센터 안내 문구로 답함")
    void noMatch() {
        saveWithSimilarity("관련 없는 질문", "관련 없는 답변", 0.3);

        ChatAnswerDto result = chatFacade.answer(QUESTION);

        assertThat(result.answerType()).isEqualTo(ChatAnswerType.NO_MATCH);
        assertThat(result.answer()).isEqualTo(ChatPrompt.NO_MATCH_ANSWER);
        assertThat(result.sources()).isEmpty();
        assertThat(fakeChatModel.calls).hasValue(0);
    }

    @Test
    @DisplayName("LLM이 실패·시간 초과하거나 빈 답을 주면 가장 비슷한 FAQ 답변을 그대로 줌")
    void fallbackToFaq() {
        saveWithSimilarity("충전 한도가 있나요?", "1회 최대 100만 원이에요.", 0.5);
        Long best = saveWithSimilarity("충전한 예치금을 환불할 수 있나요?", "충전 건마다 남은 금액을 원래 결제 수단으로 취소해요.", 0.9);

        fakeChatModel.failing = true;
        ChatAnswerDto failed = chatFacade.answer(QUESTION);
        assertThat(failed.answerType()).isEqualTo(ChatAnswerType.FAQ);
        assertThat(failed.answer()).isEqualTo("충전 건마다 남은 금액을 원래 결제 수단으로 취소해요.");
        assertThat(failed.sources().getFirst().faqId()).isEqualTo(best);

        fakeChatModel.failing = false;
        fakeChatModel.reply = "  ";
        assertThat(chatFacade.answer(QUESTION).answerType()).isEqualTo(ChatAnswerType.FAQ);
    }

    @Test
    @DisplayName("질문 임베딩(OpenAI)이 실패하면 FAQ를 찾을 수 없어 502")
    void embeddingFailure() {
        fakeEmbeddingModel.failing = true;

        assertThatThrownBy(() -> chatFacade.answer(QUESTION))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR"));
        assertThat(fakeChatModel.calls).hasValue(0);
    }

    // 질문 벡터(한 칸만 1)와 코사인 유사도가 similarity가 되도록, 그 칸에 similarity, 다른 칸에 나머지 길이를 둠
    private Long saveWithSimilarity(String question, String answer, double similarity) {
        Long id = faqRepository.save(Faq.create(FaqCategory.TOPUP, question, answer, true)).getId();
        createdIds.add(id);
        int dimensions = FakeEmbeddingModelConfig.DIMENSIONS;
        int queryIndex = Math.floorMod(QUESTION.hashCode(), dimensions);
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
