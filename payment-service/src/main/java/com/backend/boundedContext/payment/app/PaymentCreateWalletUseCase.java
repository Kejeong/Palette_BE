package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.out.WalletRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentCreateWalletUseCase {

    private final WalletRepository walletRepository;

    // 잔액 0인 지갑 생성 (이미 있으면 무시). 새로 만들었으면 true
    // 호출하는 쪽의 트랜잭션 안에서 실행함 (가입 이벤트 컨슈머, 충전 준비)
    public boolean createWallet(Long memberId) {
        return walletRepository.insertIfAbsent(memberId, LocalDateTime.now()) > 0;
    }
}
