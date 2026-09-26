// consumer-api/src/main/java/com/catalogo/consumer/service/ItemService.java
package com.catalogo.consumer.service;

import com.catalogo.consumer.dto.ItemRequest;
import com.catalogo.consumer.dto.ItemView;
import com.catalogo.consumer.dto.PageResponse;
import com.catalogo.consumer.dto.SummaryView;
import com.catalogo.consumer.dto.WebhookPayload;
import com.catalogo.consumer.model.Estado;
import com.catalogo.consumer.model.EventType;
import com.catalogo.consumer.model.Item;
import com.catalogo.consumer.model.OutboxEvent;
import com.catalogo.consumer.model.OutboxStatus;
import com.catalogo.consumer.model.SyncStatus;
import com.catalogo.consumer.model.TipoContenido;
import com.catalogo.consumer.repository.ItemRepository;
import com.catalogo.consumer.repository.OutboxEventRepository;
import com.catalogo.consumer.sync.SyncRequested;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las lecturas salen de la réplica local. Cada escritura guarda la proyección y encola un evento
 * en el <b>outbox transaccional</b> dentro de la misma transacción; el relay (ver SyncService) lo
 * envía al Producer. Solo se pueden modificar ítems CONFIRMED para no cambiar el contenido de un
 * envío que pudo haber llegado al Producer.
 */
@Service
public class ItemService {

    private final ItemRepository repository;
    private final OutboxEventRepository outbox;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    public ItemService(ItemRepository repository, OutboxEventRepository outbox,
                       ApplicationEventPublisher events, ObjectMapper mapper) {
        this.repository = repository;
        this.outbox = outbox;
        this.events = events;
        this.mapper = mapper;
    }

    /**
     * Lista paginada desde la réplica. Búsqueda por nombre y filtros por estado, tipo de contenido y
     * estado de sincronización, todo resuelto en base de datos con parámetros (criteria API).
     */
    @Transactional(readOnly = true)
    public PageResponse<ItemView> list(int page, int size, String q, Estado estado, TipoContenido tipo,
                                       SyncStatus syncStatus) {
        Specification<Item> spec = Specification.where(null);
        if (estado != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("estado"), estado));
        }
        if (tipo != null) {
            // Los registros anteriores a la columna `tipo` (null) cuentan como PRODUCTO.
            spec = spec.and((root, query, cb) -> tipo == TipoContenido.PRODUCTO
                    ? cb.or(cb.equal(root.get("tipo"), tipo), cb.isNull(root.get("tipo")))
                    : cb.equal(root.get("tipo"), tipo));
        }
        if (syncStatus != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("syncStatus"), syncStatus));
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + escapeLike(q.trim().toLowerCase()) + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("nombre")), pattern, '\\'));
        }
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "fechaActualizacion").and(Sort.by("id")));
        return PageResponse.of(repository.findAll(spec, pageable), ItemView::from);
    }

    @Transactional(readOnly = true)
    public SummaryView summary() {
        return new SummaryView(repository.count(), repository.countByEstado(Estado.ACTIVO),
                repository.countBySyncStatus(SyncStatus.PENDING), repository.countBySyncStatus(SyncStatus.FAILED));
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Transactional(readOnly = true)
    public ItemView get(String id) {
        return ItemView.from(find(id));
    }

    @Transactional
    public ItemView create(ItemRequest req) {
        Instant now = Instant.now();
        Item item = new Item();
        item.setId(UUID.randomUUID().toString());
        item.setNombre(req.nombre().trim());
        item.setDescripcion(req.descripcion());
        item.setEstado(req.estado());
        item.setTipo(req.tipo() != null ? req.tipo() : TipoContenido.PRODUCTO);
        item.setFechaCreacion(now);
        item.setFechaActualizacion(now);
        item.setVersion(0);
        String key = UUID.randomUUID().toString();
        markPending(item, EventType.CREATED, key, now);
        Item saved = repository.save(item);
        enqueue(saved, EventType.CREATED, key, null, now);
        events.publishEvent(new SyncRequested(saved.getId()));
        return ItemView.from(saved);
    }

    @Transactional
    public ItemView update(String id, ItemRequest req) {
        Item item = requireConfirmed(find(id));
        item.setNombre(req.nombre().trim());
        item.setDescripcion(req.descripcion());
        item.setEstado(req.estado());
        if (req.tipo() != null) {
            item.setTipo(req.tipo());
        }
        Instant now = Instant.now();
        item.setFechaActualizacion(now);
        String key = UUID.randomUUID().toString();
        markPending(item, EventType.UPDATED, key, now);
        Item saved = repository.save(item);
        enqueue(saved, EventType.UPDATED, key, saved.getVersion(), now);
        events.publishEvent(new SyncRequested(saved.getId()));
        return ItemView.from(saved);
    }

    @Transactional
    public ItemView delete(String id) {
        Item item = requireConfirmed(find(id));
        Instant now = Instant.now();
        String key = UUID.randomUUID().toString();
        markPending(item, EventType.DELETED, key, now);
        Item saved = repository.save(item);
        enqueue(saved, EventType.DELETED, key, saved.getVersion(), now);
        events.publishEvent(new SyncRequested(saved.getId()));
        return ItemView.from(saved);
    }

    /** Reenvía los eventos FAILED del ítem con el mismo contenido y la misma Idempotency-Key. */
    @Transactional
    public ItemView retry(String id) {
        Item item = find(id);
        if (item.getSyncStatus() != SyncStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "Solo se pueden reintentar ítems con sincronización fallida");
        }
        List<OutboxEvent> failed = outbox.findByItemIdAndStatusIn(id, List.of(OutboxStatus.FAILED));
        if (failed.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "No hay un evento fallido que reintentar");
        }
        Instant now = Instant.now();
        for (OutboxEvent e : failed) {
            e.setStatus(OutboxStatus.PENDING);
            e.setAttempts(0);
            e.setNextAttemptAt(now);
            e.setLastError(null);
            outbox.save(e);
        }
        item.setSyncStatus(SyncStatus.PENDING);
        item.setSyncAttempts(0);
        item.setLastSyncError(null);
        item.setFailureReason(null);
        item.setNextRetryAt(now);
        Item saved = repository.save(item);
        events.publishEvent(new SyncRequested(saved.getId()));
        return ItemView.from(saved);
    }

    /** Encola el evento en el outbox dentro de la misma transacción que la escritura de la réplica. */
    private void enqueue(Item item, EventType type, String key, Long baseVersion, Instant occurredAt) {
        WebhookPayload payload = new WebhookPayload(item.getId(), item.getNombre(), item.getDescripcion(),
                item.getEstado(), item.getTipo(), baseVersion, occurredAt);
        try {
            outbox.save(new OutboxEvent(key, item.getId(), type, mapper.writeValueAsString(payload), occurredAt));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el evento del outbox", e);
        }
    }

    private static void markPending(Item item, EventType event, String key, Instant now) {
        item.setSyncStatus(SyncStatus.PENDING);
        item.setPendingEvent(event);
        item.setIdempotencyKey(key);
        item.setSyncAttempts(0);
        item.setLastSyncError(null);
        item.setFailureReason(null);
        item.setNextRetryAt(now);
    }

    private static Item requireConfirmed(Item item) {
        if (item.getSyncStatus() != SyncStatus.CONFIRMED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "El ítem tiene un cambio pendiente o fallido; espere la confirmación, reintente o resincronice");
        }
        return item;
    }

    private Item find(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ítem no encontrado"));
    }
}
