package com.backend.boundedContext.recommend.in;

import com.backend.boundedContext.recommend.app.RecommendFacade;
import com.backend.boundedContext.recommend.app.dto.ProductRecommendationsDto;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "맞춤 추천", description = "뷰티 설문 기반 상품 추천")
@RestController
@RequestMapping("/api/v1/ai/recommendations")
@RequiredArgsConstructor
public class ApiV1RecommendationController {

    private final RecommendFacade recommendFacade;

    @Operation(
            summary = "맞춤 상품 추천",
            description = "뷰티 설문이 있으면 피하는 성분이 든 상품(과 전성분을 모르는 상품)을 빼고, 피부 프로필과 비슷한 순서로 추천 이유와 함께 줍니다. "
                    + "설문이 없거나 로그인하지 않았으면 personalized=false이고 판매 중인 뷰티 상품을 최신순으로 줍니다. "
                    + "가격은 정가이고, 할인·품절은 상품 상세에서 봅니다.")
    @GetMapping
    public RsData<ProductRecommendationsDto> getProducts(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size
    ) {
        Long memberId = authMember == null ? null : authMember.memberId();
        return RsData.of("200-1", "추천 상품을 조회했습니다.", recommendFacade.getProducts(memberId, size));
    }
}
