package com.backend.boundedContext.payment.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.boundedContext.payment.domain.PaymentRefund;

import jakarta.persistence.LockModeType;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {

    Optional<PaymentRefund> findByOrderId(Long orderId);

    // 같은 환불을 동시에 두 번 처리하지 않게 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentRefund r where r.id = :id")
    Optional<PaymentRefund> findByIdForUpdate(@Param("id") Long id);
}
