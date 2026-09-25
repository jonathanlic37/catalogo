// producer-api/src/main/java/com/catalogo/producer/repository/ItemRepository.java
package com.catalogo.producer.repository;

import com.catalogo.producer.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, String> {
}
