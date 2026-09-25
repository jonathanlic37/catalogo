// consumer-api/src/main/java/com/catalogo/consumer/dto/ProducerPage.java
package com.catalogo.consumer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Página devuelta por la lista del Producer. Solo se usan contenido, número y si es la última. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProducerPage(List<ProducerItem> content, int page, boolean last) {
}
