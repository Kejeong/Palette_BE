package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.out.search.SearchIndexUnavailableException;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.product.event.ProductChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 상품 변경 이벤트(ProductChangedEvent)를 Kafka(product.events)로 받아 검색 색인에 반영한다 (engine=es일 때만).
 * <p>
 * 메시지를 묶음으로 받아 상품 ID를 모은 뒤 한 번에 색인한다. payload는 쓰지 않고 상품 ID로 DB를 다시 읽어 문서 전체를 다시 쓴다
 * (판매 중이 아니면 지운다). 같은 이벤트가 다시 오거나 순서가 바뀌어도 결과는 DB의 지금 상태이므로 processed_event로 거르지 않는다.
 * 같은 토픽의 다른 이벤트(찜 등)는 eventType 헤더로 걸러 payload를 읽지 않는다. 헤더가 없으면 봉투를 읽어 거른다.
 * <p>
 * 실패는 두 가지로 나눈다.
 * <ul>
 *   <li>일시 오류(ES 접속·응답 실패, alias 없음, 이전 인덱스의 거부, DB 연결 실패): 시간이 지나면 성공하므로 예외를 던져
 *       묶음 전체를 성공할 때까지 다시 처리한다(ProductSearchIndexKafkaConfig). 이 그룹의 offset이 "어디까지 색인했는지"를 기억하므로
 *       ES가 돌아오거나 재색인이 끝나면 멈춘 위치부터 수동 재색인 없이 반영된다.</li>
 *   <li>그 밖의 오류(특정 상품 데이터 문제 등): 다시 해도 같으므로 상품 하나씩 다시 색인해 실패한 상품만 ERROR로 남기고 건너뛴다.
 *       한 상품 때문에 다른 상품의 색인이 멈추지 않는다. 건너뛴 상품은 다음 변경이나 관리자 전체 재색인으로 복구한다.</li>
 * </ul>
 * 판매자 리스너(market)·AI 추천과 offset을 따로 둔다.
 */
@Slf4j
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
    public void handle(List<ConsumerRecord<String, String>> records) {
        Set<Long> productIds = new LinkedHashSet<>();
        records.forEach(record -> {
            Long productId = changedProductId(record);
            if (productId != null) productIds.add(productId);
        });
        if (productIds.isEmpty()) return;

        try {
            productFacade.indexProducts(productIds);
        } catch (RuntimeException e) {
            if (isTransient(e)) throw e;   // 묶음 전체를 다시 처리한다
            indexOneByOne(productIds);
        }
    }

    /** 상품 하나씩 색인해 실패한 상품만 건너뛴다. 일시 오류면 묶음 전체를 다시 처리하도록 던진다 */
    private void indexOneByOne(Set<Long> productIds) {
        for (Long productId : productIds) {
            try {
                productFacade.indexProducts(Set.of(productId));
            } catch (RuntimeException e) {
                if (isTransient(e)) throw e;
                log.error("상품 검색 색인에 실패해 이 상품은 건너뜁니다. 다음 변경이나 관리자 전체 재색인으로 복구합니다. productId={}", productId, e);
            }
        }
    }

    /** 상품 변경 이벤트면 상품 ID, 아니면 null. 키나 봉투가 깨진 메시지는 다시 해도 같으므로 ERROR로 남기고 건너뛴다 */
    private Long changedProductId(ConsumerRecord<String, String> record) {
        try {
            if (!HANDLED_EVENT_TYPE.equals(eventType(record))) return null;
            return Long.valueOf(record.key());
        } catch (RuntimeException e) {
            log.error("상품 검색 색인 메시지를 읽지 못해 건너뜁니다. partition={}, offset={}, key={}",
                    record.partition(), record.offset(), record.key(), e);
            return null;
        }
    }

    private String eventType(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (header != null) return new String(header.value(), StandardCharsets.UTF_8);
        return eventMessages.read(record.value()).eventType();   // 콘솔로 다시 보낸 메시지처럼 헤더가 없을 때
    }

    /** 시간이 지나면 같은 요청이 성공하는 오류인지 (원인을 따라가며 본다) */
    static boolean isTransient(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SearchIndexUnavailableException
                    || cause instanceof TransientDataAccessException
                    || cause instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }
}
