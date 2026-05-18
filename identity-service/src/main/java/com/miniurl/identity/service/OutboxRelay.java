package com.miniurl.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniurl.identity.entity.Outbox;
import com.miniurl.identity.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxRelay {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long KAFKA_ACK_TIMEOUT_SECONDS = 10;

    public OutboxRelay(OutboxRepository outboxRepository,
                       KafkaTemplate<String, Object> kafkaTemplate,
                       ObjectMapper objectMapper,
                       MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;

        // Gauge: number of unprocessed outbox events
        Gauge.builder("outbox_events_unprocessed", outboxRepository::countByProcessedFalse)
                .description("Number of outbox events that have not yet been processed")
                .tag("service", "identity-service")
                .register(meterRegistry);

        // Gauge: age in seconds of the oldest unprocessed outbox event
        Gauge.builder("outbox_events_age_seconds", () -> {
                    LocalDateTime oldest = outboxRepository.findOldestUnprocessedCreatedAt();
                    if (oldest == null) {
                        return 0.0;
                    }
                    return (double) Duration.between(oldest, LocalDateTime.now()).getSeconds();
                })
                .description("Age in seconds of the oldest unprocessed outbox event")
                .tag("service", "identity-service")
                .register(meterRegistry);
    }

    /**
     * Processes pending outbox events every 5 seconds.
     * Events are marked as processed ONLY after receiving Kafka acknowledgment.
     * This prevents event loss if the process crashes between Kafka send and DB update.
     *
     * Each event is processed in its own transaction to ensure:
     * - A failed event does not roll back successfully published events
     * - The processed flag is set atomically with the Kafka acknowledgment
     */
    @Scheduled(fixedDelay = 5000)
    public void processOutbox() {
        List<Outbox> pendingEvents = outboxRepository.findByProcessedFalseOrderByCreatedAtAsc();

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Processing {} pending outbox events", pendingEvents.size());

        for (Outbox event : pendingEvents) {
            processEventWithRetry(event);
        }
    }

    /**
     * Processes a single outbox event with retry logic.
     * Marks the event as processed ONLY after Kafka confirms receipt.
     * Retries up to MAX_RETRY_ATTEMPTS times with the Kafka acknowledgment timeout.
     */
    @Transactional
    void processEventWithRetry(Outbox event) {
        int attempt = 0;
        while (attempt < MAX_RETRY_ATTEMPTS) {
            attempt++;
            try {
                String topic = determineTopic(event.getAggregateType());
                Object payload = objectMapper.readValue(event.getPayload(), Object.class);

                CompletableFuture<SendResult<String, Object>> future =
                        kafkaTemplate.send(topic, event.getAggregateId(), payload);

                // Block with timeout waiting for Kafka acknowledgment
                SendResult<String, Object> result = future.get(KAFKA_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Kafka acknowledged — now safe to mark as processed
                event.setProcessed(true);
                event.setProcessedAt(LocalDateTime.now());
                outboxRepository.save(event);

                log.debug("Published event {} to topic {} (offset={}, partition={})",
                        event.getType(), topic,
                        result.getRecordMetadata().offset(),
                        result.getRecordMetadata().partition());
                return; // Success — exit retry loop

            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("Kafka ack timeout for event {} (attempt {}/{}): {}",
                        event.getId(), attempt, MAX_RETRY_ATTEMPTS, e.getMessage());
            } catch (Exception e) {
                log.error("Failed to publish event {} (attempt {}/{}): {}",
                        event.getId(), attempt, MAX_RETRY_ATTEMPTS, e.getMessage());
            }
        }

        // All retries exhausted — leave event unprocessed for next cycle
        log.error("Exhausted all {} retry attempts for event {}. Will retry on next cycle.",
                MAX_RETRY_ATTEMPTS, event.getId());
    }

    private String determineTopic(String aggregateType) {
        return switch (aggregateType) {
            case "USER" -> "notifications";
            default -> "general-events";
        };
    }
}
