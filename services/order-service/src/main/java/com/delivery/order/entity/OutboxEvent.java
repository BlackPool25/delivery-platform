package com.delivery.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
@IdClass(OutboxEventId.class)
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Id
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tracing_headers", columnDefinition = "jsonb")
    private String tracingHeaders;

    @Column(name = "processed", nullable = false)
    private boolean processed;

    public OutboxEvent() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.processed = false;
    }

    public OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String payload, String tracingHeaders) {
        this();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.tracingHeaders = tracingHeaders;
    }

    public OutboxEvent(UUID id, Instant createdAt, String aggregateType, UUID aggregateId, String eventType, String payload) {
        this.id = id;
        this.createdAt = createdAt;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.processed = false;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(UUID aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getTracingHeaders() {
        return tracingHeaders;
    }

    public void setTracingHeaders(String tracingHeaders) {
        this.tracingHeaders = tracingHeaders;
    }

    public boolean isProcessed() {
        return processed;
    }

    public void setProcessed(boolean processed) {
        this.processed = processed;
    }
}
