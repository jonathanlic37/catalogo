// consumer-api/src/main/java/com/catalogo/consumer/web/ItemController.java
package com.catalogo.consumer.web;

import com.catalogo.consumer.dto.ItemRequest;
import com.catalogo.consumer.dto.ItemView;
import com.catalogo.consumer.dto.PageResponse;
import com.catalogo.consumer.dto.SummaryView;
import com.catalogo.consumer.model.Estado;
import com.catalogo.consumer.service.ItemService;
import com.catalogo.consumer.sync.ReconcileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Única API que consume el frontend. Las escrituras responden 202: el cambio queda pendiente de confirmación. */
@RestController
@RequestMapping("/api/items")
public class ItemController {

    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_QUERY_LENGTH = 100;

    private final ItemService service;
    private final ReconcileService reconcile;

    public ItemController(ItemService service, ReconcileService reconcile) {
        this.service = service;
        this.reconcile = reconcile;
    }

    @GetMapping
    public PageResponse<ItemView> list(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "25") int size,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) Estado estado) {
        String query = q == null ? null : q.substring(0, Math.min(q.length(), MAX_QUERY_LENGTH));
        return service.list(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), query, estado);
    }

    @GetMapping("/summary")
    public SummaryView summary() {
        return service.summary();
    }

    @GetMapping("/{id}")
    public ItemView get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<ItemView> create(@Valid @RequestBody ItemRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ItemView> update(@PathVariable String id, @Valid @RequestBody ItemRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ItemView> delete(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.delete(id));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<ItemView> retry(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.retry(id));
    }

    /** Descarta el cambio local fallido y recupera la versión del Producer (204 si ya no existe). */
    @PostMapping("/{id}/resync")
    public ResponseEntity<ItemView> resync(@PathVariable String id) {
        ItemView view = reconcile.resync(id);
        return view == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(view);
    }
}
