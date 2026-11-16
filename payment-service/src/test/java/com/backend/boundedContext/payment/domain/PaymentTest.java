package com.backend.boundedContext.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.global.exception.DomainException;

class PaymentTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 10, 0);

    private final Wallet wallet = Wallet.create(3L);

    @Test
    @DisplayName("결제 성공 건은 PAID이고 결제 일시가 남으며, 회원 ID는 지갑에서 가져온다")
    void paid() {
        Payment payment = Payment.paid(10L, "ORD-TEST-10", wallet, 25_000, "PAY-10-a", NOW);

        assertThat(payment.getOrderId()).isEqualTo(10L);
        assertThat(payment.getOrderNumber()).isEqualTo("ORD-TEST-10");
        assertThat(payment.getMemberId()).isEqualTo(3L);
        assertThat(payment.getWallet()).isSameAs(wallet);
        assertThat(payment.getAmount()).isEqualTo(25_000);
        assertThat(payment.getIdempotencyKey()).isEqualTo("PAY-10-a");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaidAt()).isEqualTo(NOW);
        assertThat(payment.isPaid()).isTrue();
    }

    @Test
    @DisplayName("결제 실패 건은 FAILED_* 상태와 실패 사유가 남고 결제 일시는 없다")
    void failed() {
        Payment payment = Payment.failed(10L, "ORD-TEST-10", wallet, 25_000, "PAY-10-a",
                PaymentStatus.FAILED_INSUFFICIENT_BALANCE, "잔액 부족");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED_INSUFFICIENT_BALANCE);
        assertThat(payment.getOrderNumber()).isEqualTo("ORD-TEST-10");
        assertThat(payment.getFailReason()).isEqualTo("잔액 부족");
        assertThat(payment.getPaidAt()).isNull();
        assertThat(payment.isPaid()).isFalse();
    }

    @Test
    @DisplayName("실패가 아닌 상태로는 실패 건을 만들 수 없다")
    void failed_notFailureStatus() {
        assertThatThrownBy(() -> Payment.failed(10L, "ORD-TEST-10", wallet, 25_000, "PAY-10-a", PaymentStatus.PAID, "사유"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결제 금액은 0보다 커야 한다")
    void paid_nonPositiveAmount() {
        assertThatThrownBy(() -> Payment.paid(10L, "ORD-TEST-10", wallet, 0, "PAY-10-a", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("주문번호 없이는 결제 건을 만들 수 없다 (성공·실패 모두)")
    void orderNumberRequired() {
        assertThatThrownBy(() -> Payment.paid(10L, null, wallet, 25_000, "PAY-10-a", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Payment.failed(10L, " ", wallet, 25_000, "PAY-10-a",
                PaymentStatus.FAILED_INSUFFICIENT_BALANCE, "잔액 부족"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FAILED_로 시작하는 상태만 실패 상태다")
    void statusIsFailure() {
        assertThat(PaymentStatus.FAILED_INSUFFICIENT_BALANCE.isFailure()).isTrue();
        assertThat(PaymentStatus.FAILED_ORDER_EXPIRED.isFailure()).isTrue();
        assertThat(PaymentStatus.FAILED_SYSTEM_ERROR.isFailure()).isTrue();
        assertThat(PaymentStatus.PAID.isFailure()).isFalse();
        assertThat(PaymentStatus.REFUNDED.isFailure()).isFalse();
    }

    @Test
    @DisplayName("결제하면 구매자 지갑에서 결제 금액이 빠지고, 배송비는 플랫폼 지갑으로, 나머지는 홀딩으로 간다")
    void pay() {
        Wallet buyer = walletWith(1L, 30_000);
        Wallet platform = walletWith(99L, 0);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 3_000, "PAY-10-a");

        PaymentRecords records = payment.pay(buyer, platform, NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaidAt()).isEqualTo(NOW);
        assertThat(buyer.getBalance()).isEqualTo(5_000);
        assertThat(platform.getBalance()).isEqualTo(3_000);
        assertThat(records.transactions()).extracting(WalletTransaction::getType, WalletTransaction::getAmount)
                .containsExactly(
                        tuple(WalletTransactionType.PAYMENT, -25_000L),
                        tuple(WalletTransactionType.SHIPPING_FEE, 3_000L));
        assertThat(records.holding().getAmount()).isEqualTo(22_000);
    }

    @Test
    @DisplayName("배송비가 없으면 플랫폼 지갑 없이 결제되고 결제 금액 전부가 홀딩된다")
    void pay_noShippingFee() {
        Wallet buyer = walletWith(1L, 30_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 0, "PAY-10-a");

        PaymentRecords records = payment.pay(buyer, null, NOW);

        assertThat(records.transactions()).hasSize(1);
        assertThat(records.holding().getAmount()).isEqualTo(25_000);
    }

    @Test
    @DisplayName("준비 상태가 아니면 결제할 수 없다 (같은 결제를 두 번 결제하지 않는다)")
    void pay_twice() {
        Wallet buyer = walletWith(1L, 30_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 10_000, 0, "PAY-10-a");
        payment.pay(buyer, null, NOW);

        assertThatThrownBy(() -> payment.pay(buyer, null, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(buyer.getBalance()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("배송비가 있는데 플랫폼 지갑이 없으면 돈을 옮기기 전에 막는다")
    void pay_shippingFeeWithoutPlatformWallet() {
        Wallet buyer = walletWith(1L, 30_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 3_000, "PAY-10-a");

        assertThatThrownBy(() -> payment.pay(buyer, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(buyer.getBalance()).isEqualTo(30_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("결제를 취소하면 홀딩이 해제되고, 구매자에게 결제 금액 전부를, 플랫폼 지갑에서 배송비를 되돌린다")
    void cancel() {
        Wallet buyer = walletWith(1L, 30_000);
        Wallet platform = walletWith(99L, 0);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 3_000, "PAY-10-a");
        WalletHolding holding = payment.pay(buyer, platform, NOW).holding();

        List<WalletTransaction> transactions = payment.cancel(buyer, platform, holding, NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(holding.getStatus()).isEqualTo(WalletHoldingStatus.RELEASED);
        assertThat(buyer.getBalance()).isEqualTo(30_000);
        assertThat(platform.getBalance()).isZero();
        assertThat(transactions).extracting(WalletTransaction::getType)
                .containsExactly(WalletTransactionType.PAYMENT_CANCEL, WalletTransactionType.SHIPPING_FEE_CANCEL);
    }

    @Test
    @DisplayName("정산 지급이 시작된 결제는 취소할 수 없고 돈도 움직이지 않는다")
    void cancel_afterSettlementStarted() {
        Wallet buyer = walletWith(1L, 30_000);
        Wallet platform = walletWith(99L, 0);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 3_000, "PAY-10-a");
        WalletHolding holding = payment.pay(buyer, platform, NOW).holding();
        holding.settle(1_000, NOW);

        assertThatThrownBy(() -> payment.cancel(buyer, platform, holding, NOW))
                .isInstanceOf(DomainException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(buyer.getBalance()).isEqualTo(5_000);
        assertThat(platform.getBalance()).isEqualTo(3_000);
    }

    private static Wallet walletWith(Long memberId, long balance) {
        Wallet wallet = Wallet.create(memberId);
        if (balance > 0) wallet.deposit(balance);
        return wallet;
    }
}
