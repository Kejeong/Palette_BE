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
 * delivery가 꺼져 있을 때 배송 웹훅의 비밀 토큰이 로그에 남지 않는지 확인한다.
 * delivery 주소를 아무도 듣지 않는 포트(1)로 두어 연결 실패를 만든다.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "DELIVERY_SERVICE_URL=http://localhost:1")
class HideWebhookTokenOnErrorFilterTest {

    private static final String WEBHOOK_TOKEN = "secret-webhook-token-7f3a9c";

    @Value("${local.server.port}")
    private int port;

    @Test
    void delivery로_못_넘기면_502이고_로그에_토큰이_없다(CapturedOutput output) {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/webhooks/delivery-tracker/" + WEBHOOK_TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);

        assertThat(output).contains("배송 웹훅을 delivery로 넘기지 못했습니다");
        assertThat(output).doesNotContain(WEBHOOK_TOKEN);
    }
}
