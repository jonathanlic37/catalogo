// consumer-api/src/main/java/com/catalogo/consumer/repository/OutboxEventRepository.java
package com.catalogo.consumer.repository;

import com.catalogo.consumer.model.OutboxEvent;
import com.catalogo.consumer.model.OutboxStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    List<OutboxEvent> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(OutboxStatus status, Instant now);

    List<OutboxEvent> findByItemIdAndStatusIn(String itemId, Collection<OutboxStatus> statuses);

    void deleteByItemId(String itemId);

    long deleteByStatusAndSentAtBefore(OutboxStatus status, Instant before);

    long countByStatus(OutboxStatus status);
}
