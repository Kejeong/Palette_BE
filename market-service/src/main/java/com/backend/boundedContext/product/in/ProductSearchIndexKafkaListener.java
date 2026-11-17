package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.product.event.ProductChangedEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 상품 변경 이벤트(ProductChangedEvent)를 Kafka(product.events)로 받아 검색 색인에 반영한다 (engine=es일 때만).
 * <p>
 * payload는 쓰지 않고 메시지 키(productId)로 DB를 다시 읽어 문서 전체를 다시 쓴다(판매 중이 아니면 지운다).
 * 같은 이벤트가 다시 오거나 순서가 바뀌어도 결과는 DB의 지금 상태이므로 processed_event로 중복을 거르지 않는다.
 * 같은 토픽의 다른 이벤트(찜 등)는 eventType 헤더로 걸러 payload를 읽지 않는다. 헤더가 없으면 봉투를 읽어 거른다.
 * <p>
 * 색인에 실패하면(ES 장애, alias 없음, 매핑 버전이 낮은 이전 인덱스) DLT로 보내지 않고 성공할 때까지 같은 메시지를 다시 처리한다
 * (ProductSearchIndexKafkaConfig). 이 그룹의 offset이 "어디까지 색인했는지"를 기억하므로, ES가 돌아오거나 재색인이 끝나면
 * 멈춘 위치부터 수동 재색인 없이 반영된다. 판매자 리스너(market)·AI 추천과 offset을 따로 둔다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductSearchIndexKafkaListener {

    /** Kafka groupId */
    static final String CONSUMER = "market-search-index";

    /** OutboxKafkaSender가 붙이는 이벤트 타입 헤더 */
    private static final String EVENT_TYPE_HEADER = "eventType";
    private static final String HANDLED_EVENT_TYPE = ProductChangedEvent.class.getSimpleName();

    private final ProductFacade productFacade;
    private final EventMessages eventMessages;

    @KafkaListener(topics = "product.events", groupId = CONSUMER, containerFactory = ProductSearchIndexKafkaConfig.LISTENER_FACTORY)
    public void handle(ConsumerRecord<String, String> record) {
        if (!HANDLED_EVENT_TYPE.equals(eventType(record))) return;
        // 키가 상품 ID가 아니면 NumberFormatException: 다시 해도 같으므로 재시도하지 않고 넘긴다 (전용 팩토리 설정)
        productFacade.indexProducts(Set.of(Long.valueOf(record.key())));
    }

    private String eventType(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (header != null) return new String(header.value(), StandardCharsets.UTF_8);
        return eventMessages.read(record.value()).eventType();   // 콘솔로 다시 보낸 메시지처럼 헤더가 없을 때
    }
}
