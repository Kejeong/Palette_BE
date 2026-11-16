package com.backend.shared.product.event;

import com.backend.shared.event.DomainEvent;

/**
 * 상품의 내용이나 판매 상태가 바뀜(등록·수정·삭제·판매 중지·중지 해제). AI 추천이 받아 추천용 상품 사본·성분 태그·임베딩을 만든다.
 * 바뀐 부분이 아니라 상품의 지금 상태 전체를 담는다. 재고·옵션·할인 변경은 발행하지 않는다.
 *
 * @param categoryName    소분류 이름 (예: 스킨케어)
 * @param beauty          뷰티 대분류의 상품인지
 * @param ingredientsText 전성분 원문. 뷰티가 아니거나 판매자가 아직 넣지 않았으면 null
 * @param price           정가 (할인 반영 안 함)
 * @param thumbnailUrl    대표 이미지 URL. 없으면 null
 * @param onSale          구매자에게 보이는 상품인지 (판매 중이고 카테고리 사용 중). 삭제·판매 중지면 false
 */
public record ProductChangedEvent(
        Long productId,
        String name,
        String description,
        String categoryName,
        boolean beauty,
        String ingredientsText,
        long price,
        String thumbnailUrl,
        boolean onSale
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "PRODUCT";   // 토픽 product.events (찜 이벤트와 같은 토픽, 받는 쪽은 eventType 헤더로 거른다)
    }

    @Override
    public Long aggregateId() {
        return productId;   // 같은 상품의 이벤트는 같은 파티션으로 가서 순서대로 전달된다
    }
}
