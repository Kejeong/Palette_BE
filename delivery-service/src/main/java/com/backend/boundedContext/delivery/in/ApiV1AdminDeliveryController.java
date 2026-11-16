package com.backend.boundedContext.delivery.in;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.delivery.app.DeliveryFacade;
import com.backend.boundedContext.delivery.in.dto.DeliveryCompleteRequest;
import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.shared.delivery.dto.DeliveryStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 배송 API. 권한(ADMIN만)은 common SecurityConfig가 /api/v1/admin/**에 건다.
 */
@Tag(name = "관리자 배송", description = "배송 목록, 배송 수동 완료 (관리자 전용)")
@RestController
@RequestMapping("/api/v1/admin/deliveries")
@RequiredArgsConstructor
public class ApiV1AdminDeliveryController {

    private final DeliveryFacade deliveryFacade;

    @Operation(summary = "배송 목록", description = "상태별 배송 목록. 수동 완료 대상은 status=SHIPPED로 찾습니다. 응답은 물류 배송 목록과 같습니다.")
    @GetMapping
    public RsData<PageResponse<LogisticsDeliveryResponse>> getDeliveries(
            @RequestParam(defaultValue = "SHIPPED") DeliveryStatus status,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "배송 목록을 조회했습니다.", deliveryFacade.getDeliveries(status, pageable));
    }

    @Operation(summary = "배송 수동 완료",
            description = "택배사 조회로 완료되지 않은 배송 중(SHIPPED) 배송을 관리자가 확인한 배송 완료 시각·사유로 완료(DELIVERED)합니다. "
                    + "택배사 조회 완료와 같은 배송 완료 이벤트가 나가 주문에 배송 완료 시각이 기록됩니다.")
    @PostMapping("/{deliveryId}/complete")
    public RsData<LogisticsDeliveryResponse> complete(@PathVariable Long deliveryId,
                                                      @Valid @RequestBody DeliveryCompleteRequest request) {
        return RsData.of("200-1", "배송을 완료 처리했습니다.",
                deliveryFacade.completeManually(deliveryId, request.deliveredAt(), request.reason()));
    }
}
