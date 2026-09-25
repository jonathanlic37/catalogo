// consumer-api/src/main/java/com/catalogo/consumer/maintenance/OutboxPurger.java
package com.catalogo.consumer.maintenance;

import com.catalogo.consumer.model.OutboxStatus;
import com.catalogo.consumer.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Retira del outbox los eventos ya enviados, para que la tabla no crezca sin límite. */
@Component
public class OutboxPurger {

    private static final Logger log = LoggerFactory.getLogger(OutboxPurger.class);

    private final OutboxEventRepository outbox;
    private final int retentionDays;

    public OutboxPurger(OutboxEventRepository outbox,
                        @Value("${app.sync.outbox-retention-days:7}") int retentionDays) {
        this.outbox = outbox;
        this.retentionDays = retentionDays;
    }

    @Transactional
    public long purge() {
        Instant before = Instant.now().minus(Duration.ofDays(retentionDays));
        return outbox.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, before);
    }

    @Scheduled(fixedDelayString = "${app.sync.outbox-purge-interval-ms:3600000}")
    public void scheduled() {
        long purged = purge();
        if (purged > 0) {
            log.info("Purgados {} eventos SENT con más de {} días", purged, retentionDays);
        }
    }
}
