// producer-api/src/main/java/com/catalogo/producer/service/ItemService.java
package com.catalogo.producer.service;

import com.catalogo.producer.dto.EventType;
import com.catalogo.producer.dto.ItemRequest;
import com.catalogo.producer.dto.ItemResponse;
import com.catalogo.producer.dto.PageResponse;
import com.catalogo.producer.dto.WebhookPayload;
import com.catalogo.producer.model.Estado;
import com.catalogo.producer.model.Item;
import com.catalogo.producer.model.TipoContenido;
import com.catalogo.producer.repository.ItemRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ItemService {

    private final ItemRepository repository;

    public ItemService(ItemRepository repository) {
        this.repository = repository;
    }

    /** Lista paginada con orden estable (fechaCreacion, id) para poder recorrerla sin saltos. */
    @Transactional(readOnly = true)
    public PageResponse<ItemResponse> list(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("fechaCreacion", "id"));
        return PageResponse.of(repository.findAll(pageable), ItemResponse::from);
    }

    @Transactional(readOnly = true)
    public ItemResponse get(String id) {
        return ItemResponse.from(find(id));
    }

    /** Aplica un evento del Consumer sobre la fuente de verdad. Debe ejecutarse en una transacción. */
    @Transactional
    public ItemResponse apply(EventType type, WebhookPayload p) {
        return switch (type) {
            case CREATED -> create(p);
            case UPDATED -> update(p);
            case DELETED -> delete(p);
        };
    }

    /** Crea un ítem directamente en la fuente de verdad (API de administración del Producer). */
    @Transactional
    public ItemResponse createDirect(ItemRequest r) {
        Instant now = Instant.now();
        Item item = new Item();
        item.setId(UUID.randomUUID().toString());
        item.setNombre(r.nombre().trim());
        item.setDescripcion(r.descripcion());
        item.setEstado(r.estado());
        item.setTipo(r.tipo() != null ? r.tipo() : TipoContenido.PRODUCTO);
        item.setFechaCreacion(now);
        item.setFechaActualizacion(now);
        item.setVersion(1);
        item.setLastEventAt(now);
        return ItemResponse.from(repository.save(item));
    }

    /** Edita un ítem directamente en la fuente de verdad. */
    @Transactional
    public ItemResponse updateDirect(String id, ItemRequest r) {
        Item item = find(id);
        item.setNombre(r.nombre().trim());
        item.setDescripcion(r.descripcion());
        item.setEstado(r.estado());
        if (r.tipo() != null) {
            item.setTipo(r.tipo());
        }
        Instant now = Instant.now();
        item.setFechaActualizacion(now);
        item.setVersion(item.getVersion() + 1);
        item.setLastEventAt(now);
        return ItemResponse.from(repository.save(item));
    }

    /** Elimina un ítem directamente de la fuente de verdad. */
    @Transactional
    public void deleteDirect(String id) {
        repository.delete(find(id));
    }

    private ItemResponse create(WebhookPayload p) {
        requireNombre(p);
        if (repository.existsById(p.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya existe un ítem con ese id");
        }
        Instant now = Instant.now();
        Item item = new Item();
        item.setId(p.id());
        item.setNombre(p.nombre().trim());
        item.setDescripcion(p.descripcion());
        item.setEstado(p.estado() != null ? p.estado() : Estado.ACTIVO);
        item.setTipo(p.tipo() != null ? p.tipo() : TipoContenido.PRODUCTO);
        item.setFechaCreacion(now);
        item.setFechaActualizacion(now);
        item.setVersion(1);
        item.setLastEventAt(p.occurredAt() != null ? p.occurredAt() : now);
        return ItemResponse.from(repository.save(item));
    }

    private ItemResponse update(WebhookPayload p) {
        requireNombre(p);
        Item item = find(p.id());
        if (isStale(item, p)) {
            // Evento entregado fuera de orden: ya se aplicó uno más reciente. Se devuelve el estado
            // autoritativo actual sin modificarlo.
            return ItemResponse.from(item);
        }
        if (p.baseVersion() != null && p.baseVersion() != item.getVersion()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Versión obsoleta: el ítem ya fue modificado en la fuente de verdad");
        }
        item.setNombre(p.nombre().trim());
        item.setDescripcion(p.descripcion());
        if (p.estado() != null) {
            item.setEstado(p.estado());
        }
        if (p.tipo() != null) {
            item.setTipo(p.tipo());
        }
        item.setFechaActualizacion(Instant.now());
        item.setVersion(item.getVersion() + 1);
        item.setLastEventAt(p.occurredAt() != null ? p.occurredAt() : Instant.now());
        return ItemResponse.from(repository.save(item));
    }

    private ItemResponse delete(WebhookPayload p) {
        Item item = find(p.id());
        if (isStale(item, p)) {
            return ItemResponse.from(item);
        }
        ItemResponse snapshot = ItemResponse.from(item);
        repository.delete(item);
        return snapshot;
    }

    private static boolean isStale(Item item, WebhookPayload p) {
        return p.occurredAt() != null && item.getLastEventAt() != null
                && p.occurredAt().isBefore(item.getLastEventAt());
    }

    private Item find(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ítem no encontrado"));
    }

    private static void requireNombre(WebhookPayload p) {
        if (p.nombre() == null || p.nombre().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
    }
}
