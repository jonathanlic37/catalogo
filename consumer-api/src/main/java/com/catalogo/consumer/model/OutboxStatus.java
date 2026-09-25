// consumer-api/src/main/java/com/catalogo/consumer/model/OutboxStatus.java
package com.catalogo.consumer.model;

/** Estado de un evento del outbox transaccional del Consumer. */
public enum OutboxStatus {
    /** Pendiente de enviar (o de reintentar). */
    PENDING,
    /** El Producer aceptó el evento. */
    SENT,
    /** El Producer rechazó el evento o se agotaron los reintentos. */
    FAILED
}
