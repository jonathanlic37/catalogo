// consumer-api/src/main/java/com/catalogo/consumer/model/SyncStatus.java
package com.catalogo.consumer.model;

public enum SyncStatus {
    /** Cambio local aún no confirmado por el Producer. */
    PENDING,
    /** El Producer aceptó el cambio; la réplica coincide con la fuente de verdad. */
    CONFIRMED,
    /** El Producer rechazó el cambio o se agotaron los reintentos. */
    FAILED
}
