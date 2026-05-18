package com.miniurl.identity.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "outbox")
public class Outbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime processedAt;

    @Column(nullable = false)
    private boolean processed = false;

    // Getters
    public Long getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public boolean isProcessed() { return processed; }

    // Setters
    public void setId(Long id) { this.id = id; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
    public void setType(String type) { this.type = type; }
    public void setPayload(String payload) { this.payload = payload; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setProcessedAt(LocalDateTime processedAt) { this.processedAt = processedAt; }
    public void setProcessed(boolean processed) { this.processed = processed; }

    // Builder pattern factory
    public static Builder builder() {
        return new Builder();
    }

    // Builder pattern
    public static class Builder {
        private Long id;
        private String aggregateType;
        private String aggregateId;
        private String type;
        private String payload;
        private LocalDateTime createdAt;
        private LocalDateTime processedAt;
        private boolean processed = false;

        public Builder aggregateType(String aggregateType) {
            this.aggregateType = aggregateType;
            return this;
        }

        public Builder aggregateId(String aggregateId) {
            this.aggregateId = aggregateId;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder processed(boolean processed) {
            this.processed = processed;
            return this;
        }

        public Outbox build() {
            Outbox outbox = new Outbox();
            outbox.id = id;
            outbox.aggregateType = aggregateType;
            outbox.aggregateId = aggregateId;
            outbox.type = type;
            outbox.payload = payload;
            outbox.createdAt = createdAt;
            outbox.processedAt = processedAt;
            outbox.processed = processed;
            return outbox;
        }
    }
}
