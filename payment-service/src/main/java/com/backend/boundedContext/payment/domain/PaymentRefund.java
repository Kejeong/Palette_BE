package com.backend.boundedContext.payment.domain;

import java.time.LocalDateTime;

import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.order.dto.ReturnReasonCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 반품 환불 기록 (주문당 1개). 반품 승인 이벤트를 받으면 REQUESTED로 먼저 남기고, 환불은 따로 처리한다.
// 환불이 실패해도 이 행과 사유가 남아 재시도 스케줄러가 다시 처리한다
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "payment_refund")
public class PaymentRefund extends BaseIdAndTime {

    // 시스템 오류로 이만큼 실패하면 더 이상 자동으로 재시도하지 않고 사람이 확인한다
    public static final int MAX_SYSTEM_ERROR_RETRIES = 5;

    private static final int FAIL_REASON_MAX_LENGTH = 300;

    // 주문당 반품 승인은 한 번뿐이라 유니크. 이벤트 ID가 달라도 같은 주문은 한 번만 환불한다
    @Column(nullable = false, unique = true)
    private Long orderId;

    // 처리할 때 주문의 결제를 찾아 채운다. 결제를 못 찾으면 비어 있다
    private Long paymentId;

    // 이벤트의 환불 금액. 결제 금액과 같은지 대조하는 데만 쓴다
    @Column(nullable = false)
    private long requestedAmount;

    // 구매자가 실제로 받은 금액 (COMPLETED 때 채움). 단순 변심이면 처음 배송비·반품 배송비를 뺀 금액
    @Column(nullable = false)
    private long amount;

    // 구매자가 낸 반품 배송비 (단순 변심만, 나머지는 0)
    @Column(nullable = false)
    private long returnPickupFee;

    // 반품 사유. 사유를 싣기 전에 발행된 이벤트면 null이고 전액 환불한다
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ReturnReasonCode reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentRefundStatus status;

    // 시스템 오류(FAILED_SYSTEM_ERROR)로 실패한 횟수
    @Column(nullable = false)
    private int retryCount;

    @Column(length = FAIL_REASON_MAX_LENGTH)
    private String failReason;

    private LocalDateTime refundedAt;

    public static PaymentRefund request(Long orderId, long requestedAmount, ReturnReasonCode reason) {
        PaymentRefund refund = new PaymentRefund();
        refund.orderId = orderId;
        refund.requestedAmount = requestedAmount;
        refund.reason = reason;
        refund.status = PaymentRefundStatus.REQUESTED;
        return refund;
    }

    // 지금 환불을 처리해도 되는 상태인지: 처리 전이거나, 시스템 오류로 실패했지만 재시도 횟수가 남은 경우
    public boolean isProcessable() {
        return status == PaymentRefundStatus.REQUESTED
                || (status == PaymentRefundStatus.FAILED_SYSTEM_ERROR && retryCount < MAX_SYSTEM_ERROR_RETRIES);
    }

    // 단순 변심만 처음 배송비를 돌려주지 않고 반품 배송비를 뺀다. 기타·불량·사유 없음은 전액 환불
    public boolean isBuyerPaysShipping() {
        return reason == ReturnReasonCode.CHANGE_OF_MIND;
    }

    public void complete(Long paymentId, long amount, long returnPickupFee, LocalDateTime now) {
        requireProcessable();
        this.paymentId = paymentId;
        this.amount = amount;
        this.returnPickupFee = returnPickupFee;
        this.status = PaymentRefundStatus.COMPLETED;
        this.failReason = null;
        this.refundedAt = now;
    }

    // 실패 기록. 시스템 오류면 재시도 횟수를 늘린다. paymentId는 결제를 찾은 경우에만 넘긴다(못 찾았으면 null)
    public void fail(PaymentRefundStatus failStatus, Long paymentId, String reason) {
        if (!failStatus.isFailure()) throw new IllegalArgumentException("실패 상태가 아닙니다: " + failStatus);
        requireProcessable();
        if (paymentId != null) this.paymentId = paymentId;
        this.status = failStatus;
        this.failReason = truncate(reason);
        if (failStatus == PaymentRefundStatus.FAILED_SYSTEM_ERROR) this.retryCount++;
    }

    // 처리할 수 없는 상태(완료, 사람이 볼 실패, 재시도 끝)에서 바꾸려 하면 호출한 쪽 버그다
    private void requireProcessable() {
        if (!isProcessable()) throw new IllegalStateException("처리할 수 없는 환불 상태입니다: " + status + ", retryCount=" + retryCount);
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= FAIL_REASON_MAX_LENGTH) return reason;
        return reason.substring(0, FAIL_REASON_MAX_LENGTH);
    }
}
