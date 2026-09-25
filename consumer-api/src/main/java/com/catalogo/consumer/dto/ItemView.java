// consumer-api/src/main/java/com/catalogo/consumer/dto/ItemView.java
package com.catalogo.consumer.dto;

import com.catalogo.consumer.model.Estado;
import com.catalogo.consumer.model.EventType;
import com.catalogo.consumer.model.FailureReason;
import com.catalogo.consumer.model.Item;
import com.catalogo.consumer.model.SyncStatus;
import com.catalogo.consumer.model.TipoContenido;
import java.time.Instant;

/**
 * Ítem de la proyección tal como lo ve la UI. {@code syncAttempts} y {@code syncError} describen
 * el último intento (también mientras se reintenta); {@code failureReason} solo se informa en FAILED.
 */
public record ItemView(
        String id,
        String nombre,
        String descripcion,
        Estado estado,
        TipoContenido tipo,
        Instant fechaCreacion,
        Instant fechaActualizacion,
        long version,
        SyncStatus syncStatus,
        EventType pendingOperation,
        String syncError,
        int syncAttempts,
        FailureReason failureReason) {

    public static ItemView from(Item i) {
        return new ItemView(i.getId(), i.getNombre(), i.getDescripcion(), i.getEstado(),
                i.getTipo() != null ? i.getTipo() : TipoContenido.PRODUCTO,
                i.getFechaCreacion(), i.getFechaActualizacion(), i.getVersion(),
                i.getSyncStatus(), i.getPendingEvent(), i.getLastSyncError(), i.getSyncAttempts(),
                i.getSyncStatus() == SyncStatus.FAILED ? i.getFailureReason() : null);
    }
}
