package com.backend.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 배송 웹훅(/webhooks/delivery-tracker/{토큰})을 delivery로 넘기지 못했을 때(연결 실패·시간 초과) 오류를 여기서 받아
 * 주소 없이 기록하고 502로 답한다.
 * 그냥 두면 Spring 기본 오류 처리기가 요청 주소 전체를 ERROR로 찍어, 주소 끝의 비밀 토큰이 Loki·Slack 알림에 남는다.
 * nginx는 이 경로의 접근 로그를 끈다(infra/nginx/default.conf).
 */
@Slf4j
@Component
public class HideWebhookTokenOnErrorFilter implements GlobalFilter, Ordered {

    private static final String WEBHOOK_PATH_PREFIX = "/webhooks/delivery-tracker/";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith(WEBHOOK_PATH_PREFIX)) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange).onErrorResume(e -> {
            // 예외 메시지는 연결 대상(delivery 주소)만 담고 요청 주소는 담지 않는다. 스택은 남기지 않는다(Reactor가 요청 주소를 붙인다)
            log.error("배송 웹훅을 delivery로 넘기지 못했습니다 ({}: {})", e.getClass().getSimpleName(), e.getMessage());
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
