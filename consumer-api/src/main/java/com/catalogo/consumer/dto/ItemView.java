// consumer-api/src/main/java/com/catalogo/consumer/dto/ItemView.java
package com.catalogo.consumer.dto;

import com.catalogo.consumer.model.Estado;
import com.catalogo.consumer.model.EventType;
import com.catalogo.consumer.model.Item;
import com.catalogo.consumer.model.SyncStatus;
import com.catalogo.consumer.model.TipoContenido;
import java.time.Instant;

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
        String syncError) {

    public static ItemView from(Item i) {
        return new ItemView(i.getId(), i.getNombre(), i.getDescripcion(), i.getEstado(),
                i.getTipo() != null ? i.getTipo() : TipoContenido.PRODUCTO,
                i.getFechaCreacion(), i.getFechaActualizacion(), i.getVersion(),
                i.getSyncStatus(), i.getPendingEvent(), i.getLastSyncError());
    }
}
