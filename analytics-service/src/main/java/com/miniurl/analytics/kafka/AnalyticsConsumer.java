package com.miniurl.analytics.kafka;

import com.miniurl.common.dto.ClickEvent;
import com.miniurl.analytics.repository.ClickEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsConsumer {

    private final ClickEventRepository clickEventRepository;

    @KafkaListener(topics = "click-events", groupId = "analytics-group")
    @Transactional
    public void consume(ClickEvent event) {
        log.info("Received click event for shortCode: {}", event.getShortCode());
        try {
            com.miniurl.analytics.entity.ClickEvent entity = com.miniurl.analytics.entity.ClickEvent.builder()
                    .shortCode(event.getShortCode())
                    .originalUrl(event.getOriginalUrl())
                    .ipAddress(event.getIpAddress())
                    .userAgent(event.getUserAgent())
                    .referer(event.getReferer())
                    .timestamp(event.getTimestamp())
                    .userId(event.getUserId())
                    .build();

            clickEventRepository.save(entity);
        } catch (Exception e) {
            log.error("Error saving click event for {}: {}", event.getShortCode(), e.getMessage());
        }
    }
}
