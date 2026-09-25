// consumer-api/src/main/java/com/catalogo/consumer/sync/ReconcileService.java
package com.catalogo.consumer.sync;

import com.catalogo.consumer.dto.ItemView;
import com.catalogo.consumer.dto.ProducerItem;
import com.catalogo.consumer.model.Item;
import com.catalogo.consumer.model.SyncStatus;
import com.catalogo.consumer.repository.ItemRepository;
import com.catalogo.consumer.repository.OutboxEventRepository;
import com.catalogo.consumer.service.ApiException;
import com.catalogo.consumer.service.ItemMapper;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Mantiene la réplica alineada con la fuente de verdad: siembra una réplica vacía, recoge cambios
 * hechos directamente en el Producer y permite descartar un cambio local rechazado (resync).
 * Nunca toca ítems con cambios locales PENDING/FAILED.
 *
 * <p>Las llamadas HTTP al Producer se hacen fuera de transacción (el pool SQLite es de 1 conexión)
 * y cada escritura vuelve a leer el ítem dentro de su propia transacción: si el usuario lo ha
 * editado mientras tanto (ya no está CONFIRMED), la reconciliación no lo pisa.
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    private final ItemRepository repository;
    private final OutboxEventRepository outbox;
    private final ProducerWebhookClient client;
    private final SyncMetrics metrics;
    private final TransactionTemplate tx;

    public ReconcileService(ItemRepository repository, OutboxEventRepository outbox, ProducerWebhookClient client,
                            SyncMetrics metrics, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.outbox = outbox;
        this.client = client;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Resumen de una reconciliación: altas, actualizaciones y bajas aplicadas a la réplica. */
    public record ReconcileResult(int created, int updated, int deleted) {
    }

    @Scheduled(initialDelay = 3000, fixedDelayString = "${app.sync.reconcile-interval-ms}")
    public void scheduled() {
        try {
            ReconcileResult r = reconcileAll();
            if (r.created() + r.updated() + r.deleted() > 0) {
                log.info("Reconciliación: {} nuevos, {} actualizados, {} eliminados", r.created(), r.updated(), r.deleted());
            }
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Reconciliación omitida: Producer no disponible");
        }
    }

    public ReconcileResult reconcileAll() {
        Instant fetchStartedAt = Instant.now();
        Set<String> remoteIds = new HashSet<>();
        int[] created = {0};
        int[] updated = {0};

        client.forEachPage(page -> {
            for (ProducerItem r : page) {
                remoteIds.add(r.id());
                tx.executeWithoutResult(s -> {
                    Optional<Item> local = repository.findById(r.id());
                    if (local.isEmpty()) {
                        repository.save(ItemMapper.applyRemote(new Item(), r));
                        created[0]++;
                    } else if (local.get().getSyncStatus() == SyncStatus.CONFIRMED
                            && r.version() > local.get().getVersion()) {
                        // Versión monótona: nunca se sustituye la proyección por un estado más antiguo.
                        repository.save(ItemMapper.applyRemote(local.get(), r));
                        updated[0]++;
                    }
                });
            }
        });

        // Un ítem CONFIRMED ausente de la lista pudo borrarse en el Producer, o haberse desplazado
        // entre páginas mientras se recorría. Se confirma con una consulta puntual antes de borrar,
        // y se ignoran los modificados tras iniciar el recorrido (p. ej. recién creados).
        int deleted = 0;
        for (String id : repository.findIdsByStatusUpdatedBefore(SyncStatus.CONFIRMED, fetchStartedAt)) {
            if (!remoteIds.contains(id) && client.fetch(id).isEmpty()) {
                Boolean removed = tx.execute(s -> repository.findById(id)
                        .filter(i -> i.getSyncStatus() == SyncStatus.CONFIRMED)
                        .map(i -> {
                            repository.delete(i);
                            return true;
                        })
                        .orElse(false));
                if (Boolean.TRUE.equals(removed)) {
                    deleted++;
                }
            }
        }
        metrics.reconciled(created[0], updated[0], deleted);
        return new ReconcileResult(created[0], updated[0], deleted);
    }

    /** Descarta el cambio local de un ítem FAILED, retira sus eventos del outbox y recupera el Producer. */
    public ItemView resync(String id) {
        requireFailed(repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ítem no encontrado")));
        Optional<ProducerItem> remote;
        try {
            remote = client.fetch(id);
        } catch (RestClientException | IllegalStateException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Producer no disponible");
        }
        return tx.execute(s -> {
            Item local = requireFailed(repository.findById(id)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ítem no encontrado")));
            outbox.deleteByItemId(id);
            if (remote.isEmpty()) {
                repository.delete(local);
                return null;
            }
            return ItemView.from(repository.save(ItemMapper.applyRemote(local, remote.get())));
        });
    }

    private static Item requireFailed(Item item) {
        if (item.getSyncStatus() != SyncStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "Solo se pueden resincronizar ítems con sincronización fallida");
        }
        return item;
    }
}
