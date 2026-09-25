// producer-api/src/main/java/com/catalogo/producer/maintenance/IdempotencyPurger.java
package com.catalogo.producer.maintenance;

import com.catalogo.producer.repository.IdempotencyRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Elimina registros de idempotencia antiguos para que la tabla no crezca sin límite. La retención
 * (7 días por defecto) es muy superior a la ventana de reintentos del Consumer (minutos).
 */
@Component
public class IdempotencyPurger {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurger.class);

    private final IdempotencyRepository repository;
    private final long retentionDays;

    public IdempotencyPurger(IdempotencyRepository repository,
                             @Value("${app.idempotency.retention-days}") long retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    @Transactional
    public int purge() {
        int deleted = repository.deleteOlderThan(Instant.now().minus(Duration.ofDays(retentionDays)));
        if (deleted > 0) {
            log.info("Purgados {} registros de idempotencia con más de {} días", deleted, retentionDays);
        }
        return deleted;
    }
}
