package com.backend.boundedContext.payment.in.dto;

import com.backend.boundedContext.payment.domain.PgProvider;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 충전 준비 요청. 금액 단위·범위는 정책(PaymentPolicy)에서 검사해 400 TOPUP_AMOUNT_INVALID로 응답
 */
public record TopupPrepareRequest(
        @Schema(description = "충전 금액(원). 10,000원 단위, 10,000~1,000,000원", example = "20000")
        @NotNull
        Long amount,

        @Schema(description = "결제할 PG. 없으면 TOSS. HECTO는 신용카드만", example = "TOSS", nullable = true)
        PgProvider pgProvider
) {
    // PG를 고르지 않으면 토스 (PG 선택이 없던 기존 요청과 같게)
    public PgProvider pgProviderOrDefault() {
        return pgProvider != null ? pgProvider : PgProvider.TOSS;
    }
}
