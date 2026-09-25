// consumer-api/src/main/java/com/catalogo/consumer/dto/ProducerPage.java
package com.catalogo.consumer.dto;

import java.util.List;

/** Página devuelta por la lista del Producer. */
public record ProducerPage(List<ProducerItem> content, int page, boolean last) {
}
