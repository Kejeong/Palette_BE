package com.backend.boundedContext.member.app;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.MemberDetailReader.MemberDetail;
import com.backend.boundedContext.member.app.dto.AdminMemberDetailDto;
import com.backend.boundedContext.member.out.MemberOrderPort;
import com.backend.boundedContext.member.out.MemberPaymentPort;
import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.payment.dto.WalletSummaryDto;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberGetMemberDetailUseCase {

    private static final int RECENT_ORDER_LIMIT = 5;    // 관리자 화면에 보여 줄 최근 주문 수

    private final MemberDetailReader memberDetailReader;
    private final MemberPaymentPort memberPaymentPort;
    private final MemberOrderPort memberOrderPort;

    // 관리자 회원 상세: 회원 기본 정보 + 판매자·인플루언서 상태 + 지갑 + 최근 주문
    // 지갑·최근 주문은 화면 표시용이라, 결제·주문을 조회하지 못하면 그 영역만 비우고 회원 정보는 그대로 응답한다
    // 회원 DB 조회만 읽기 트랜잭션에서 하고, 지갑·최근 주문(다른 서비스 HTTP 호출)은 트랜잭션이 끝난 뒤 조회함
    public AdminMemberDetailDto getMemberDetail(Long memberId) {
        MemberDetail detail = memberDetailReader.read(memberId);
        Optional<WalletSummaryDto> wallet = DisplayPortQueries.orNull(() -> memberPaymentPort.findWallet(memberId));   // 결제를 조회하지 못하면 null
        List<OrderSummaryDto> recentOrders = DisplayPortQueries.orNull(() -> memberOrderPort.getRecentOrders(memberId, RECENT_ORDER_LIMIT));

        return AdminMemberDetailDto.of(detail.member(), detail.sellerStatus(), detail.influencerStatus(), wallet, recentOrders);
    }
}
