// producer-api/src/main/java/com/catalogo/producer/model/IdempotencyRecord.java
package com.catalogo.producer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * Resultado almacenado de un webhook ya procesado, indexado por Idempotency-Key.
 * Es {@link Persistable} para que guardarlo sea siempre un INSERT: con un id asignado, save() haría
 * un merge y una clave duplicada por concurrencia pasaría inadvertida en vez de violar la PK.
 */
@Entity
@Table(name = "idempotency_records", indexes = @Index(name = "idx_idem_created_at", columnList = "createdAt"))
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(length = 100, nullable = false, updatable = false)
    private String key;

    @Column(nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(nullable = false, updatable = false)
    private int statusCode;

    @Column(nullable = false, updatable = false, length = 4000)
    private String responseBody;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected IdempotencyRecord() {}

    public IdempotencyRecord(String key, String requestHash, int statusCode, String responseBody) {
        this(key, requestHash, statusCode, responseBody, Instant.now());
    }

    public IdempotencyRecord(String key, String requestHash, int statusCode, String responseBody, Instant createdAt) {
        this.key = key;
        this.requestHash = requestHash;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
    }

    @PostLoad
    void markLoaded() { isNew = false; }

    @Override public String getId() { return key; }
    @Override public boolean isNew() { return isNew; }
    public String getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
}
