// consumer-api/src/main/java/com/catalogo/consumer/sync/SyncMetrics.java
package com.catalogo.consumer.sync;

import com.catalogo.consumer.model.OutboxStatus;
import com.catalogo.consumer.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Métricas de sincronización expuestas vía Micrometer (Actuator/Prometheus). */
@Component
public class SyncMetrics {

    private final OutboxEventRepository outbox;
    private final Counter attempts;
    private final Counter success;
    private final Counter failures;
    private final Counter retries;
    private final Counter conflicts;
    private final Counter reconcileRuns;
    private final Counter reconcileCreated;
    private final Counter reconcileUpdated;
    private final Counter reconcileDeleted;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong lastReconcileEpochSeconds = new AtomicLong();

    public SyncMetrics(MeterRegistry registry, OutboxEventRepository outbox) {
        this.outbox = outbox;
        this.attempts = Counter.builder("sync.outbox.attempts")
                .description("Envíos al Producer intentados").register(registry);
        this.success = Counter.builder("sync.outbox.success")
                .description("Eventos aceptados por el Producer").register(registry);
        this.failures = Counter.builder("sync.outbox.failures")
                .description("Eventos rechazados o agotados").register(registry);
        this.retries = Counter.builder("sync.outbox.retries")
                .description("Reintentos programados por fallo del Producer").register(registry);
        this.conflicts = Counter.builder("sync.outbox.conflicts")
                .description("Conflictos de versión (409) detectados").register(registry);
        Gauge.builder("sync.outbox.pending", pending, AtomicLong::get)
                .description("Eventos pendientes en el outbox").register(registry);
        Gauge.builder("sync.outbox.failed", failed, AtomicLong::get)
                .description("Eventos fallidos en el outbox").register(registry);
        this.reconcileRuns = Counter.builder("sync.reconcile.runs")
                .description("Reconciliaciones completadas con el Producer").register(registry);
        this.reconcileCreated = Counter.builder("sync.reconcile.items").tag("change", "created")
                .description("Ítems sembrados en la proyección por reconciliación").register(registry);
        this.reconcileUpdated = Counter.builder("sync.reconcile.items").tag("change", "updated")
                .description("Ítems actualizados en la proyección por reconciliación").register(registry);
        this.reconcileDeleted = Counter.builder("sync.reconcile.items").tag("change", "deleted")
                .description("Ítems retirados de la proyección por reconciliación").register(registry);
        Gauge.builder("sync.reconcile.last.success", lastReconcileEpochSeconds, AtomicLong::get)
                .description("Epoch (s) de la última reconciliación correcta; permite alertar si la proyección envejece")
                .baseUnit("seconds").register(registry);
    }

    public void reconciled(int created, int updated, int deleted) {
        reconcileRuns.increment();
        reconcileCreated.increment(created);
        reconcileUpdated.increment(updated);
        reconcileDeleted.increment(deleted);
        lastReconcileEpochSeconds.set(java.time.Instant.now().getEpochSecond());
    }

    public void attempt() { attempts.increment(); }
    public void success() { success.increment(); }
    public void failure() { failures.increment(); }
    public void retry() { retries.increment(); }
    public void conflict() { conflicts.increment(); }

    /** Refresca los gauges de estado del outbox (consultas acotadas, no en cada envío). */
    @Scheduled(fixedDelayString = "${app.sync.metrics-interval-ms:15000}")
    public void refresh() {
        pending.set(outbox.countByStatus(OutboxStatus.PENDING));
        failed.set(outbox.countByStatus(OutboxStatus.FAILED));
    }
}
