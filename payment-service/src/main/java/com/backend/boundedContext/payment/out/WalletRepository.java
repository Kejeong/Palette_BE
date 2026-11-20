package com.backend.boundedContext.payment.out;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.boundedContext.payment.domain.Wallet;

import jakarta.persistence.LockModeType;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByMemberId(Long memberId);

    /**
     * 잔액 0인 지갑을 만들고, 그 회원의 지갑이 이미 있으면 아무것도 하지 않음. 만들었으면 1, 아니면 0
     * 가입 이벤트 컨슈머와 충전 준비가 같은 회원의 지갑을 동시에 만들어도 member_id 유니크 위반 없이 하나만 남음 (먼저 넣은 트랜잭션이 끝날 때까지 기다렸다가 아무것도 하지 않음)
     * 네이티브 INSERT는 JPA 감사(@CreatedDate·@LastModifiedDate)를 거치지 않으므로 시각을 직접 넣음
     */
    @Modifying
    @Query(value = """
            insert into wallet (member_id, balance, created_at, updated_at)
            values (:memberId, 0, :now, :now)
            on conflict (member_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    // 잔액을 바꾸기 전에 지갑 행을 잠금. 트랜잭션이 끝날 때까지 다른 잔액 변경은 대기
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.memberId = :memberId")
    Optional<Wallet> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
