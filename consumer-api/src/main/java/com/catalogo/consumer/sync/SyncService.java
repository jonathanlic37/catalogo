// consumer-api/src/main/java/com/catalogo/consumer/sync/SyncService.java
package com.catalogo.consumer.sync;

import com.catalogo.consumer.dto.ProducerItem;
import com.catalogo.consumer.dto.WebhookPayload;
import com.catalogo.consumer.model.EventType;
import com.catalogo.consumer.model.FailureReason;
import com.catalogo.consumer.model.Item;
import com.catalogo.consumer.model.OutboxEvent;
import com.catalogo.consumer.model.OutboxStatus;
import com.catalogo.consumer.model.SyncStatus;
import com.catalogo.consumer.repository.ItemRepository;
import com.catalogo.consumer.repository.OutboxEventRepository;
import com.catalogo.consumer.service.ItemMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClientException;

/**
 * Relay del outbox transaccional: envía al Producer los eventos PENDING y deja constancia del
 * resultado. No mantiene transacciones abiertas durante la llamada HTTP (el pool de SQLite es de
 * 1 conexión); tras la respuesta, el estado del evento y la proyección del ítem se escriben en una
 * única transacción. Cada reintento reutiliza la misma Idempotency-Key, por lo que un evento ya
 * aplicado por el Producer no se duplica (p. ej. si el Consumer se reinicia tras enviarlo).
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final ItemRepository repository;
    private final OutboxEventRepository outbox;
    private final ProducerWebhookClient client;
    private final ObjectMapper mapper;
    private final SyncMetrics metrics;
    private final int maxRetries;
    private final long retryIntervalMs;
    private final ConflictPolicy conflictPolicy;
    private final TransactionTemplate tx;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public SyncService(ItemRepository repository, OutboxEventRepository outbox, ProducerWebhookClient client,
                       ObjectMapper mapper, SyncMetrics metrics, PlatformTransactionManager txManager,
                       @Value("${app.sync.max-retries}") int maxRetries,
                       @Value("${app.sync.retry-interval-ms}") long retryIntervalMs,
                       @Value("${app.sync.conflict-policy:MANUAL}") ConflictPolicy conflictPolicy) {
        this.tx = new TransactionTemplate(txManager);
        this.repository = repository;
        this.outbox = outbox;
        this.client = client;
        this.mapper = mapper;
        this.metrics = metrics;
        this.maxRetries = maxRetries;
        this.retryIntervalMs = retryIntervalMs;
        this.conflictPolicy = conflictPolicy;
    }

    /** Se dispara tras confirmar la transacción que encoló el evento. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSyncRequested(SyncRequested event) {
        relayItem(event.itemId());
    }

    @Scheduled(fixedDelayString = "${app.sync.retry-interval-ms}")
    public void relayPending() {
        for (OutboxEvent event : outbox.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                OutboxStatus.PENDING, Instant.now())) {
            // Si el Producer no responde, el resto fallaría igual: se corta para no bloquear el hilo
            // del planificador con un timeout por evento y se reintenta en el siguiente ciclo.
            if (!relay(event)) {
                log.warn("Producer no disponible: se aplaza el resto del outbox");
                break;
            }
        }
    }

    /** Envía el evento PENDING más antiguo del ítem (si lo hay). @return false si el Producer no está disponible. */
    public boolean relayItem(String itemId) {
        Optional<OutboxEvent> pending = outbox
                .findByItemIdAndStatusIn(itemId, List.of(OutboxStatus.PENDING)).stream()
                .min(Comparator.comparing(OutboxEvent::getCreatedAt));
        return pending.map(this::relay).orElse(true);
    }

    private boolean relay(OutboxEvent event) {
        if (!inFlight.add(event.getId())) {
            return true;
        }
        try {
            return doRelay(event);
        } catch (RuntimeException e) {
            log.error("Fallo inesperado sincronizando el evento {}", event.getId(), e);
            return true;
        } finally {
            inFlight.remove(event.getId());
        }
    }

    private boolean doRelay(OutboxEvent event) {
        // Recarga el estado actual: otra vía (relay inmediato o programado) pudo haberlo enviado ya.
        OutboxEvent fresh = outbox.findById(event.getId()).orElse(null);
        if (fresh == null || fresh.getStatus() != OutboxStatus.PENDING) {
            return true;
        }
        event = fresh;

        Item item = repository.findById(event.getItemId()).orElse(null);
        if (item == null && event.getEventType() != EventType.DELETED) {
            // El ítem ya no existe localmente (p. ej. se descartó): el evento es irrelevante.
            markSent(event);
            return true;
        }
        WebhookPayload payload = read(event.getPayload());

        ProducerWebhookClient.WebhookResult result;
        metrics.attempt();
        try {
            result = client.send(event.getId(), event.getEventType(), payload);
        } catch (RestClientException e) {
            metrics.retry();
            scheduleRetry(event, item, "Producer no disponible");
            return false;
        }

        if (result.isSuccess() && result.body() != null) {
            metrics.success();
            OutboxEvent sent = event;
            // Evento SENT y proyección confirmada en la misma transacción: si el proceso cae a mitad,
            // o se hace todo o nada (el evento sigue PENDING y se reenvía con la misma clave).
            tx.executeWithoutResult(s -> {
                markSent(sent);
                if (sent.getEventType() == EventType.DELETED) {
                    repository.deleteById(sent.getItemId());
                } else {
                    repository.save(ItemMapper.applyRemote(item, result.body()));
                }
            });
            log.info("Evento {} confirmado por el Producer ({})", event.getId(), event.getEventType());
        } else if (event.getEventType() == EventType.DELETED && result.status() == 404) {
            // Borrar lo que ya no existe es el estado deseado: el borrado es idempotente.
            metrics.success();
            OutboxEvent sent = event;
            tx.executeWithoutResult(s -> {
                markSent(sent);
                repository.deleteById(sent.getItemId());
            });
            log.info("Ítem {} ya no existía en el Producer; eliminado de la réplica", event.getItemId());
        } else if (result.status() == 409 && conflictPolicy != ConflictPolicy.MANUAL) {
            metrics.conflict();
            resolveConflict(event, item, payload);
        } else if (isTransient(result)) {
            metrics.retry();
            scheduleRetry(event, item, "Error del Producer (HTTP " + result.status() + ")");
            return false;
        } else {
            // 4xx definitivo: el Producer rechazó el cambio; reenviar el mismo contenido no lo arregla.
            metrics.failure();
            fail(event, item, rejectionMessage(result.status()), reasonFor(result.status()));
        }
        return true;
    }

    /**
     * Fallo transitorio: el Producer no pudo procesarlo ahora (5xx, timeout 408, 425, límite 429, o un
     * 2xx sin cuerpo). Se reintenta indefinidamente con backoff; nunca se da por fallido por sí solo.
     */
    private static boolean isTransient(ProducerWebhookClient.WebhookResult result) {
        int s = result.status();
        return s >= 500 || s == 408 || s == 425 || s == 429 || result.isSuccess();
    }

    private static FailureReason reasonFor(int status) {
        return switch (status) {
            case 409 -> FailureReason.CONFLICT;
            case 404 -> FailureReason.GONE;
            default -> FailureReason.REJECTED;
        };
    }

    /** Resuelve un conflicto de versión según la política configurada. */
    private void resolveConflict(OutboxEvent event, Item item, WebhookPayload payload) {
        if (conflictPolicy == ConflictPolicy.PRODUCER_WINS) {
            // La consulta HTTP va fuera de la transacción; la escritura, en una sola.
            Optional<ProducerItem> remote = item == null ? Optional.empty() : client.fetch(event.getItemId());
            tx.executeWithoutResult(s -> {
                markSent(event);
                if (item != null) {
                    if (remote.isEmpty()) {
                        repository.deleteById(event.getItemId());
                    } else {
                        repository.save(ItemMapper.applyRemote(item, remote.get()));
                    }
                }
            });
            log.warn("Conflicto resuelto con PRODUCER_WINS para {}", event.getItemId());
            return;
        }
        // CONSUMER_WINS: rebasa el cambio local sobre la versión actual del Producer y reintenta.
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        if (attempts >= maxRetries) {
            // Conflictos repetidos (otro escritor cambia el ítem una y otra vez): se deja al usuario.
            metrics.failure();
            fail(event, item, rejectionMessage(409), FailureReason.CONFLICT);
            return;
        }
        Optional<ProducerItem> remote = client.fetch(event.getItemId());
        if (remote.isEmpty()) {
            metrics.failure();
            fail(event, item, rejectionMessage(404), FailureReason.GONE);
            return;
        }
        WebhookPayload rebased = new WebhookPayload(payload.id(), payload.nombre(), payload.descripcion(),
                payload.estado(), payload.tipo(), remote.get().version(), payload.occurredAt());
        event.setPayload(write(rebased));
        event.setStatus(OutboxStatus.PENDING);
        event.setNextAttemptAt(Instant.now());
        event.setLastError("Rebasado sobre la versión " + remote.get().version() + " del Producer");
        tx.executeWithoutResult(s -> outbox.save(event));
        log.warn("Conflicto rebasado (CONSUMER_WINS) para {} sobre la versión {}", event.getItemId(),
                remote.get().version());
    }

    private static String rejectionMessage(int status) {
        return switch (status) {
            case 404 -> "El ítem ya no existe en el Producer; descarte el cambio";
            case 409 -> "Conflicto: el ítem cambió en el Producer; descarte el cambio y vuelva a editar";
            case 401, 403 -> "El Producer rechazó las credenciales del servicio (HTTP " + status + ")";
            default -> "El Producer rechazó el cambio (HTTP " + status + ")";
        };
    }

    private void scheduleRetry(OutboxEvent event, Item item, String error) {
        tx.executeWithoutResult(s -> doScheduleRetry(event, item, error));
    }

    /**
     * Fallo transitorio: el evento sigue PENDING y se reprograma con backoff exponencial
     * (SYNC_RETRY_INTERVAL_MS × 2ⁿ, tope de 5 min). No se da por fallido: cuando el Producer vuelva,
     * el cambio se confirma solo, sin que el usuario tenga que reintentar ítem por ítem.
     */
    private void doScheduleRetry(OutboxEvent event, Item item, String error) {
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        event.setLastError(error);
        Duration backoff = Duration.ofMillis(retryIntervalMs).multipliedBy(1L << Math.min(attempts - 1, 10));
        if (backoff.compareTo(MAX_BACKOFF) > 0) {
            backoff = MAX_BACKOFF;
        }
        event.setNextAttemptAt(Instant.now().plus(backoff));
        outbox.save(event);
        mirrorItem(item, attempts, error, event.getNextAttemptAt(), null);
        log.warn("Evento {} pendiente, intento {} fallido ({}); siguiente en {} s",
                event.getId(), attempts, error, backoff.toSeconds());
    }

    /** Evento FAILED y estado del ítem en la misma transacción (queda registrado para reintento/auditoría). */
    private void fail(OutboxEvent event, Item item, String error, FailureReason reason) {
        tx.executeWithoutResult(s -> {
            event.setStatus(OutboxStatus.FAILED);
            event.setLastError(error);
            event.setNextAttemptAt(null);
            outbox.save(event);
            mirrorItem(item, item == null ? 0 : item.getSyncAttempts(), error, null, reason);
        });
        log.warn("Evento {} marcado FAILED ({}): {}", event.getId(), reason, error);
    }

    /** Mantiene el estado de sincronización del ítem (contrato de la UI) alineado con el outbox. */
    private void mirrorItem(Item item, int attempts, String error, Instant nextRetryAt, FailureReason reason) {
        if (item == null) {
            return;
        }
        item.setSyncAttempts(attempts);
        item.setLastSyncError(error);
        item.setNextRetryAt(nextRetryAt);
        item.setSyncStatus(nextRetryAt == null ? SyncStatus.FAILED : SyncStatus.PENDING);
        item.setFailureReason(reason);
        repository.save(item);
    }

    private void markSent(OutboxEvent event) {
        event.setStatus(OutboxStatus.SENT);
        event.setSentAt(Instant.now());
        event.setNextAttemptAt(null);
        outbox.save(event);
    }

    private WebhookPayload read(String json) {
        try {
            return mapper.readValue(json, WebhookPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Evento de outbox corrupto", e);
        }
    }

    private String write(WebhookPayload payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el evento", e);
        }
    }
}
