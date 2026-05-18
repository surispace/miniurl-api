package com.miniurl.url;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniurl.url.entity.Outbox;
import com.miniurl.url.repository.OutboxRepository;
import com.miniurl.url.service.OutboxRelay;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxRelay Tests — P0-2 Fix Verification")
class OutboxRelayTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private ObjectMapper objectMapper;

    private MeterRegistry meterRegistry;
    private OutboxRelay outboxRelay;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        outboxRelay = new OutboxRelay(outboxRepository, kafkaTemplate, objectMapper, meterRegistry);
    }

    // -----------------------------------------------------------------------
    // Successful Kafka ack marks event processed
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("successful Kafka ack marks event as processed")
    void successfulKafkaAckMarksEventProcessed() throws Exception {
        Outbox event = createOutboxEvent(1L, "URL", "123", "URL_CREATED", "{\"url\":\"https://example.com\"}");

        when(objectMapper.readValue(event.getPayload(), Object.class)).thenReturn("{\"url\":\"https://example.com\"}");

        @SuppressWarnings("unchecked")
        SendResult<String, Object> sendResult = mock(SendResult.class);
        org.apache.kafka.clients.producer.RecordMetadata recordMetadata =
                mock(org.apache.kafka.clients.producer.RecordMetadata.class);
        when(recordMetadata.offset()).thenReturn(42L);
        when(recordMetadata.partition()).thenReturn(0);
        when(sendResult.getRecordMetadata()).thenReturn(recordMetadata);

        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(eq("url-events"), eq("123"), any())).thenReturn(future);

        outboxRelay.processEventWithRetry(event);

        assertTrue(event.isProcessed(), "Event should be marked as processed after successful Kafka ack");
        assertNotNull(event.getProcessedAt(), "Processed timestamp should be set");
        verify(outboxRepository).save(event);
    }

    // -----------------------------------------------------------------------
    // Kafka send failure leaves event unprocessed
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Kafka send failure leaves event unprocessed")
    void kafkaSendFailureLeavesEventUnprocessed() throws Exception {
        Outbox event = createOutboxEvent(2L, "URL", "456", "URL_CREATED", "{\"url\":\"https://fail.com\"}");

        when(objectMapper.readValue(event.getPayload(), Object.class)).thenReturn("{\"url\":\"https://fail.com\"}");

        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker unavailable"));
        when(kafkaTemplate.send(eq("url-events"), eq("456"), any())).thenReturn(failedFuture);

        outboxRelay.processEventWithRetry(event);

        assertFalse(event.isProcessed(), "Event should NOT be marked as processed on Kafka failure");
        assertNull(event.getProcessedAt(), "Processed timestamp should NOT be set");
        verify(outboxRepository, never()).save(event);
    }

    // -----------------------------------------------------------------------
    // Kafka ack timeout leaves event unprocessed
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Kafka ack timeout leaves event unprocessed")
    void kafkaAckTimeoutLeavesEventUnprocessed() throws Exception {
        Outbox event = createOutboxEvent(3L, "URL", "789", "URL_DELETED", "{\"url\":\"https://timeout.com\"}");

        when(objectMapper.readValue(event.getPayload(), Object.class)).thenReturn("{\"url\":\"https://timeout.com\"}");

        // Create a future that will never complete (simulating timeout)
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, Object>> neverCompletingFuture = new CompletableFuture<>();
        when(kafkaTemplate.send(eq("url-events"), eq("789"), any())).thenReturn(neverCompletingFuture);

        // processEventWithRetry will block on future.get(10, SECONDS) — we need to
        // complete it exceptionally with TimeoutException to simulate the timeout.
        new Thread(() -> {
            try {
                Thread.sleep(100);
                neverCompletingFuture.completeExceptionally(
                        new TimeoutException("Simulated Kafka ack timeout"));
            } catch (InterruptedException ignored) {
            }
        }).start();

        outboxRelay.processEventWithRetry(event);

        assertFalse(event.isProcessed(), "Event should NOT be marked as processed on Kafka timeout");
        assertNull(event.getProcessedAt(), "Processed timestamp should NOT be set");
        verify(outboxRepository, never()).save(event);
    }

    // -----------------------------------------------------------------------
    // Malformed payload leaves event unprocessed
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("malformed payload leaves event unprocessed")
    void malformedPayloadLeavesEventUnprocessed() throws Exception {
        Outbox event = createOutboxEvent(4L, "URL", "101", "URL_CREATED", "{invalid json");

        when(objectMapper.readValue(event.getPayload(), Object.class))
                .thenThrow(new JsonParseException(null, "Malformed JSON"));

        outboxRelay.processEventWithRetry(event);

        assertFalse(event.isProcessed(), "Event should NOT be marked as processed for malformed payload");
        assertNull(event.getProcessedAt(), "Processed timestamp should NOT be set");
        verify(outboxRepository, never()).save(event);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    // -----------------------------------------------------------------------
    // Repository save is not called as processed on failure
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("repository save is not called with processed=true on failure")
    void repositorySaveNotCalledOnFailure() throws Exception {
        Outbox event = createOutboxEvent(5L, "URL", "202", "URL_CREATED", "{\"url\":\"https://nope.com\"}");

        when(objectMapper.readValue(event.getPayload(), Object.class)).thenReturn("{\"url\":\"https://nope.com\"}");

        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker unavailable"));
        when(kafkaTemplate.send(eq("url-events"), eq("202"), any())).thenReturn(failedFuture);

        outboxRelay.processEventWithRetry(event);

        // Verify save was never called
        verify(outboxRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // processOutbox with empty list does nothing
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("processOutbox with empty pending events does nothing")
    void processOutboxWithEmptyListDoesNothing() {
        when(outboxRepository.findByProcessedFalseOrderByCreatedAtAsc())
                .thenReturn(Collections.emptyList());

        outboxRelay.processOutbox();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(outboxRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // processOutbox with multiple events processes each independently
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("processOutbox processes each event independently")
    void processOutboxProcessesEachEventIndependently() throws Exception {
        Outbox event1 = createOutboxEvent(10L, "URL", "111", "URL_CREATED", "{\"url\":\"https://one.com\"}");
        Outbox event2 = createOutboxEvent(11L, "URL", "222", "URL_DELETED", "{\"url\":\"https://two.com\"}");

        when(outboxRepository.findByProcessedFalseOrderByCreatedAtAsc())
                .thenReturn(List.of(event1, event2));

        when(objectMapper.readValue(event1.getPayload(), Object.class)).thenReturn("{\"url\":\"https://one.com\"}");
        when(objectMapper.readValue(event2.getPayload(), Object.class)).thenReturn("{\"url\":\"https://two.com\"}");

        // Event 1: succeeds
        @SuppressWarnings("unchecked")
        SendResult<String, Object> sendResult1 = mock(SendResult.class);
        org.apache.kafka.clients.producer.RecordMetadata recordMetadata1 =
                mock(org.apache.kafka.clients.producer.RecordMetadata.class);
        when(recordMetadata1.offset()).thenReturn(100L);
        when(recordMetadata1.partition()).thenReturn(0);
        when(sendResult1.getRecordMetadata()).thenReturn(recordMetadata1);
        CompletableFuture<SendResult<String, Object>> future1 = CompletableFuture.completedFuture(sendResult1);
        when(kafkaTemplate.send(eq("url-events"), eq("111"), any())).thenReturn(future1);

        // Event 2: fails
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, Object>> future2 = new CompletableFuture<>();
        future2.completeExceptionally(new RuntimeException("Kafka unavailable"));
        when(kafkaTemplate.send(eq("url-events"), eq("222"), any())).thenReturn(future2);

        outboxRelay.processOutbox();

        // Event 1 should be processed
        assertTrue(event1.isProcessed(), "Event 1 should be marked as processed");
        // Event 2 should NOT be processed
        assertFalse(event2.isProcessed(), "Event 2 should NOT be marked as processed");

        verify(outboxRepository).save(event1);
        verify(outboxRepository, never()).save(event2);
    }

    // -----------------------------------------------------------------------
    // Custom Metrics: outbox_events_unprocessed and outbox_events_age_seconds
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("outbox_events_unprocessed gauge reflects repository count")
    void outboxEventsUnprocessedGaugeReflectsRepositoryCount() {
        when(outboxRepository.countByProcessedFalse()).thenReturn(5L);

        double value = meterRegistry.get("outbox_events_unprocessed")
                .tag("service", "url-service")
                .gauge().value();
        assertEquals(5.0, value, "Gauge should reflect countByProcessedFalse()");
    }

    @Test
    @DisplayName("outbox_events_unprocessed gauge returns 0 when no pending events")
    void outboxEventsUnprocessedGaugeReturnsZero() {
        when(outboxRepository.countByProcessedFalse()).thenReturn(0L);

        double value = meterRegistry.get("outbox_events_unprocessed")
                .tag("service", "url-service")
                .gauge().value();
        assertEquals(0.0, value, "Gauge should return 0 when no pending events");
    }

    @Test
    @DisplayName("outbox_events_age_seconds gauge returns 0 when no unprocessed events")
    void outboxEventsAgeSecondsGaugeReturnsZeroWhenEmpty() {
        when(outboxRepository.findOldestUnprocessedCreatedAt()).thenReturn(null);

        double value = meterRegistry.get("outbox_events_age_seconds")
                .tag("service", "url-service")
                .gauge().value();
        assertEquals(0.0, value, "Gauge should return 0 when no unprocessed events exist");
    }

    @Test
    @DisplayName("outbox_events_age_seconds gauge returns positive value for old events")
    void outboxEventsAgeSecondsGaugeReturnsPositiveValue() {
        LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
        when(outboxRepository.findOldestUnprocessedCreatedAt()).thenReturn(fiveMinutesAgo);

        double value = meterRegistry.get("outbox_events_age_seconds")
                .tag("service", "url-service")
                .gauge().value();
        assertTrue(value >= 299.0 && value <= 301.0,
                "Gauge should return approximately 300 seconds for a 5-minute-old event, got: " + value);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private Outbox createOutboxEvent(Long id, String aggregateType, String aggregateId,
                                      String type, String payload) {
        Outbox event = new Outbox();
        event.setId(id);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setType(type);
        event.setPayload(payload);
        event.setCreatedAt(LocalDateTime.now());
        event.setProcessed(false);
        return event;
    }
}
