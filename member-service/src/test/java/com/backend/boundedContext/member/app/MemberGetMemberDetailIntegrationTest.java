package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.backend.boundedContext.member.app.dto.AdminMemberDetailDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberOrderPort;
import com.backend.boundedContext.member.out.MemberPaymentPort;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.shared.payment.dto.WalletSummaryDto;

/**
 * 관리자 회원 상세에서 지갑·최근 주문(다른 서비스 호출)을 DB 트랜잭션 밖에서 조회하는지 실제 DB로 확인한다. 로컬 Docker가 필요하다.
 */
@SpringBootTest
class MemberGetMemberDetailIntegrationTest {

    @Autowired
    private MemberFacade memberFacade;

    @Autowired
    private MemberRepository memberRepository;

    // 결제·주문은 다른 서비스의 내부 API를 HTTP로 부르므로 목으로 둔다
    @MockitoBean
    private MemberPaymentPort memberPaymentPort;

    @MockitoBean
    private MemberOrderPort memberOrderPort;

    private Member member;

    @BeforeEach
    void setUp() {
        member = memberRepository.save(Member.signUp("detail-it-" + UUID.randomUUID() + "@palette.com", "encoded", "홍길동"));
    }

    @AfterEach
    void cleanUp() {
        memberRepository.deleteById(member.getId());
    }

    @Test
    @DisplayName("지갑·최근 주문은 DB 트랜잭션 밖에서 조회하고, 회원 정보와 함께 응답한다")
    void getMemberDetail_portsOutsideTransaction() {
        AtomicBoolean walletInTransaction = new AtomicBoolean(true);
        AtomicBoolean ordersInTransaction = new AtomicBoolean(true);
        given(memberPaymentPort.findWallet(member.getId())).willAnswer(invocation -> {
            walletInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(new WalletSummaryDto(member.getId(), 10_000L, 0L));
        });
        given(memberOrderPort.getRecentOrders(member.getId(), 5)).willAnswer(invocation -> {
            ordersInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return List.of();
        });

        AdminMemberDetailDto detail = memberFacade.getMemberDetail(member.getId());

        assertThat(walletInTransaction).isFalse();
        assertThat(ordersInTransaction).isFalse();
        assertThat(detail.email()).isEqualTo(member.getEmail());
        assertThat(detail.walletState()).isEqualTo(AdminMemberDetailDto.WalletState.CREATED);
        assertThat(detail.wallet().balance()).isEqualTo(10_000L);
    }
}
