package com.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 서비스가 꺼져 있을 때: 502로 답하고, 요청 주소 없이 WARN으로 남는지(ERROR 알림이 안 가는지) 확인한다.
 * delivery·market 주소를 아무도 듣지 않는 포트(1)로 두어 연결 실패를 만든다.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"DELIVERY_SERVICE_URL=http://localhost:1", "MARKET_SERVICE_URL=http://localhost:1"})
class ServiceConnectionFailureFilterTest {

    private static final String WEBHOOK_TOKEN = "secret-webhook-token-7f3a9c";

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void 배송_웹훅을_못_넘기면_502이고_로그에_토큰이_없다(CapturedOutput output) {
        client().post().uri("/webhooks/delivery-tracker/" + WEBHOOK_TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);

        assertThat(output).contains("서비스에 연결하지 못했습니다 (route=delivery");
        assertThat(output).doesNotContain(WEBHOOK_TOKEN);
    }

    @Test
    void 서비스가_꺼져_있으면_502이고_주소가_든_ERROR를_남기지_않는다(CapturedOutput output) {
        client().get().uri("/api/v1/products")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);

        assertThat(output).contains("WARN").contains("서비스에 연결하지 못했습니다 (route=market");
        // Spring 기본 오류 처리기의 로그("500 Server Error for HTTP GET \"/api/v1/products\"")가 없어야 Slack ERROR 알림이 안 간다
        assertThat(output).doesNotContain("Server Error for HTTP");
    }
}
