package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.pg.PgCheckout;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;
import com.backend.boundedContext.payment.out.pg.TopupGateways;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentPrepareTopupUseCase {

    private final PaymentPolicy paymentPolicy;
    private final WalletRepository walletRepository;
    private final TopupRepository topupRepository;
    private final PaymentMemberPort memberPort;
    private final TopupGateways topupGateways;

    /**
     * 충전 준비: 금액 검증 후 주문번호를 발급하고 결제할 PG·결제 요청 시각과 함께 READY로 저장
     * 탈퇴한 회원은 남은 Access Token으로 충전할 수 없게 401 (탈퇴한 회원 지갑에 돈이 들어가지 않도록)
     * 결제창 요청 값은 저장 전에 만듦. 키 설정 오류로 못 만들면 충전 건을 남기지 않음
     */
    public TopupPrepareDto prepareTopup(Long memberId, long amount, PgProvider pgProvider) {
        memberPort.checkActiveMember(memberId);
        paymentPolicy.validateTopupAmount(amount);
        TopupGatewayPort gateway = topupGateways.get(pgProvider);

        Wallet wallet = walletRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));

        Topup topup = Topup.create(memberId, wallet, amount, pgProvider, LocalDateTime.now());
        PgCheckout checkout = gateway.checkout(PgPaymentRef.from(topup)).orElse(null);
        topupRepository.save(topup);

        return TopupPrepareDto.of(topup, checkout);
    }
}
