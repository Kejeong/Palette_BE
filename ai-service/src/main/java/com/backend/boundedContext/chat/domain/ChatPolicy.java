package com.backend.boundedContext.chat.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 챗봇 컨텍스트 정책 값 (custom.chat.*)
@Component
@Getter
public class ChatPolicy {

    // 프롬프트에 넣는 같은 대화의 최근 메시지 수(질문·답변 합산)
    private final int historyMessages;

    // FAQ 검색어 앞에 붙이는 같은 대화의 최근 질문 수. "그럼 며칠 걸려요?"처럼 주어가 빠진 질문도 맞는 FAQ를 찾게
    private final int searchHistoryQuestions;

    public ChatPolicy(@Value("${custom.chat.historyMessages}") int historyMessages,
                      @Value("${custom.chat.searchHistoryQuestions}") int searchHistoryQuestions) {
        if (historyMessages < 0) throw new IllegalArgumentException("custom.chat.historyMessages는 0 이상이어야 합니다: " + historyMessages);
        if (searchHistoryQuestions < 0) throw new IllegalArgumentException("custom.chat.searchHistoryQuestions는 0 이상이어야 합니다: " + searchHistoryQuestions);
        this.historyMessages = historyMessages;
        this.searchHistoryQuestions = searchHistoryQuestions;
    }
}
