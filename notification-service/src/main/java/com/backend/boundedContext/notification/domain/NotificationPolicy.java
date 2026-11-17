package com.backend.boundedContext.notification.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

// 알림 컨텍스트 정책 값 (custom.notification.*)
@Component
@Getter
public class NotificationPolicy {

    // 목록·안 읽은 개수에 보여 주는 기간(일). 이보다 오래된 알림은 지우지 않고 조회에서만 뺌
    private final int visibleDays;

    // SSE 연결 하나를 유지하는 최대 시간(분). 지나면 서버가 연결을 끝내고 프론트가 다시 연결함
    private final int sseTimeoutMinutes;

    public NotificationPolicy(
            @Value("${custom.notification.visibleDays}") int visibleDays,
            @Value("${custom.notification.sseTimeoutMinutes}") int sseTimeoutMinutes
    ) {
        if (visibleDays < 1) throw new IllegalArgumentException("custom.notification.visibleDays는 1 이상이어야 합니다: " + visibleDays);
        if (sseTimeoutMinutes < 1) throw new IllegalArgumentException("custom.notification.sseTimeoutMinutes는 1 이상이어야 합니다: " + sseTimeoutMinutes);
        this.visibleDays = visibleDays;
        this.sseTimeoutMinutes = sseTimeoutMinutes;
    }

    // 이 시각 이후에 생긴 알림만 보여 줌
    public LocalDateTime visibleSince(LocalDateTime now) {
        return now.minusDays(visibleDays);
    }

    public long sseTimeoutMillis() {
        return sseTimeoutMinutes * 60_000L;
    }
}
