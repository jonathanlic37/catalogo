// consumer-api/src/main/java/com/catalogo/consumer/repository/ItemRepository.java
package com.catalogo.consumer.repository;

import com.catalogo.consumer.model.Estado;
import com.catalogo.consumer.model.Item;
import com.catalogo.consumer.model.SyncStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, String>, JpaSpecificationExecutor<Item> {

    List<Item> findBySyncStatusAndNextRetryAtLessThanEqual(SyncStatus status, Instant now);

    long countByEstado(Estado estado);

    long countBySyncStatus(SyncStatus status);

    /** Ids (sin cargar entidades) de ítems confirmados y no modificados desde {@code before}. */
    @Query("select i.id from Item i where i.syncStatus = :status and i.fechaActualizacion < :before")
    List<String> findIdsByStatusUpdatedBefore(@Param("status") SyncStatus status, @Param("before") Instant before);
}
