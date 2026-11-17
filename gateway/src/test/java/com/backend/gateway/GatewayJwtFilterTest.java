package com.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * gateway JWT 검사. 서비스 7개 대신 가짜 서비스 서버 하나로 모든 경로를 받아,
 * 통과한 요청은 서비스까지 가고 막힌 요청은 서비스에 닿지 않는지 확인한다.
 */
@SpringBootTest
class GatewayJwtFilterTest {

    // 가짜 서비스: 받은 요청 수와 Authorization 헤더를 기록하고 200을 돌려준다
    private static final AtomicInteger serviceHits = new AtomicInteger();
    private static final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private static final DisposableServer fakeService = HttpServer.create()
            .port(0)
            .handle((request, response) -> {
                serviceHits.incrementAndGet();
                receivedAuthorization.set(request.requestHeaders().get(HttpHeaders.AUTHORIZATION));
                return response.sendString(Mono.just("ok"));
            })
            .bindNow();

    @DynamicPropertySource
    static void routeAllServicesToFakeService(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + fakeService.port();
        for (String name : new String[]{"MEMBER", "MARKET", "PAYMENT", "SETTLEMENT", "DELIVERY", "NOTIFICATION", "AI"}) {
            registry.add(name + "_SERVICE_URL", () -> url);
        }
    }

    @AfterAll
    static void stopFakeService() {
        fakeService.disposeNow();
    }

    @Autowired
    private ApplicationContext context;

    @Value("${custom.jwt.secret}")
    private String secret;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        serviceHits.set(0);
        receivedAuthorization.set(null);
    }

    @Test
    void 공개_경로는_토큰_없이_서비스로_넘긴다() {
        client.get().uri("/api/v1/products").exchange().expectStatus().isOk();
        client.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();
        client.post().uri("/webhooks/delivery-tracker/secret-token").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/influencers/3").exchange().expectStatus().isOk();

        assertThat(serviceHits).hasValue(4);
    }

    @Test
    void 공개_경로는_만료된_토큰이어도_서비스로_넘긴다() {
        client.get().uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken()))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void 로그인_필요_경로에_토큰이_없으면_401이고_서비스에_닿지_않는다() {
        expectUnauthorized(client.get().uri("/api/v1/orders").exchange(), "401-UNAUTHORIZED");
        // 공개는 GET만이라 같은 경로라도 POST는 로그인 필요
        expectUnauthorized(client.post().uri("/api/v1/products").exchange(), "401-UNAUTHORIZED");
        // 인플루언서 프로필(/influencers/*)은 공개지만 내 신청 정보(/influencers/me)는 로그인 필요
        expectUnauthorized(client.get().uri("/api/v1/influencers/me").exchange(), "401-UNAUTHORIZED");

        assertThat(serviceHits).hasValue(0);
    }

    @Test
    void 가짜_토큰이면_401_UNAUTHORIZED다() {
        SecretKey otherKey = Keys.hmacShaKeyFor("another-secret-key-another-secret-key!!".getBytes());
        String forged = Jwts.builder().subject("1").claim("role", "ADMIN").signWith(otherKey).compact();

        expectUnauthorized(client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(forged)).exchange(),
                "401-UNAUTHORIZED");
        expectUnauthorized(client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt").exchange(),
                "401-UNAUTHORIZED");
        assertThat(serviceHits).hasValue(0);
    }

    @Test
    void 만료된_토큰이면_401_AUTH_TOKEN_EXPIRED다() {
        expectUnauthorized(client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken())).exchange(),
                "401-AUTH_TOKEN_EXPIRED");
        assertThat(serviceHits).hasValue(0);
    }

    @Test
    void 정상_토큰이면_토큰을_그대로_서비스로_넘긴다() {
        String token = Jwts.builder()
                .subject("1").claim("role", "USER")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key())
                .compact();

        client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .exchange()
                .expectStatus().isOk();

        assertThat(receivedAuthorization).hasValue(bearer(token));
    }

    @Test
    void CORS_확인_요청은_토큰_없이_넘긴다() {
        client.method(HttpMethod.OPTIONS).uri("/api/v1/orders").exchange().expectStatus().isOk();
        assertThat(serviceHits).hasValue(1);
    }

    private void expectUnauthorized(WebTestClient.ResponseSpec response, String resultCode) {
        response.expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.resultCode").isEqualTo(resultCode)
                .jsonPath("$.msg").isNotEmpty();
    }

    private String expiredToken() {
        return Jwts.builder()
                .subject("1").claim("role", "USER")
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(key())
                .compact();
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
