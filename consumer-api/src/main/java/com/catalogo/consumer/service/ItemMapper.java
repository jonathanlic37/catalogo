// consumer-api/src/main/java/com/catalogo/consumer/service/ItemMapper.java
package com.catalogo.consumer.service;

import com.catalogo.consumer.dto.ProducerItem;
import com.catalogo.consumer.model.Item;
import com.catalogo.consumer.model.SyncStatus;
import com.catalogo.consumer.model.TipoContenido;

public final class ItemMapper {

    private ItemMapper() {}

    /** Sobrescribe la réplica con el estado autoritativo del Producer y la marca CONFIRMED. */
    public static Item applyRemote(Item local, ProducerItem remote) {
        if (local.getId() == null) {
            local.setId(remote.id());
        }
        local.setNombre(remote.nombre());
        local.setDescripcion(remote.descripcion());
        local.setEstado(remote.estado());
        local.setTipo(remote.tipo() != null ? remote.tipo() : TipoContenido.PRODUCTO);
        if (local.getFechaCreacion() == null || remote.fechaCreacion() != null) {
            local.setFechaCreacion(remote.fechaCreacion());
        }
        local.setFechaActualizacion(remote.fechaActualizacion());
        local.setVersion(remote.version());
        local.setSyncStatus(SyncStatus.CONFIRMED);
        local.setPendingEvent(null);
        local.setIdempotencyKey(null);
        local.setSyncAttempts(0);
        local.setLastSyncError(null);
        local.setNextRetryAt(null);
        local.setFailureReason(null);
        return local;
    }
}
