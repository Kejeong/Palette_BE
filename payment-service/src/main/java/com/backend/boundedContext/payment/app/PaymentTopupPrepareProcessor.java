package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.pg.PgCheckout;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 충전 준비의 DB 트랜잭션 구간: 지갑 조회(없으면 생성) → 충전 건 생성 → 결제창 요청 값 생성 → 저장
 * 회원 확인(회원 서비스 호출)·PG 선택은 PaymentPrepareTopupUseCase가 이 트랜잭션 밖에서 먼저 함
 * (같은 클래스 내부 호출은 Spring 프록시를 거치지 않아 @Transactional이 적용되지 않으므로 별도 빈으로 분리)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTopupPrepareProcessor {

    private final WalletRepository walletRepository;
    private final TopupRepository topupRepository;
    private final PaymentCreateWalletUseCase paymentCreateWalletUseCase;

    // 결제창 요청 값은 외부 호출이 아니라 저장 전에 만듦. 키 설정 오류로 못 만들면 충전 건을 남기지 않음
    @Transactional
    public TopupPrepareDto create(Long memberId, long amount, PgProvider pgProvider, TopupGatewayPort gateway) {
        Wallet wallet = walletRepository.findByMemberId(memberId)
                .orElseGet(() -> createMissingWallet(memberId));

        Topup topup = Topup.create(memberId, wallet, amount, pgProvider, LocalDateTime.now());
        PgCheckout checkout = gateway.checkout(PgPaymentRef.from(topup)).orElse(null);
        topupRepository.save(topup);

        return TopupPrepareDto.of(topup, checkout);
    }

    // 가입 이벤트 처리가 늦어지거나 DLT로 가 지갑이 아직 없으면 여기서 만들고 진행함
    // 그사이 가입 이벤트 컨슈머가 먼저 만들었으면 만들지 않고 그 지갑을 씀
    private Wallet createMissingWallet(Long memberId) {
        if (paymentCreateWalletUseCase.createWallet(memberId)) {
            log.warn("지갑이 없어 충전 준비에서 만들었습니다. 가입 이벤트 처리가 늦어지거나 실패했을 수 있습니다. memberId={}", memberId);
        }
        return walletRepository.findByMemberId(memberId).orElseThrow();
    }
}
