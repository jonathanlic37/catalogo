// consumer-api/src/main/java/com/catalogo/consumer/dto/OutboxEventView.java
package com.catalogo.consumer.dto;

import com.catalogo.consumer.model.EventType;
import com.catalogo.consumer.model.OutboxEvent;
import com.catalogo.consumer.model.OutboxStatus;
import java.time.Instant;

/**
 * Entrada del registro de eventos de sincronización (outbox). No incluye el payload: basta con el
 * ítem, el tipo de evento, el resultado y el motivo del último fallo para diagnosticar.
 */
public record OutboxEventView(
        String id,
        String itemId,
        EventType eventType,
        OutboxStatus status,
        int attempts,
        String lastError,
        Instant occurredAt,
        Instant createdAt,
        Instant nextAttemptAt,
        Instant sentAt) {

    public static OutboxEventView from(OutboxEvent e) {
        return new OutboxEventView(e.getId(), e.getItemId(), e.getEventType(), e.getStatus(), e.getAttempts(),
                e.getLastError(), e.getOccurredAt(), e.getCreatedAt(), e.getNextAttemptAt(), e.getSentAt());
    }
}
