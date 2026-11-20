package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.InfluencerRepository;
import com.backend.shared.member.dto.InfluencerStatus;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 회원 상세 중 회원 DB에서 읽는 부분(회원·판매자·인플루언서)만 한 읽기 트랜잭션으로 조회함
 * 지갑·최근 주문(다른 서비스 호출)은 MemberGetMemberDetailUseCase가 이 트랜잭션이 끝난 뒤 조회함. 같은 클래스 안에서 부르면 트랜잭션이 적용되지 않아 컴포넌트를 나눔
 */
@Component
@RequiredArgsConstructor
public class MemberDetailReader {

    private final MemberSupport memberSupport;
    private final MemberGetSellerUseCase memberGetSellerUseCase;
    private final InfluencerRepository influencerRepository;

    // 회원이 없으면 404 MEMBER_NOT_FOUND
    @Transactional(readOnly = true)
    public MemberDetail read(Long memberId) {
        Member member = memberSupport.getMember(memberId);
        SellerStatus sellerStatus = memberGetSellerUseCase.findSellerByMemberId(memberId).map(SellerDto::status).orElse(null);
        InfluencerStatus influencerStatus = influencerRepository.findByMemberId(memberId).map(Influencer::getStatus).orElse(null);
        return new MemberDetail(member, sellerStatus, influencerStatus);
    }

    // 판매자·인플루언서 신청 이력이 없으면 각 상태는 null
    public record MemberDetail(Member member, SellerStatus sellerStatus, InfluencerStatus influencerStatus) {}
}
