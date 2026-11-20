package com.backend.boundedContext.payment.app;

import java.util.List;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;
import com.backend.boundedContext.payment.out.pg.TopupGateways;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentPrepareTopupUseCase {

    // 충전 PG 우선순위: 토스가 기본이고, 토스가 사용 불가(서킷 OPEN)면 헥토
    private static final List<PgProvider> PG_PRIORITY = List.of(PgProvider.TOSS, PgProvider.HECTO);

    private final PaymentPolicy paymentPolicy;
    private final PaymentMemberPort memberPort;
    private final TopupGateways topupGateways;
    private final PaymentTopupPrepareProcessor paymentTopupPrepareProcessor;

    /**
     * 충전 준비: 금액 검증 후 주문번호를 발급하고 결제할 PG·결제 요청 시각과 함께 READY로 저장
     * PG는 사용자가 고르지 않고 우선순위대로 사용 가능한 PG를 고름. 모두 사용 불가면 503 TOPUP_PG_UNAVAILABLE
     * 탈퇴한 회원은 남은 Access Token으로 충전할 수 없게 401 (탈퇴한 회원 지갑에 돈이 들어가지 않도록)
     * 회원 확인(회원 서비스 호출)·PG 선택은 DB 트랜잭션 밖에서 하고, 지갑 조회·충전 건 저장만 PaymentTopupPrepareProcessor의 트랜잭션으로 함
     */
    public TopupPrepareDto prepareTopup(Long memberId, long amount) {
        memberPort.checkActiveMember(memberId);
        paymentPolicy.validateTopupAmount(amount);
        PgProvider pgProvider = PG_PRIORITY.stream()
                .filter(topupGateways::isAvailable)
                .findFirst()
                .orElseThrow(() -> new DomainException(PaymentErrorCode.TOPUP_PG_UNAVAILABLE));
        TopupGatewayPort gateway = topupGateways.get(pgProvider);

        return paymentTopupPrepareProcessor.create(memberId, amount, pgProvider, gateway);
    }
}
