// consumer-api/src/main/java/com/catalogo/consumer/dto/ProducerItem.java
package com.catalogo.consumer.dto;

import com.catalogo.consumer.model.Estado;
import com.catalogo.consumer.model.TipoContenido;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Ítem tal como lo devuelve el Producer (fuente de verdad). Lector tolerante: si el Producer añade
 * campos nuevos, el Consumer sigue funcionando (evolución compatible del contrato).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
