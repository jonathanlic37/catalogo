// consumer-api/src/main/java/com/catalogo/consumer/web/SyncController.java
package com.catalogo.consumer.web;

import com.catalogo.consumer.service.ApiException;
import com.catalogo.consumer.sync.ReconcileService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

/** Operaciones de sincronización disparadas a demanda (además de las programadas). */
@RestController
@RequestMapping("/api")
public class SyncController {

    private final ReconcileService reconcile;

    public SyncController(ReconcileService reconcile) {
        this.reconcile = reconcile;
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
}
