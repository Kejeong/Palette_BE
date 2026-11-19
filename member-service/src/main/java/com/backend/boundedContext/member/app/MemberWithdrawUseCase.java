package com.backend.boundedContext.member.app;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.WithdrawalCheckDto;
import com.backend.boundedContext.member.app.dto.WithdrawalNotAllowedResponse;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.MemberPolicy;
import com.backend.boundedContext.member.out.LoginAttemptStore;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberWithdrawUseCase {

    private final MemberSupport memberSupport;
    private final MemberWithdrawalSupport memberWithdrawalSupport;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptStore loginAttemptStore;
    private final MemberPolicy memberPolicy;
    private final MemberWithdrawProcessor memberWithdrawProcessor;

    // 비밀번호로 본인 확인 → 탈퇴 조건 재확인 → 회원·판매자 비식별화, 소셜 연결 정보 삭제
    // 조건 확인은 다른 서비스를 HTTP로 부르므로 DB 트랜잭션 밖에서 하고, 비식별화만 트랜잭션으로 처리함 (상대가 느려도 커넥션을 잡고 있지 않게)
    // 조건 확인은 지금도 다른 서비스를 잠그지 않은 시점 확인이라, 트랜잭션 밖으로 옮겨도 보장 수준은 같음
    public void withdraw(Long memberId, String password) {
        Member member = memberSupport.getLoginMember(memberId);
        checkPassword(member, password);

        WithdrawalCheckDto check = memberWithdrawalSupport.check(memberId);
        if (!check.withdrawable()) {
            throw new DomainException(MemberErrorCode.WITHDRAWAL_NOT_ALLOWED, new WithdrawalNotAllowedResponse(check.reasons()));
        }

        memberWithdrawProcessor.anonymize(memberId);
    }

    // Access Token만 가진 사람이 비밀번호를 계속 넣어 볼 수 없도록, 로그인과 같은 이메일별 실패 횟수·잠금을 씀
    private void checkPassword(Member member, String password) {
        String email = member.getEmail();
        long attempt = loginAttemptStore.increaseAttempt(email, memberPolicy.loginLockTtl());
        if (attempt > memberPolicy.loginMaxAttempts()) {
            throw new DomainException(MemberErrorCode.LOGIN_LOCKED);
        }
        if (passwordEncoder.matches(password, member.getPassword())) {
            // 본인 확인이 끝났으므로 횟수를 지움 (탈퇴 조건 때문에 거절돼도 시도 횟수가 쌓이지 않게)
            loginAttemptStore.deleteAttempts(email);
            return;
        }

        if (attempt == memberPolicy.loginMaxAttempts()) {
            loginAttemptStore.lock(email, memberPolicy.loginLockTtl());
            throw new DomainException(MemberErrorCode.LOGIN_LOCKED);
        }
        throw new DomainException(MemberErrorCode.PASSWORD_MISMATCH);
    }
}
