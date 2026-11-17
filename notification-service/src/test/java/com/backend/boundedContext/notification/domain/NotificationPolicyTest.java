package com.backend.boundedContext.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationPolicyTest {

    @Test
    @DisplayName("보여 주는 기간의 시작은 지금에서 visibleDays일 전")
    void visibleSince() {
        NotificationPolicy policy = new NotificationPolicy(30, 30);

        assertThat(policy.visibleSince(LocalDateTime.of(2026, 11, 11, 12, 0)))
                .isEqualTo(LocalDateTime.of(2026, 10, 12, 12, 0));
    }

    @Test
    @DisplayName("visibleDays가 1보다 작으면 앱 시작을 막음")
    void invalidVisibleDays() {
        assertThatThrownBy(() -> new NotificationPolicy(0, 30)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SSE 연결 유지 시간은 분을 밀리초로 바꿔 씀")
    void sseTimeoutMillis() {
        assertThat(new NotificationPolicy(30, 30).sseTimeoutMillis()).isEqualTo(1_800_000L);
    }

    @Test
    @DisplayName("sseTimeoutMinutes가 1보다 작으면 앱 시작을 막음")
    void invalidSseTimeoutMinutes() {
        assertThatThrownBy(() -> new NotificationPolicy(30, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
