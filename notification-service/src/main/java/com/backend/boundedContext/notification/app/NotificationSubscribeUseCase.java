package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.domain.NotificationPolicy;
import com.backend.boundedContext.notification.out.NotificationEmitters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class NotificationSubscribeUseCase {

    private final NotificationEmitters notificationEmitters;
    private final NotificationGetUnreadCountUseCase notificationGetUnreadCountUseCase;
    private final NotificationPolicy notificationPolicy;

    // SSE 연결을 등록하고 지금 안 읽은 개수를 바로 보냄. 다시 연결할 때도 뱃지를 맞추기 위함
    // 같은 회원의 다른 탭에도 같은 개수가 가지만 값이 같아 문제없음
    public SseEmitter subscribe(Long memberId) {
        SseEmitter emitter = new SseEmitter(notificationPolicy.sseTimeoutMillis());
        notificationEmitters.add(memberId, emitter);
        notificationEmitters.send(memberId, NotificationEmitters.UNREAD_COUNT, notificationGetUnreadCountUseCase.getUnreadCount(memberId));
        return emitter;
    }
}
