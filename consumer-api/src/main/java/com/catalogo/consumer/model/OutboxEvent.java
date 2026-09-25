// consumer-api/src/main/java/com/catalogo/consumer/model/OutboxEvent.java
package com.catalogo.consumer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Outbox transaccional: cada cambio local se registra aquí dentro de la misma transacción que la
 * escritura de la réplica. Un relay lo envía al Producer y deja constancia del resultado
 * (incluidos los fallos), de modo que ningún cambio se pierde aunque el proceso se reinicie.
 *
 * <p>El {@code id} es la Idempotency-Key que viaja al Producer; se reutiliza en cada reintento.
 */
@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_status_next", columnList = "status, nextAttemptAt"),
        @Index(name = "idx_outbox_item", columnList = "itemId")
})
public class OutboxEvent {

    @Id
    @Column(length = 100, nullable = false, updatable = false)
    private String id;

    @Column(nullable = false, length = 36)
    private String itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventType eventType;

    /** Snapshot JSON del cambio en el momento de encolarse (no depende del estado actual del ítem). */
    @Column(nullable = false, length = 4000)
    private String payload;

    /** Marca temporal del cambio; el Producer la usa para descartar eventos fuera de orden. */
    @Column(nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    private Instant nextAttemptAt;

    @Column(length = 500)
    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant sentAt;

    public OutboxEvent() {
    }

    public OutboxEvent(String id, String itemId, EventType eventType, String payload, Instant occurredAt) {
        this.id = id;
        this.itemId = itemId;
        this.eventType = eventType;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = occurredAt;
        this.createdAt = occurredAt;
    }

    public String getId() { return id; }
    public String getItemId() { return itemId; }
    public EventType getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getNextRetryAt() { return nextAttemptAt; }
    public OutboxStatus getStatus() { return status; }
    public void setStatus(OutboxStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
}
