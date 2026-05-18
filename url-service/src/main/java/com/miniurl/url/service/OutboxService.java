package com.miniurl.url.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniurl.url.entity.Outbox;
import com.miniurl.url.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveEvent(String aggregateType, String aggregateId, String type, Object payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            
            Outbox outbox = Outbox.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .type(type)
                .payload(payloadJson)
                .createdAt(LocalDateTime.now())
                .processed(false)
                .build();
            
            outboxRepository.save(outbox);
            log.debug("Saved event to outbox: {} for aggregate {} {}", type, aggregateType, aggregateId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload for outbox event: {}", type, e);
            throw new RuntimeException("Event serialization failed", e);
        }
    }
}
