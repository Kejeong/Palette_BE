package com.backend.boundedContext.chat.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param message 사용자 질문. 앞뒤 공백을 지우고 씀
 */
public record ChatRequest(
        @NotBlank @Size(max = 200) String message
) {}
