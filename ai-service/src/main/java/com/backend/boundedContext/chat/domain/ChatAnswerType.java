package com.backend.boundedContext.chat.domain;

// 답변을 만든 방식
public enum ChatAnswerType {
    AI,          // 근거 FAQ로 LLM이 만든 답변
    FAQ,         // LLM이 실패·시간 초과라 가장 비슷한 FAQ 답변을 그대로 줌
    NO_MATCH     // 관련 FAQ가 없어 고객센터 안내 문구로 답함(LLM 호출 안 함)
}
