// producer-api/src/main/java/com/catalogo/producer/dto/ItemResponse.java
package com.catalogo.producer.dto;

import com.catalogo.producer.model.Estado;
import com.catalogo.producer.model.Item;
import com.catalogo.producer.model.TipoContenido;
import java.time.Instant;

public record ItemResponse(
        String id,
        String nombre,
        String descripcion,
        Estado estado,
        TipoContenido tipo,
        Instant fechaCreacion,
        Instant fechaActualizacion,
        long version) {

    public static ItemResponse from(Item i) {
        return new ItemResponse(i.getId(), i.getNombre(), i.getDescripcion(), i.getEstado(), i.getTipo(),
                i.getFechaCreacion(), i.getFechaActualizacion(), i.getVersion());
    }
}
