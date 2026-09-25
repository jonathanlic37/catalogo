// producer-api/src/main/java/com/catalogo/producer/model/Item.java
package com.catalogo.producer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "items", indexes = @Index(name = "idx_items_fecha_creacion", columnList = "fechaCreacion, id"))
public class Item {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(nullable = false, length = 120)
    private String nombre;

    @Column(length = 1000)
    private String descripcion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Estado estado;

    /**
     * Tipo de elemento de catálogo (producto, servicio, contenido...). Nullable a nivel de columna
     * para que {@code ddl-auto=update} pueda añadirla a una BD existente (SQLite no admite añadir
     * una columna NOT NULL sin default); el código siempre la asigna y la trata como PRODUCTO si falta.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TipoContenido tipo;

    /** Marca del último evento aplicado; los eventos con occurredAt anterior se descartan. */
    private Instant lastEventAt;

    @Column(nullable = false, updatable = false)
    private Instant fechaCreacion;

    @Column(nullable = false)
    private Instant fechaActualizacion;

    @Column(nullable = false)
    private long version;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public Estado getEstado() { return estado; }
    public void setEstado(Estado estado) { this.estado = estado; }
    public TipoContenido getTipo() { return tipo; }
    public void setTipo(TipoContenido tipo) { this.tipo = tipo; }
    public Instant getLastEventAt() { return lastEventAt; }
    public void setLastEventAt(Instant lastEventAt) { this.lastEventAt = lastEventAt; }
    public Instant getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(Instant fechaCreacion) { this.fechaCreacion = fechaCreacion; }
    public Instant getFechaActualizacion() { return fechaActualizacion; }
    public void setFechaActualizacion(Instant fechaActualizacion) { this.fechaActualizacion = fechaActualizacion; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
