package com.backend.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import reactor.core.publisher.Mono;

/**
 * 로그인이 필요한 경로에서 Access Token(Authorization: Bearer)을 먼저 검사해, 없거나 잘못된 토큰은 서비스로 넘기지 않고 401로 막는다.
 * 공개 경로는 토큰이 없거나 만료돼도 그대로 넘긴다(서비스가 비회원으로 처리). 서비스와 같은 동작이다.
 * 역할(관리자·판매자·물류) 검사와 회원 정보 꺼내기는 지금처럼 서비스(common JwtTokenFilter)가 한다. 토큰은 그대로 넘긴다.
 * 경로 규칙에 맞는 요청에만 돈다. 규칙에 없는 경로(/internal/** 등)는 이 필터 전에 404다.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    // 공개 경로. common SecurityConfig의 SWAGGER_URLS·PUBLIC_*_API_URLS와 같아야 한다. 한쪽을 고치면 다른 쪽도 같이 고친다
    private static final List<PathPattern> PUBLIC_PATHS = patterns(
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/error"
    );
    private static final List<PathPattern> PUBLIC_GET_PATHS = patterns(
            "/api/v1/ai/recommendations",
            "/api/v1/products/**",
            "/api/v1/categories/**",
            "/api/v1/influencers/*",
            "/images/**"
    );
    private static final List<PathPattern> PUBLIC_POST_PATHS = patterns(
            "/api/v1/auth/email/**",
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/kakao",
            "/api/v1/auth/refresh",
            "/webhooks/delivery-tracker/**",
            "/api/v1/topups/hecto/**"
    );
    // 인플루언서 프로필(/api/v1/influencers/*)은 공개지만 내 신청 정보는 로그인 필요. 공개 규칙보다 먼저 본다
    private static final PathPattern INFLUENCER_ME_PATH = PathPatternParser.defaultInstance.parse("/api/v1/influencers/me");

    // 응답 본문은 서비스(common GlobalErrorCode + RsData)와 같은 모양이다. 프론트가 resultCode로 토큰 재발급을 고른다
    private static final byte[] UNAUTHORIZED_BODY =
            "{\"resultCode\":\"401-UNAUTHORIZED\",\"msg\":\"로그인이 필요합니다.\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] TOKEN_EXPIRED_BODY =
            "{\"resultCode\":\"401-AUTH_TOKEN_EXPIRED\",\"msg\":\"로그인이 만료되었습니다. 다시 로그인해 주세요.\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_REQUEST_BODY =
            "{\"resultCode\":\"400-INVALID_REQUEST\",\"msg\":\"요청 형식이 올바르지 않습니다.\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);

    // 서비스(Tomcat)가 풀어서 다른 경로로 바꾸는 주소 조각. 디코딩 전 원문에서 찾는다(소문자로 바꿔 %2E·%2F도 잡는다)
    // 예: /api/v1/products/../orders는 gateway에선 공개 경로(products/**)로 보이지만 서비스에선 /api/v1/orders가 된다
    private static final List<String> PATH_TRAVERSAL_PARTS = List.of("/../", "/./", "//", "\\", "%2e", "%2f", "%5c");

    private final SecretKey key;

    public JwtAuthenticationFilter(@Value("${custom.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(decodeSecret(secret));
    }

    // common JwtTokenProvider와 같은 검사. 키가 잘못 들어오면 시작할 때 바로 실패시킨다
    private static byte[] decodeSecret(String secret) {
        byte[] secretBytes;
        try {
            secretBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("custom.jwt.secret(JWT_SECRET)은 Base64 형식이어야 합니다. openssl rand -base64 32로 만드세요.", e);
        }
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "custom.jwt.secret(JWT_SECRET)은 디코딩 후 32바이트 이상이어야 합니다. .env에 JWT_SECRET= 처럼 빈 값이 있는지 확인하세요.");
        }
        return secretBytes;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // 공개 경로 판단을 속이는 주소는 서비스로 넘기지 않는다. 정상 화면·API는 이런 주소를 쓰지 않는다
        if (hasPathTraversal(request.getPath().value())) {
            return reject(exchange.getResponse(), HttpStatus.BAD_REQUEST, INVALID_REQUEST_BODY);
        }
        if (isPublic(request)) {
            return chain.filter(exchange);
        }

        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return reject(exchange.getResponse(), HttpStatus.UNAUTHORIZED, UNAUTHORIZED_BODY);
        }
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(header.substring(BEARER_PREFIX.length()));
        } catch (ExpiredJwtException e) {
            return reject(exchange.getResponse(), HttpStatus.UNAUTHORIZED, TOKEN_EXPIRED_BODY);
        } catch (JwtException | IllegalArgumentException e) {
            return reject(exchange.getResponse(), HttpStatus.UNAUTHORIZED, UNAUTHORIZED_BODY);
        }
        return chain.filter(exchange);
    }

    private static boolean isPublic(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        // 브라우저가 본 요청 전에 보내는 CORS 확인 요청은 토큰을 싣지 않는다. 서비스의 CORS 설정이 답한다
        if (HttpMethod.OPTIONS.equals(method)) {
            return true;
        }
        PathContainer path = request.getPath().pathWithinApplication();
        if (matchesAny(PUBLIC_PATHS, path)) {
            return true;
        }
        if (HttpMethod.GET.equals(method)) {
            return !INFLUENCER_ME_PATH.matches(path) && matchesAny(PUBLIC_GET_PATHS, path);
        }
        return HttpMethod.POST.equals(method) && matchesAny(PUBLIC_POST_PATHS, path);
    }

    private static boolean hasPathTraversal(String rawPath) {
        String path = rawPath.toLowerCase(Locale.ROOT);
        return path.endsWith("/..") || path.endsWith("/.") || PATH_TRAVERSAL_PARTS.stream().anyMatch(path::contains);
    }

    private static Mono<Void> reject(ServerHttpResponse response, HttpStatus status, byte[] body) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private static boolean matchesAny(List<PathPattern> patterns, PathContainer path) {
        return patterns.stream().anyMatch(pattern -> pattern.matches(path));
    }

    private static List<PathPattern> patterns(String... paths) {
        return Stream.of(paths).map(PathPatternParser.defaultInstance::parse).toList();
    }

    // 경로 규칙을 고른 뒤, 서비스로 넘기기(NettyRoutingFilter) 전에 돈다
    @Override
    public int getOrder() {
        return -1;
    }
}
