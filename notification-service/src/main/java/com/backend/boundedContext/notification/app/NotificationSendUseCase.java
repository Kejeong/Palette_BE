package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.app.dto.NotificationDto;
import com.backend.boundedContext.notification.out.NotificationEmitters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 연결된 회원 화면으로 알림을 보냄. 연결이 없으면 아무것도 하지 않음
@Service
@RequiredArgsConstructor
public class NotificationSendUseCase {

    private final NotificationEmitters notificationEmitters;
    private final NotificationGetUnreadCountUseCase notificationGetUnreadCountUseCase;

    // 새 알림과 바뀐 안 읽은 개수를 보냄. 연결이 없으면 개수 조회도 하지 않음
    public void sendCreated(Long memberId, NotificationDto notification) {
        if (notificationEmitters.count(memberId) == 0) return;

        notificationEmitters.send(memberId, NotificationEmitters.NOTIFICATION, notification);
        notificationEmitters.send(memberId, NotificationEmitters.UNREAD_COUNT, notificationGetUnreadCountUseCase.getUnreadCount(memberId));
    }
}
