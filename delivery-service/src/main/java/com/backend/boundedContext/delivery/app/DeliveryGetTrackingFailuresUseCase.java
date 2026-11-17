package com.backend.boundedContext.delivery.app;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.domain.DeliveryItem;
import com.backend.boundedContext.delivery.domain.DeliveryPolicy;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.admin.dto.AdminFailureItem;
import com.backend.shared.admin.dto.AdminFailureTargetType;
import com.backend.shared.admin.dto.AdminFailureType;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 실패 거래 화면(A7)의 배송 행: 송장 등록 후 trackingFailureAfterDays일(기본 15일)이 지나도 배송 완료(DELIVERED)가 되지 않은 배송
 * 공통 형식(AdminFailureItem)으로 돌려준다. 결제, 충전, 환불, 정산도 같은 형식으로 각자 돌려주고 화면이 합친다
 *
 * occurredAt = 송장 등록 시각(shippedAt), 기간은 그 날짜로 거른다(from, to 포함, 한국 시간)
 * 대상 판매자와 상호명은 배송 항목의 결제 시점 스냅샷(sellerId, sellerName)을 쓴다. 회원 서비스는 부르지 않는다
 * 주문서 1개에 판매자가 여럿이면(통합 출고) 첫 항목의 판매자를 대상으로 하고 상호명 뒤에 "외 N곳"을 붙인다
 */
@Component
@RequiredArgsConstructor
public class DeliveryGetTrackingFailuresUseCase {

    static final String REASON_STALLED = "STALLED_AFTER_SHIPPED";
    static final String HANDLING_CODE = "LOGISTICS_CHECK";
    static final String HANDLING_LABEL = "물류 담당자 확인";
    private static final LocalDateTime EARLIEST = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final LocalDateTime LATEST = LocalDateTime.of(9999, 1, 1, 0, 0);
    // 요청의 sort는 무시하고 최신순으로만 준다(다른 서비스 결과와 합쳐 정렬하기 쉽게)
    private static final Sort LATEST_SHIPPED_FIRST = Sort.by(Sort.Order.desc("shippedAt"), Sort.Order.desc("id"));

    private final DeliveryRepository deliveryRepository;
    private final DeliveryPolicy deliveryPolicy;

    public PageResponse<AdminFailureItem> getTrackingFailures(LocalDate from, LocalDate to, Pageable pageable,
                                                              LocalDateTime now) {
        int days = deliveryPolicy.getTrackingFailureAfterDays();
        LocalDateTime fromAt = from == null ? EARLIEST : from.atStartOfDay();
        LocalDateTime toAt = to == null ? LATEST : to.plusDays(1).atStartOfDay();
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST_SHIPPED_FIRST);

        // 정확히 days일 전에 등록한 송장도 포함한다
        Page<Delivery> page = deliveryRepository.findStalledShipped(now.minusDays(days), fromAt, toAt, latestFirst);
        return PageResponse.from(page, delivery -> toItem(delivery, days));
    }

    static AdminFailureItem toItem(Delivery delivery, int days) {
        List<DeliveryItem> items = delivery.getItems();
        DeliveryItem first = items.stream().min(Comparator.comparing(DeliveryItem::getId)).orElse(null);
        return new AdminFailureItem(
                AdminFailureType.DELIVERY_TRACKING_FAILED,
                delivery.getShippedAt(),
                AdminFailureTargetType.SELLER,
                first == null ? null : first.getSellerId(),
                first == null ? null : sellerLabel(first, items),
                null,
                REASON_STALLED,
                reasonLabel(delivery, days),
                HANDLING_CODE,
                HANDLING_LABEL,
                delivery.getId(),
                delivery.getOrderId());
    }

    // 상호명. 다른 판매자가 더 있으면 "팔레트상점 외 1곳"
    private static String sellerLabel(DeliveryItem first, List<DeliveryItem> items) {
        long others = items.stream().map(DeliveryItem::getSellerId).distinct().count() - 1;
        return others > 0 ? "%s 외 %d곳".formatted(first.getSellerName(), others) : first.getSellerName();
    }

    /**
     * 원인 칸: "송장 등록 후 15일 지남". 택배사에서 받은 마지막 상태가 있으면 붙인다(예: "(택배사 상태: EXCEPTION)")
     * 상태가 없으면 송장을 한 번도 조회하지 못했거나 택배사에 없는 송장이라 물류 담당자가 송장부터 확인한다
     */
    private static String reasonLabel(Delivery delivery, int days) {
        String label = "송장 등록 후 %d일 지남".formatted(days);
        String status = delivery.getLastTrackingStatus();
        return status == null ? label : "%s (택배사 상태: %s)".formatted(label, status);
    }
}
