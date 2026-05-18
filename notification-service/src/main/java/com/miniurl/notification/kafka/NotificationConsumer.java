package com.miniurl.notification.kafka;

import com.miniurl.dto.NotificationEvent;
import com.miniurl.notification.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final EmailService emailService;

    @KafkaListener(topics = "notifications", groupId = "notification-group")
    public void consume(NotificationEvent event) {
        log.info("Received notification event: {} for email: {}", event.getEventType(), event.getToEmail());
        try {
            emailService.sendEmail(
                event.getEventType(),
                event.getToEmail(),
                event.getUsername(),
                event.getPayload()
            );
        } catch (Exception e) {
            log.error("Error processing notification event {}: {}", event.getEventType(), e.getMessage());
        }
    }
}
