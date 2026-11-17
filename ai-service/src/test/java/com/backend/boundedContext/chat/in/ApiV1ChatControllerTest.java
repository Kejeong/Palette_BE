package com.backend.boundedContext.chat.in;

import com.backend.boundedContext.chat.app.ChatFacade;
import com.backend.boundedContext.chat.app.dto.ChatAnswerDto;
import com.backend.boundedContext.chat.domain.ChatAnswerType;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1ChatController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ChatFacade chatFacade;

    @Test
    @DisplayName("로그인하면 앞뒤 공백을 지운 질문으로 답함")
    void chat() throws Exception {
        given(chatFacade.answer("충전 취소")).willReturn(new ChatAnswerDto("답변", ChatAnswerType.AI, List.of()));

        mockMvc.perform(post("/api/v1/ai/chat").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"message":"  충전 취소 "}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.answerType").value("AI"));
    }

    @Test
    @DisplayName("로그인하지 않으면 401, 질문이 비었거나 200자를 넘으면 400")
    void invalid() throws Exception {
        mockMvc.perform(post("/api/v1/ai/chat").contentType(MediaType.APPLICATION_JSON).content("""
                        {"message":"충전 취소"}"""))
                .andExpect(status().isUnauthorized());
        for (String message : List.of(" ", "가".repeat(201))) {
            mockMvc.perform(post("/api/v1/ai/chat").header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"" + message + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(chatFacade);
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.createAccessToken(3L, MemberRole.USER);
    }
}
