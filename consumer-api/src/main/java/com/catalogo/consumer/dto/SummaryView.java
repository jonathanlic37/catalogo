// consumer-api/src/main/java/com/catalogo/consumer/dto/SummaryView.java
package com.catalogo.consumer.dto;

/** Contadores globales del catálogo (independientes de la página que se esté viendo). */
public record SummaryView(long total, long activos, long pendientes, long fallidos) {
}
