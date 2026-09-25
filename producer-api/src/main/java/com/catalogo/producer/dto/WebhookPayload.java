// producer-api/src/main/java/com/catalogo/producer/dto/WebhookPayload.java
package com.catalogo.producer.dto;

import com.catalogo.producer.model.Estado;
import com.catalogo.producer.model.TipoContenido;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Cuerpo del webhook enviado por el Consumer. {@code baseVersion} es la versión del
 * Producer sobre la que el Consumer basó su edición (null en CREATED) y {@code occurredAt}
 * la marca del cambio, usada para descartar eventos entregados fuera de orden.
 */
public record WebhookPayload(
        @NotBlank @Size(max = 36) @Pattern(regexp = "^[A-Za-z0-9-]+$") String id,
        @Size(max = 120) String nombre,
        @Size(max = 1000) String descripcion,
        Estado estado,
        TipoContenido tipo,
        Long baseVersion,
        Instant occurredAt) {
}
