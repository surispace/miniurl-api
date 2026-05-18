package com.miniurl.identity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniurl.identity.entity.Outbox;
import com.miniurl.identity.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveEvent(String aggregateType, String aggregateId, String type, Object payload) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            Outbox outbox = Outbox.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .type(type)
                    .payload(jsonPayload)
                    .createdAt(LocalDateTime.now())
                    .processed(false)
                    .build();
            outboxRepository.save(outbox);
            log.debug("Saved event {} for {} {} to outbox", type, aggregateType, aggregateId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload for event {}: {}", type, e.getMessage());
            throw new RuntimeException("Event serialization failed", e);
        }
    }
}
