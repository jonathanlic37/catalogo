// consumer-api/src/main/java/com/catalogo/consumer/sync/SyncRequested.java
package com.catalogo.consumer.sync;

/** Evento interno: hay un cambio local que enviar al Producer (se publica dentro de la transacción). */
public record SyncRequested(String itemId) {
}
