package com.backend.boundedContext.product.in;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * 검색 색인 컨슈머(ProductSearchIndexKafkaListener) 전용 리스너 팩토리 (engine=es일 때만).
 * <p>
 * 공통 실패 처리(KafkaConsumerConfig: 1·2·4·8초 뒤 DLT)를 쓰면 ES 장애가 15초만 넘어도 DLT로 가서 수동 복구가 다시 필요하다.
 * 색인은 DB를 다시 읽어 쓰는 일이라 몇 번을 다시 해도 결과가 같으므로, 성공할 때까지 같은 메시지를 다시 처리한다
 * (간격은 retryInitialIntervalMillis부터 두 배씩, 최대 retryMaxIntervalMillis. max.poll.interval.ms 5분보다 짧게).
 * 이 그룹의 offset만 멈추므로 다른 컨슈머는 영향이 없다. 다시 해도 같은 오류(키가 상품 ID가 아님)만 재시도 없이 넘긴다.
 * 나머지 설정(ack-mode 등)은 스프링 부트 기본 팩토리와 같다.
 */
@Configuration
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductSearchIndexKafkaConfig {

    static final String LISTENER_FACTORY = "productSearchIndexListenerFactory";

    @Bean(name = LISTENER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> productSearchIndexListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            @Value("${custom.product.search.indexConsumer.retryInitialIntervalMillis}") long retryInitialIntervalMillis,
            @Value("${custom.product.search.indexConsumer.retryMaxIntervalMillis}") long retryMaxIntervalMillis) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);

        ExponentialBackOff backOff = new ExponentialBackOff(retryInitialIntervalMillis, 2.0);
        backOff.setMaxInterval(retryMaxIntervalMillis);   // 최대 경과 시간은 기본값(제한 없음)
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(backOff);
        errorHandler.addNotRetryableExceptions(NumberFormatException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
