// producer-api/src/main/java/com/catalogo/producer/service/IdempotencyService.java
package com.catalogo.producer.service;

import com.catalogo.producer.dto.EventType;
import com.catalogo.producer.dto.ItemResponse;
import com.catalogo.producer.dto.WebhookPayload;
import com.catalogo.producer.model.IdempotencyRecord;
import com.catalogo.producer.repository.IdempotencyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Procesa webhooks de forma idempotente: la misma Idempotency-Key con el mismo contenido
 * devuelve la respuesta original sin volver a aplicar el cambio; con contenido distinto
 * se rechaza con 409 (reutilización indebida de la clave).
 */
@Service
public class IdempotencyService {

    public record Result(ItemResponse body, int status, boolean replayed) {}

    private final IdempotencyRepository repository;
    private final ItemService itemService;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public IdempotencyService(IdempotencyRepository repository, ItemService itemService, ObjectMapper mapper,
                              PlatformTransactionManager txManager) {
        this.repository = repository;
        this.itemService = itemService;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Si dos peticiones con la misma clave compiten, la que pierde choca con la clave primaria de
     * idempotency_records. Su transacción se revierte entera y se repite en una nueva, que ya ve el
     * registro de la ganadora: replay si el contenido coincide, 409 si no. Nunca un 500.
     * No se usa REQUIRES_NEW anidado: con el pool de una conexión se bloquearía esperándose a sí mismo.
     */
    public Result process(String key, EventType type, WebhookPayload payload) {
        String hash = hash(type, payload);
        try {
            return tx.execute(s -> attempt(key, type, payload, hash));
        } catch (DataIntegrityViolationException race) {
            return tx.execute(s -> attempt(key, type, payload, hash));
        }
    }

    private Result attempt(String key, EventType type, WebhookPayload payload, String hash) {

        Optional<IdempotencyRecord> existing = repository.findById(key);
        if (existing.isPresent()) {
            IdempotencyRecord rec = existing.get();
            if (!rec.getRequestHash().equals(hash)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Idempotency-Key ya utilizada con un contenido distinto");
            }
            return new Result(read(rec.getResponseBody()), rec.getStatusCode(), true);
        }

        ItemResponse response = itemService.apply(type, payload);
        int status = type == EventType.CREATED ? HttpStatus.CREATED.value() : HttpStatus.OK.value();
        repository.saveAndFlush(new IdempotencyRecord(key, hash, status, write(response)));
        return new Result(response, status, false);
    }

    private String hash(EventType type, WebhookPayload payload) {
        try {
            String canonical = type.name() + "|" + mapper.writeValueAsString(payload);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("No se pudo calcular el hash de la petición", e);
        }
    }

    private String write(ItemResponse response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la respuesta", e);
        }
    }

    private ItemResponse read(String json) {
        try {
            return mapper.readValue(json, ItemResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Registro de idempotencia corrupto", e);
        }
    }
}
