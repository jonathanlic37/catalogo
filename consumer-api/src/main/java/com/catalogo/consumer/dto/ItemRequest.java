// consumer-api/src/main/java/com/catalogo/consumer/dto/ItemRequest.java
package com.catalogo.consumer.dto;

import com.catalogo.consumer.model.Estado;
import com.catalogo.consumer.model.TipoContenido;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code tipo} es opcional; si falta se asume PRODUCTO. */
public record ItemRequest(
        @NotBlank @Size(max = 120) String nombre,
        @Size(max = 1000) String descripcion,
        @NotNull Estado estado,
        TipoContenido tipo) {
}
