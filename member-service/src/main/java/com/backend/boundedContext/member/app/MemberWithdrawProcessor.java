package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.MemberSocialAccountRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;

import lombok.RequiredArgsConstructor;

/**
 * 회원 탈퇴의 DB 변경(비식별화)만 한 트랜잭션으로 처리함
 * 탈퇴 조건 확인(다른 서비스 호출)은 MemberWithdrawUseCase가 이 트랜잭션 밖에서 먼저 함. 같은 클래스 안에서 부르면 트랜잭션이 적용되지 않아 컴포넌트를 나눔
 */
@Component
@RequiredArgsConstructor
public class MemberWithdrawProcessor {

    private final MemberRepository memberRepository;
    private final SellerRepository sellerRepository;
    private final SellerStatusHistoryRepository sellerStatusHistoryRepository;
    private final MemberSocialAccountRepository memberSocialAccountRepository;

    // 회원·판매자 비식별화, 소셜 연결 정보 삭제. 조건 확인 사이에 이미 탈퇴했으면 아무것도 하지 않음
    @Transactional
    public void anonymize(Long memberId) {
        Member member = memberRepository.findById(memberId).filter(Member::isActive).orElse(null);
        if (member == null) return;

        // 엔티티는 조회한 상태라 커밋 때 변경 감지로 저장됨
        sellerRepository.findByMemberId(memberId).ifPresent(seller -> {
            seller.anonymize();
            sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(seller.getId()).forEach(SellerStatusHistory::anonymize);
        });
        // 탈퇴한 회원의 카카오 계정으로는 로그인할 수 없게 하고, 같은 카카오 계정을 새 회원에게 다시 연결할 수 있게 지움
        // (카카오 쪽 연결은 끊지 않음. unlink API 연동은 이번 범위 밖)
        memberSocialAccountRepository.deleteAllByMemberId(memberId);
        member.withdraw();
    }
}
