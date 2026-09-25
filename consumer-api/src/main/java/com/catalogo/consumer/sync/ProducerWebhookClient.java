// consumer-api/src/main/java/com/catalogo/consumer/sync/ProducerWebhookClient.java
package com.catalogo.consumer.sync;

import com.catalogo.consumer.dto.ProducerItem;
import com.catalogo.consumer.dto.ProducerPage;
import com.catalogo.consumer.dto.WebhookPayload;
import com.catalogo.consumer.model.EventType;
import com.catalogo.consumer.security.TokenValidator;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Cliente HTTP hacia la Producer API (fuente de verdad). */
@Component
public class ProducerWebhookClient {

    /** Respuesta del webhook; {@code body} es null cuando el estado no es 2xx. */
    public record WebhookResult(int status, ProducerItem body) {
        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    private static final int PAGE_SIZE = 500;

    private final RestClient client;

    public ProducerWebhookClient(RestClient.Builder builder,
                                 @Value("${app.producer.base-url}") String baseUrl,
                                 @Value("${app.producer.token}") String token,
                                 @Value("${app.producer.connect-timeout-ms}") long connectTimeoutMs,
                                 @Value("${app.producer.read-timeout-ms}") long readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.client = builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TokenValidator.requireStrong("CONSUMER_TO_PRODUCER_TOKEN", token))
                .build();
    }

    /** Envía el webhook. Las respuestas 4xx/5xx se devuelven como resultado; los fallos de red lanzan excepción. */
    public WebhookResult send(String idempotencyKey, EventType type, WebhookPayload payload) {
        return client.post()
                .uri("/webhooks/catalogo")
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Event-Type", type.name())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    ProducerItem body = response.getStatusCode().is2xxSuccessful()
                            ? response.bodyTo(ProducerItem.class) : null;
                    return new WebhookResult(status, body);
                });
    }

    /** Recorre la lista del Producer página a página (memoria acotada), entregando cada página al handler. */
    public void forEachPage(Consumer<List<ProducerItem>> handler) {
        int page = 0;
        ProducerPage current;
        do {
            current = client.get().uri("/api/items?page={page}&size={size}", page, PAGE_SIZE).retrieve()
                    .body(ProducerPage.class);
            if (current == null) {
                throw new IllegalStateException("Respuesta vacía del Producer");
            }
            handler.accept(current.content());
            page++;
        } while (!current.last());
    }

    public Optional<ProducerItem> fetch(String id) {
        return client.get().uri("/api/items/{id}", id).exchange((request, response) -> {
            if (response.getStatusCode().value() == 404) {
                return Optional.<ProducerItem>empty();
            }
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("HTTP " + response.getStatusCode().value());
            }
            return Optional.ofNullable(response.bodyTo(ProducerItem.class));
        });
    }
}
