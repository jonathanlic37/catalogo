// consumer-api/src/main/java/com/catalogo/consumer/sync/ConflictPolicy.java
package com.catalogo.consumer.sync;

/** Estrategia de resolución cuando el Producer rechaza un UPDATED por versión obsoleta (409). */
public enum ConflictPolicy {
    /** El conflicto queda en manos del usuario (reintentar o descartar). Por defecto. */
    MANUAL,
    /** Se descarta el cambio local y se adopta el estado del Producer. */
    PRODUCER_WINS,
    /** Se reintenta el cambio local rebasándolo sobre la versión actual del Producer. */
    CONSUMER_WINS
}
