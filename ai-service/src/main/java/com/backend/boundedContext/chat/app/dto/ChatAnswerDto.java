package com.backend.boundedContext.chat.app.dto;

import com.backend.boundedContext.chat.domain.ChatAnswerType;
import com.backend.boundedContext.faq.domain.FaqCategory;

import java.util.List;

/**
 * 챗봇 답변
 *
 * @param answerType 답변을 만든 방식 (AI, FAQ, NO_MATCH)
 * @param sources    답변 근거 FAQ(유사도 높은 순). NO_MATCH면 빈 목록
 */
public record ChatAnswerDto(
        String answer,
        ChatAnswerType answerType,
        List<Source> sources
) {
    /**
     * @param categoryLabel 화면 표시용 카테고리 이름 (예: 반품·환불)
     */
    public record Source(
            Long faqId,
            FaqCategory category,
            String categoryLabel,
            String question
    ) {}
}
