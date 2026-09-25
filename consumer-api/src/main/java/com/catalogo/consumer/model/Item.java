// consumer-api/src/main/java/com/catalogo/consumer/model/Item.java
package com.catalogo.consumer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** Réplica local de un ítem del catálogo más el estado de sincronización de su último cambio. */
@Entity
@Table(name = "items", indexes = {
        @Index(name = "idx_items_sync", columnList = "syncStatus, nextRetryAt"),
        @Index(name = "idx_items_fecha_act", columnList = "fechaActualizacion")
})
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

    @Column(nullable = false, updatable = false)
    private Instant fechaCreacion;

    @Column(nullable = false)
    private Instant fechaActualizacion;

    /** Última versión confirmada por el Producer (0 = nunca confirmado). */
    @Column(nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncStatus syncStatus;

    /** Operación pendiente de confirmar; null cuando está CONFIRMED. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private EventType pendingEvent;

    /** Clave de idempotencia del envío en curso; se reutiliza en cada reintento. */
    @Column(length = 100)
    private String idempotencyKey;

    @Column(nullable = false)
    private int syncAttempts;

    @Column(length = 500)
    private String lastSyncError;

    private Instant nextRetryAt;

    /** Motivo del último fallo definitivo (solo con syncStatus FAILED). */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private FailureReason failureReason;

    public FailureReason getFailureReason() { return failureReason; }
    public void setFailureReason(FailureReason failureReason) { this.failureReason = failureReason; }
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
    public Instant getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(Instant fechaCreacion) { this.fechaCreacion = fechaCreacion; }
    public Instant getFechaActualizacion() { return fechaActualizacion; }
    public void setFechaActualizacion(Instant fechaActualizacion) { this.fechaActualizacion = fechaActualizacion; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public SyncStatus getSyncStatus() { return syncStatus; }
    public void setSyncStatus(SyncStatus syncStatus) { this.syncStatus = syncStatus; }
    public EventType getPendingEvent() { return pendingEvent; }
    public void setPendingEvent(EventType pendingEvent) { this.pendingEvent = pendingEvent; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public int getSyncAttempts() { return syncAttempts; }
    public void setSyncAttempts(int syncAttempts) { this.syncAttempts = syncAttempts; }
    public String getLastSyncError() { return lastSyncError; }
    public void setLastSyncError(String lastSyncError) { this.lastSyncError = lastSyncError; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
}
