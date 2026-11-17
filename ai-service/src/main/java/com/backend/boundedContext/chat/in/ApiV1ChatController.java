package com.backend.boundedContext.chat.in;

import com.backend.boundedContext.chat.app.ChatFacade;
import com.backend.boundedContext.chat.app.dto.ChatAnswerDto;
import com.backend.boundedContext.chat.in.dto.ChatRequest;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "챗봇", description = "FAQ 근거 AI 챗봇")
@RestController
@RequestMapping("/api/v1/ai/chat")
@RequiredArgsConstructor
public class ApiV1ChatController {

    private final ChatFacade chatFacade;

    @Operation(
            summary = "챗봇 질문",
            description = "질문과 뜻이 가까운 FAQ를 찾아 그 FAQ만 근거로 답합니다. 로그인이 필요합니다. "
                    + "answerType: AI(FAQ 근거로 AI가 답함), FAQ(AI가 실패해 가장 비슷한 FAQ 답변을 그대로 줌), "
                    + "NO_MATCH(관련 FAQ가 없어 고객센터 안내, sources는 빈 목록).")
    @ApiResponse(responseCode = "502", description = "502-EXTERNAL_SERVICE_ERROR 질문 임베딩(OpenAI) 실패로 FAQ를 찾지 못함")
    @PostMapping
    public RsData<ChatAnswerDto> chat(
            @Valid @RequestBody ChatRequest request
    ) {
        return RsData.of("200-1", "답변했습니다.", chatFacade.answer(request.message().strip()));
    }
}
