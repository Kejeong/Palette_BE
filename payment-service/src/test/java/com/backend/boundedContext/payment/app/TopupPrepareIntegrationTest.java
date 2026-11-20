package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.support.TestPgCircuitBreakers;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * 충전 준비에서 회원 확인(회원 서비스 호출)을 DB 트랜잭션 밖에서 하는지 실제 DB로 확인한다.
 * 로컬 Docker PostgreSQL이 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class TopupPrepareIntegrationTest {

    private static final long AMOUNT = 20_000;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TopupRepository topupRepository;

    @MockitoBean   // 회원 서비스 대신 쓰는 목
    private PaymentMemberPort memberPort;

    private Long memberId;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        TestPgCircuitBreakers.resetAll(circuitBreakerRegistry);
        memberId = ThreadLocalRandom.current().nextLong(6_000_000_000L, 7_000_000_000L);
        wallet = walletRepository.save(Wallet.create(memberId));
    }

    @AfterEach
    void cleanUp() {
        topupRepository.deleteAll(topupRepository.findAll().stream()
                .filter(topup -> topup.getMemberId().equals(memberId)).toList());
        walletRepository.deleteById(wallet.getId());
    }

    @Test
    @DisplayName("회원 확인은 DB 트랜잭션 밖에서 하고, 확인되면 READY 충전 건을 저장한다")
    void prepare_memberCheckOutsideTransaction() {
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        willAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).given(memberPort).checkActiveMember(memberId);

        String orderId = paymentFacade.prepareTopup(memberId, AMOUNT).orderId();

        assertThat(transactionActive).isFalse();
        assertThat(topupRepository.findByOrderIdAndMemberId(orderId, memberId)).isPresent();
    }

    @Test
    @DisplayName("회원 확인이 실패하면(예: 회원 서비스 502) 충전 건을 만들지 않는다")
    void prepare_memberCheckFailed_noTopup() {
        willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR)).given(memberPort).checkActiveMember(memberId);

        assertThatThrownBy(() -> paymentFacade.prepareTopup(memberId, AMOUNT))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR"));
        assertThat(topupRepository.findAll()).noneMatch(topup -> topup.getMemberId().equals(memberId));
    }
}
