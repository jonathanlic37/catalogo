// consumer-api/src/main/java/com/catalogo/consumer/model/FailureReason.java
package com.catalogo.consumer.model;

/**
 * Por qué un cambio quedó FAILED. Decide qué acción tiene sentido ofrecer al usuario: reenviar el
 * mismo contenido solo sirve ante un rechazo (p. ej. credenciales corregidas); ante un conflicto o
 * un ítem que ya no existe, reenviar volvería a fallar y lo correcto es descartar el cambio local.
 *
 * <p>Los fallos transitorios (red, 5xx, 408/429) no llegan a FAILED: se reintentan con backoff.
 */
public enum FailureReason {
    /** 409: el ítem cambió en el Producer desde que se leyó (versión obsoleta). Descartar y reeditar. */
    CONFLICT,
    /** 404: el ítem ya no existe en el Producer. Descartar. */
    GONE,
    /** Otro 4xx (validación, credenciales…). Reintentar tiene sentido tras corregir la causa. */
    REJECTED
}
