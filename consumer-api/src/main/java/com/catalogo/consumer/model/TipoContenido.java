// consumer-api/src/main/java/com/catalogo/consumer/model/TipoContenido.java
package com.catalogo.consumer.model;

/**
 * Tipo de elemento de catálogo. El diseño queda preparado para más tipos: añadir un valor aquí
 * (y su equivalente en el Producer) no requiere cambios de esquema.
 */
public enum TipoContenido {
    PRODUCTO,
    SERVICIO,
    CONTENIDO
}
