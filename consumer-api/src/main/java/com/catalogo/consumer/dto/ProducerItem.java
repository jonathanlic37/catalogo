// consumer-api/src/main/java/com/catalogo/consumer/dto/ProducerItem.java
package com.catalogo.consumer.dto;

import com.catalogo.consumer.model.Estado;
import com.catalogo.consumer.model.TipoContenido;
import java.time.Instant;

/** Ítem tal como lo devuelve el Producer (fuente de verdad). */
public record ProducerItem(
        String id,
        String nombre,
        String descripcion,
        Estado estado,
        TipoContenido tipo,
        Instant fechaCreacion,
        Instant fechaActualizacion,
        long version) {
}
