// consumer-api/src/main/java/com/catalogo/consumer/web/SyncController.java
package com.catalogo.consumer.web;

import com.catalogo.consumer.dto.OutboxEventView;
import com.catalogo.consumer.dto.PageResponse;
import com.catalogo.consumer.model.OutboxStatus;
import com.catalogo.consumer.repository.OutboxEventRepository;
import com.catalogo.consumer.service.ApiException;
import com.catalogo.consumer.sync.ReconcileService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

/** Operaciones de sincronización disparadas a demanda (además de las programadas) y su registro. */
@RestController
@RequestMapping("/api")
public class SyncController {

    static final int MAX_PAGE_SIZE = 100;

    private final ReconcileService reconcile;
    private final OutboxEventRepository outbox;

    public SyncController(ReconcileService reconcile, OutboxEventRepository outbox) {
        this.reconcile = reconcile;
        this.outbox = outbox;
    }

    /** Fuerza una reconciliación con el Producer y devuelve cuántos ítems se sembraron/actualizaron/borraron. */
    @PostMapping("/reconcile")
    public ReconcileService.ReconcileResult reconcile() {
        try {
            return reconcile.reconcileAll();
        } catch (RestClientException | IllegalStateException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Producer no disponible");
        }
    }

    /**
     * Registro de eventos de sincronización (outbox), del más reciente al más antiguo. Con
     * {@code status=FAILED} devuelve el registro de eventos fallidos con el motivo de cada uno.
     */
    @GetMapping("/sync/events")
    @Transactional(readOnly = true)
    public PageResponse<OutboxEventView> events(@RequestParam(required = false) OutboxStatus status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "25") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return PageResponse.of(status == null ? outbox.findAll(pageable) : outbox.findByStatus(status, pageable),
                OutboxEventView::from);
    }
}
