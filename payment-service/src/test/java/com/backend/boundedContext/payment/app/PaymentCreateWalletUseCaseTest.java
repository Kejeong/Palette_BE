package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.payment.out.WalletRepository;

@ExtendWith(MockitoExtension.class)
class PaymentCreateWalletUseCaseTest {

    @Mock
    private WalletRepository walletRepository;

    @InjectMocks
    private PaymentCreateWalletUseCase paymentCreateWalletUseCase;

    @Test
    @DisplayName("지갑이 없어 새로 만들면 true")
    void createWallet_new() {
        given(walletRepository.insertIfAbsent(eq(1L), any())).willReturn(1);

        assertThat(paymentCreateWalletUseCase.createWallet(1L)).isTrue();
    }

    @Test
    @DisplayName("이미 지갑이 있으면 만들지 않고 false")
    void createWallet_alreadyExists() {
        given(walletRepository.insertIfAbsent(eq(1L), any())).willReturn(0);

        assertThat(paymentCreateWalletUseCase.createWallet(1L)).isFalse();
    }
}
