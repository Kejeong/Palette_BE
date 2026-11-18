package com.backend.boundedContext.payment.support;

import java.time.Duration;

import com.backend.boundedContext.payment.out.pg.PgCircuitBreakers;
import com.backend.boundedContext.payment.out.pg.PgConfigurationException;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

// 테스트용 서킷 브레이커. application.yaml의 resilience4j.circuitbreaker.configs.pg와 같은 기준(최근 10회 중 50%, 최소 5회, 열림 60초, 반열림 3회)
public final class TestPgCircuitBreakers {

    private TestPgCircuitBreakers() {
    }

    public static PgCircuitBreakers create() {
        return create(Duration.ofSeconds(60));
    }

    // 열림 유지 시간만 바꿈 (HALF_OPEN 전환 확인용)
    public static PgCircuitBreakers create(Duration openWait) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(openWait)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(PgResultUnknownException.class)
                .ignoreExceptions(PgConfigurationException.class)
                .build();
        return new PgCircuitBreakers(CircuitBreakerRegistry.of(config));
    }
}
