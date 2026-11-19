package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.app.dto.WithdrawalCheckDto;
import com.backend.boundedContext.member.app.dto.WithdrawalNotAllowedResponse;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.MemberPolicy;
import com.backend.boundedContext.member.domain.WithdrawalBlockReason;
import com.backend.boundedContext.member.out.LoginAttemptStore;
import com.backend.global.exception.DomainException;

@ExtendWith(MockitoExtension.class)
class MemberWithdrawUseCaseTest {

    private static final Long MEMBER_ID = 3L;
    private static final String EMAIL = "buyer@palette.test";

    @Mock
    private MemberSupport memberSupport;

    @Mock
    private MemberWithdrawalSupport memberWithdrawalSupport;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private LoginAttemptStore loginAttemptStore;

    // 로그인 최대 실패 5회, 잠금 15분
    @Spy
    private MemberPolicy memberPolicy = new MemberPolicy(5, 60, 30, 5, 5, 15, 14, false, "Lax");

    @Mock
    private MemberWithdrawProcessor memberWithdrawProcessor;

    @InjectMocks
    private MemberWithdrawUseCase memberWithdrawUseCase;

    private Member member;

    @BeforeEach
    void setUp() {
        member = Member.signUp(EMAIL, "encoded", "구매자");
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        given(memberSupport.getLoginMember(MEMBER_ID)).willReturn(member);
    }

    @Test
    @DisplayName("비밀번호가 맞고 조건을 충족하면 실패 횟수를 지우고 비식별화를 맡긴다")
    void withdraw_success() {
        given(passwordEncoder.matches("palette123!", "encoded")).willReturn(true);
        given(memberWithdrawalSupport.check(MEMBER_ID)).willReturn(WithdrawalCheckDto.of(false, false, 0, 0, false, false));

        memberWithdrawUseCase.withdraw(MEMBER_ID, "palette123!");

        verify(loginAttemptStore).deleteAttempts(EMAIL);
        verify(memberWithdrawProcessor).anonymize(MEMBER_ID);
    }

    @Test
    @DisplayName("조건이 남아 있으면 409 WITHDRAWAL_NOT_ALLOWED이고 data.reasons에 남은 조건을 담는다 (회원은 그대로)")
    void withdraw_notAllowed() {
        given(passwordEncoder.matches("palette123!", "encoded")).willReturn(true);
        given(memberWithdrawalSupport.check(MEMBER_ID)).willReturn(WithdrawalCheckDto.of(true, false, 0, 10_000, false, false));

        assertThatThrownBy(() -> memberWithdrawUseCase.withdraw(MEMBER_ID, "palette123!"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException de = (DomainException) e;
                    assertThat(de.getResultCode()).isEqualTo(MemberErrorCode.WITHDRAWAL_NOT_ALLOWED.resultCode());
                    assertThat(((WithdrawalNotAllowedResponse) de.getData()).reasons())
                            .containsExactly(WithdrawalBlockReason.ACTIVE_ORDERS, WithdrawalBlockReason.BALANCE_REMAINING);
                });
        verify(memberWithdrawProcessor, never()).anonymize(anyLong());   // 탈퇴가 거절되면 회원·연결 정보는 그대로
    }

    @Test
    @DisplayName("비밀번호가 틀리면 400 PASSWORD_MISMATCH이고, 확인 전에 로그인과 같은 시도 횟수를 올린다 (조건은 확인하지 않음)")
    void withdraw_wrongPassword() {
        given(loginAttemptStore.increaseAttempt(EMAIL, memberPolicy.loginLockTtl())).willReturn(1L);
        given(passwordEncoder.matches("wrong123!", "encoded")).willReturn(false);

        assertError(MemberErrorCode.PASSWORD_MISMATCH);
        verify(memberWithdrawalSupport, never()).check(anyLong());
        verify(loginAttemptStore, never()).deleteAttempts(anyString());
    }

    @Test
    @DisplayName("비밀번호가 맞으면 조건 때문에 거절돼도 시도 횟수를 지운다")
    void withdraw_notAllowed_resetsAttempts() {
        given(passwordEncoder.matches("palette123!", "encoded")).willReturn(true);
        given(memberWithdrawalSupport.check(MEMBER_ID)).willReturn(WithdrawalCheckDto.of(true, false, 0, 0, false, false));

        assertThatThrownBy(() -> memberWithdrawUseCase.withdraw(MEMBER_ID, "palette123!")).isInstanceOf(DomainException.class);
        verify(loginAttemptStore).deleteAttempts(EMAIL);
    }

    @Test
    @DisplayName("5번째 시도가 실패하면 잠그고 429 LOGIN_LOCKED")
    void withdraw_fifthFailure_locks() {
        given(loginAttemptStore.increaseAttempt(EMAIL, memberPolicy.loginLockTtl())).willReturn(5L);
        given(passwordEncoder.matches("wrong123!", "encoded")).willReturn(false);

        assertError(MemberErrorCode.LOGIN_LOCKED);
        verify(loginAttemptStore).lock(EMAIL, memberPolicy.loginLockTtl());
    }

    @Test
    @DisplayName("최대 횟수를 넘은 시도는 비밀번호를 확인하지 않고 429 LOGIN_LOCKED")
    void withdraw_locked() {
        given(loginAttemptStore.increaseAttempt(EMAIL, memberPolicy.loginLockTtl())).willReturn(6L);

        assertError(MemberErrorCode.LOGIN_LOCKED);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    private void assertError(MemberErrorCode errorCode) {
        assertThatThrownBy(() -> memberWithdrawUseCase.withdraw(MEMBER_ID, "wrong123!"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(errorCode.resultCode());
        verify(memberWithdrawProcessor, never()).anonymize(anyLong());
    }
}
