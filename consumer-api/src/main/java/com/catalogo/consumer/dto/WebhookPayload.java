// consumer-api/src/main/java/com/catalogo/consumer/dto/WebhookPayload.java
package com.catalogo.consumer.dto;

import com.catalogo.consumer.model.Estado;
import com.catalogo.consumer.model.TipoContenido;
import java.time.Instant;

/**
 * Cuerpo enviado al Producer. {@code baseVersion} es la última versión confirmada (null en CREATED)
 * y {@code occurredAt} la marca temporal del cambio, que el Producer usa para descartar eventos
 * fuera de orden.
 */
public record WebhookPayload(String id, String nombre, String descripcion, Estado estado,
                             TipoContenido tipo, Long baseVersion, Instant occurredAt) {
}
