package com.backend.gateway;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.io.IOException;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 서비스로 넘기지 못한 요청(연결 거절·컨테이너 없음·연결 끊김)을 502로 답하고, 요청 주소 없이 WARN으로 기록한다.
 * <ul>
 *   <li>그냥 두면 Spring 기본 오류 처리기가 500과 함께 요청 주소 전체를 ERROR로 찍는다.
 *       배포로 서비스가 재시작되는 동안 요청마다 ERROR → Slack 알림(ERROR만 봄)이 쏟아진다. 재시작은 예상된 일이라 WARN으로 둔다.
 *       서비스가 정말 안 뜨면 배포 스크립트가 상태 확인(healthcheck)으로 실패를 알린다</li>
 *   <li>배송 웹훅(/webhooks/delivery-tracker/{토큰})은 주소 끝이 비밀 토큰이라 어떤 오류든 주소 없이 처리한다
 *       (nginx도 이 경로의 접근 로그를 끈다: infra/nginx/default.conf)</li>
 * </ul>
 * 연결 문제가 아닌 오류(코드 버그 등)는 그대로 두어 지금처럼 ERROR로 남게 한다.
 */
@Slf4j
@Component
public class ServiceConnectionFailureFilter implements GlobalFilter, Ordered {

    private static final String WEBHOOK_PATH_PREFIX = "/webhooks/delivery-tracker/";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        boolean webhook = exchange.getRequest().getPath().value().startsWith(WEBHOOK_PATH_PREFIX);
        return chain.filter(exchange).onErrorResume(e -> webhook || e instanceof IOException, e -> {
            Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
            // 예외 메시지는 연결 대상(서비스 주소)만 담고 요청 주소는 담지 않는다. 스택은 남기지 않는다(Reactor가 요청 주소를 붙인다)
            log.warn("서비스에 연결하지 못했습니다 (route={}, {}: {})",
                    route == null ? "-" : route.getId(), e.getClass().getSimpleName(), e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);   // nginx만 있을 때와 같은 응답
            return exchange.getResponse().setComplete();
        });
    }

    // 다른 필터·서비스로 넘기기(NettyRoutingFilter)에서 난 오류까지 받도록 가장 먼저 돈다
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
