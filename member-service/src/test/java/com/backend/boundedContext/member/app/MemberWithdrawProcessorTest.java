package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.MemberSocialAccountRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.shared.member.dto.MemberStatus;

@ExtendWith(MockitoExtension.class)
class MemberWithdrawProcessorTest {

    private static final Long MEMBER_ID = 3L;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private SellerRepository sellerRepository;

    @Mock
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;

    @Mock
    private MemberSocialAccountRepository memberSocialAccountRepository;

    @InjectMocks
    private MemberWithdrawProcessor memberWithdrawProcessor;

    private Member member;

    @BeforeEach
    void setUp() {
        member = Member.signUp("buyer@palette.test", "encoded", "구매자");
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
    }

    @Test
    @DisplayName("회원을 비식별화하고 소셜 연결 정보를 지운다")
    void anonymize() {
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

        memberWithdrawProcessor.anonymize(MEMBER_ID);

        assertThat(member.getStatus()).isEqualTo(MemberStatus.DELETED);
        assertThat(member.getEmail()).isEqualTo("deleted-3@deleted.palette");
        verify(memberSocialAccountRepository).deleteAllByMemberId(MEMBER_ID);
    }

    @Test
    @DisplayName("판매자 정보가 있으면 판매자와 상태 이력도 비식별화한다")
    void anonymize_seller() {
        Seller seller = Seller.apply(member, "팔레트 뷰티", "1234567890", "홍길동", "01012345678",
                "국민은행", "12345601123456", "홍길동");
        ReflectionTestUtils.setField(seller, "id", 10L);
        SellerStatusHistory rejected = seller.reject("사업자번호 불일치");
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(seller));
        given(sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(10L)).willReturn(List.of(rejected));

        memberWithdrawProcessor.anonymize(MEMBER_ID);

        assertThat(seller.getRepresentativeName()).isEqualTo(Member.WITHDRAWN_NAME);
        assertThat(seller.getAccountNumber()).isEqualTo("0");
        assertThat(rejected.getRepresentativeName()).isNull();
        assertThat(rejected.getAccountLast4()).isNull();
    }

    @Test
    @DisplayName("조건 확인 사이에 이미 탈퇴했으면 아무것도 하지 않는다")
    void anonymize_alreadyWithdrawn() {
        member.withdraw();
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));

        memberWithdrawProcessor.anonymize(MEMBER_ID);

        verify(sellerRepository, never()).findByMemberId(anyLong());
        verify(memberSocialAccountRepository, never()).deleteAllByMemberId(anyLong());
    }
}
