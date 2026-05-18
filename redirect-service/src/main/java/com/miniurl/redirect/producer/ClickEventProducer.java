package com.miniurl.redirect.producer;

import com.miniurl.common.dto.ClickEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class ClickEventProducer {

    private final KafkaTemplate<String, ClickEvent> kafkaTemplate;
    private static final String TOPIC = "click-events";

    public ClickEventProducer(KafkaTemplate<String, ClickEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public Mono<Void> sendClickEvent(ClickEvent event) {
        return Mono.fromRunnable(() -> {
            try {
                kafkaTemplate.send(TOPIC, event.getShortCode(), event);
            } catch (Exception e) {
                log.error("Failed to send click event to Kafka for shortCode: {}", event.getShortCode(), e);
            }
        }).then();
    }
}
