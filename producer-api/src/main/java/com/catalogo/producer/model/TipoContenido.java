// producer-api/src/main/java/com/catalogo/producer/model/TipoContenido.java
package com.catalogo.producer.model;

/**
 * Tipo de elemento de catálogo. El diseño queda preparado para más tipos: añadir un valor aquí
 * (y su equivalente en el Consumer) no requiere cambios de esquema.
 */
public enum TipoContenido {
    PRODUCTO,
    SERVICIO,
    CONTENIDO
}
