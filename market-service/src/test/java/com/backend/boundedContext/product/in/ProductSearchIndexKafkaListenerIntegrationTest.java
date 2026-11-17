package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.search.ProductEsIndexAdapter;
import com.backend.boundedContext.product.out.search.ProductSearchDocument;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

/**
 * 검색 색인 컨슈머가 product.events의 상품 변경 이벤트를 받아 색인하는지, 실패하면 DLT 없이 성공할 때까지 다시 처리하는지 확인한다.
 * Kafka는 EmbeddedKafka, DB는 로컬 Docker PostgreSQL을 쓴다. 검색 엔진을 es로 켜되 ES 대신 색인 어댑터를 mock으로 바꾼다.
 * 릴레이(OutboxKafkaSender)와 같은 형식(키 productId, eventType 헤더)으로 직접 보낸다. 컨슈머는 payload를 읽지 않는다.
 */
@SpringBootTest(properties = {
        "custom.product.search.engine=es",
        "custom.scheduling.enabled=false",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "custom.product.search.indexConsumer.retryInitialIntervalMillis=100",
        "custom.product.search.indexConsumer.retryMaxIntervalMillis=200"
})
@EmbeddedKafka(topics = "product.events", partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class ProductSearchIndexKafkaListenerIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductOptionRepository productOptionRepository;
    @Autowired private OptionStockRepository optionStockRepository;

    /** ES 대신 색인 요청을 기록한다 (검색 어댑터도 이 타입을 받으므로 클래스로 바꾼다) */
    @MockitoBean
    private ProductEsIndexAdapter indexAdapter;

    // 다른 테스트·시드와 겹치지 않는 판매자 ID
    private final long sellerId = ThreadLocalRandom.current().nextLong(5_000_000_000L, 6_000_000_000L);

    private Long productId;
    private Long optionId;

    @BeforeEach
    void setUp() {
        given(indexAdapter.isEnabled()).willReturn(true);
        transactionTemplate.executeWithoutResult(status -> {
            Product product = productRepository.save(Product.create(
                    sellerId, categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow(), "색인 컨슈머 테스트 크림", "설명", 10_000L));
            ProductOption option = productOptionRepository.save(ProductOption.createDefault(product));
            optionStockRepository.save(OptionStock.init(option, 10));
            productId = product.getId();
            optionId = option.getId();
        });
    }

    @AfterEach
    void cleanUp() {
        transactionTemplate.executeWithoutResult(status -> {
            productOptionRepository.deleteById(optionId);
            productRepository.deleteById(productId);
        });
    }

    @Test
    @DisplayName("색인이 공통 재시도 횟수(4번)보다 많이 실패해도(ES 장애) DLT로 보내지 않고 다시 처리해 결국 DB의 지금 값으로 색인한다")
    void retriesUntilIndexed() {
        IllegalStateException esDown = new IllegalStateException("ES 응답 없음");
        doThrow(esDown).doThrow(esDown).doThrow(esDown).doThrow(esDown).doThrow(esDown).doThrow(esDown)   // 6번 실패
                .doNothing()
                .when(indexAdapter).upsert(anyList());

        send(String.valueOf(productId), "ProductChangedEvent");

        await().atMost(WAIT).until(() -> upsertCalls() >= 7);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductSearchDocument>> documents = ArgumentCaptor.forClass(List.class);
        verify(indexAdapter, atLeast(7)).upsert(documents.capture());
        assertThat(documents.getAllValues().getLast())
                .extracting(ProductSearchDocument::productId, ProductSearchDocument::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(productId, "색인 컨슈머 테스트 크림"));
    }

    @Test
    @DisplayName("같은 토픽의 다른 이벤트(찜)는 색인하지 않고, 그 뒤 상품 변경 이벤트는 색인한다")
    void ignoresOtherEventTypes() {
        doNothing().when(indexAdapter).upsert(anyList());

        send(String.valueOf(productId), "WishAddedEvent");
        send(String.valueOf(productId), "ProductChangedEvent");   // 같은 키라 같은 파티션에서 뒤에 처리된다

        await().atMost(WAIT).until(() -> upsertCalls() >= 1);
        assertThat(upsertCalls()).isEqualTo(1);
    }

    private long upsertCalls() {
        return mockingDetails(indexAdapter).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("upsert"))
                .count();
    }

    /** 릴레이와 같은 키·헤더로 보낸다. 검색 색인 컨슈머는 payload를 읽지 않으므로 본문은 비워 둔다 */
    private void send(String key, String eventType) {
        ProducerRecord<String, String> record = new ProducerRecord<>("product.events", key, "{}");
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }
}
