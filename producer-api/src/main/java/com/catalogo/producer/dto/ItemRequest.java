// producer-api/src/main/java/com/catalogo/producer/dto/ItemRequest.java
package com.catalogo.producer.dto;

import com.catalogo.producer.model.Estado;
import com.catalogo.producer.model.TipoContenido;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de la API de administración del Producer (crear/editar un ítem directamente en la
 * fuente de verdad). El id y la versión los asigna el Producer; {@code tipo} es opcional.
 */
public record ItemRequest(
        @NotBlank @Size(max = 120) String nombre,
        @Size(max = 1000) String descripcion,
        @NotNull Estado estado,
        TipoContenido tipo) {
}
