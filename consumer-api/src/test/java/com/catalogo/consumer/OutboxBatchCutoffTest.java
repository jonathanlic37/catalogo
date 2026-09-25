// consumer-api/src/test/java/com/catalogo/consumer/OutboxBatchCutoffTest.java
package com.catalogo.consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.catalogo.consumer.model.Estado;
import com.catalogo.consumer.model.EventType;
import com.catalogo.consumer.model.Item;
import com.catalogo.consumer.model.OutboxEvent;
import com.catalogo.consumer.model.SyncStatus;
import com.catalogo.consumer.model.TipoContenido;
import com.catalogo.consumer.repository.ItemRepository;
import com.catalogo.consumer.repository.OutboxEventRepository;
import com.catalogo.consumer.sync.SyncService;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Verifica que, con el Producer caído, el relay corta el lote en vez de agotar un timeout por evento. */
@SpringBootTest
class OutboxBatchCutoffTest {

    private static final String WEBHOOK = "/webhooks/catalogo";

    static final WireMockServer producer = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        producer.start();
    }

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + tmp.resolve("cutoff.db"));
        r.add("app.producer.base-url", () -> "http://localhost:" + producer.port());
        r.add("app.producer.token", () -> "producer-token-0123456789-abcdefghijklmnopqrstuvwxyz");
        r.add("app.security.frontend-token", () -> "front-token-0123456789-abcdefghijklmnopqrstuvwxyz");
        r.add("app.cors.allowed-origins", () -> "http://localhost:8088");
        // Intervalos altos: el relay solo se dispara cuando el test lo invoca.
        r.add("app.sync.retry-interval-ms", () -> "3600000");
        r.add("app.sync.reconcile-interval-ms", () -> "3600000");
    }

    @AfterAll
    static void stop() {
        producer.stop();
    }

    @Autowired SyncService sync;
    @Autowired ItemRepository items;
    @Autowired OutboxEventRepository outbox;

    @BeforeEach
    void reset() {
        producer.resetAll();
    }

    @Test
    void cortaElLoteAlPrimerFalloDelProducer() {
        producer.stubFor(post(urlEqualTo(WEBHOOK)).willReturn(aResponse().withStatus(503)));

        for (int i = 0; i < 3; i++) {
            Instant now = Instant.now();
            Item item = new Item();
            item.setId(UUID.randomUUID().toString());
            item.setNombre("lote-" + i);
            item.setEstado(Estado.ACTIVO);
            item.setTipo(TipoContenido.PRODUCTO);
            item.setFechaCreacion(now);
            item.setFechaActualizacion(now);
            item.setVersion(0);
            item.setSyncStatus(SyncStatus.PENDING);
            item.setPendingEvent(EventType.CREATED);
            item.setIdempotencyKey(UUID.randomUUID().toString());
            item.setSyncAttempts(0);
            item.setNextRetryAt(now);
            items.save(item);
            outbox.save(new OutboxEvent(item.getIdempotencyKey(), item.getId(), EventType.CREATED, "{}", now));
        }

        sync.relayPending();

        assertThat(producer.findAll(postRequestedFor(urlEqualTo(WEBHOOK))))
                .as("con el Producer caído solo se intenta el primer evento del lote")
                .hasSize(1);
    }
}
