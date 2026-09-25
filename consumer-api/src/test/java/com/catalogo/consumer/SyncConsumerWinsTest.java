// consumer-api/src/test/java/com/catalogo/consumer/SyncConsumerWinsTest.java
package com.catalogo.consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Escenario «el registro cambió en el Producer» con la política CONSUMER_WINS (rebase y reintento). */
@SpringBootTest
@AutoConfigureMockMvc
class SyncConsumerWinsTest {

    private static final String WEBHOOK = "/webhooks/catalogo";

    static final WireMockServer producer = new WireMockServer(options().dynamicPort().globalTemplating(true));

    static {
        producer.start();
    }

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + tmp.resolve("consumer-wins.db"));
        r.add("app.producer.base-url", () -> "http://localhost:" + producer.port());
        r.add("app.producer.token", () -> "producer-token-0123456789-abcdefghijklmnopqrstuvwxyz");
        r.add("app.oidc.issuer", () -> "http://localhost:8095/realms/catalogo");
        r.add("app.oidc.jwk-set-uri", () -> "http://localhost:1/jwks");
        r.add("app.cors.allowed-origins", () -> "http://localhost:8088");
        r.add("app.sync.retry-interval-ms", () -> "100");
        r.add("app.sync.max-retries", () -> "3");
        r.add("app.sync.reconcile-interval-ms", () -> "3600000");
        r.add("app.sync.conflict-policy", () -> "CONSUMER_WINS");
    }

    @AfterAll
    static void stop() {
        producer.stop();
    }

    @Autowired MockMvc mvc;

    @BeforeEach
    void reset() {
        producer.resetAll();
    }

    private static RequestPostProcessor user() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_user"));
    }

    private static String canonical(int version) {
        return "{\"id\":\"{{jsonPath request.body '$.id'}}\",\"nombre\":\"{{jsonPath request.body '$.nombre'}}\","
                + "\"estado\":\"ACTIVO\",\"tipo\":\"PRODUCTO\",\"fechaCreacion\":\"2026-01-01T00:00:00Z\","
                + "\"fechaActualizacion\":\"2026-01-01T00:00:00Z\",\"version\":" + version + "}";
    }

    private String view(String id) throws Exception {
        return mvc.perform(get("/api/items/" + id).with(user())).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void awaitStatus(String id, String syncStatus) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat((String) JsonPath.read(view(id), "$.syncStatus")).isEqualTo(syncStatus));
    }

    /** Crea y confirma un ítem (v1) y deja el Producer con ese ítem ya en la versión 3. */
    private String itemConfirmadoQueLuegoCambiaEnElProducer(String nombre) throws Exception {
        producer.stubFor(WireMock.post(urlEqualTo(WEBHOOK)).withHeader("X-Event-Type", equalTo("CREATED"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody(canonical(1))));
        String json = mvc.perform(post("/api/items").with(user()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"%s\",\"estado\":\"ACTIVO\"}".formatted(nombre)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String id = JsonPath.read(json, "$.id");
        awaitStatus(id, "CONFIRMED");
        producer.stubFor(WireMock.get(urlPathEqualTo("/api/items/" + id)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(("{\"id\":\"%s\",\"nombre\":\"Remoto\",\"estado\":\"ACTIVO\",\"tipo\":\"PRODUCTO\","
                        + "\"fechaCreacion\":\"2026-01-01T00:00:00Z\",\"fechaActualizacion\":\"2026-02-01T00:00:00Z\","
                        + "\"version\":3}").formatted(id))));
        return id;
    }

    private void editar(String id, String nombre) throws Exception {
        mvc.perform(put("/api/items/" + id).with(user()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"%s\",\"estado\":\"ACTIVO\"}".formatted(nombre)))
                .andExpect(status().isAccepted());
    }

    @Test
    void consumerWinsRebasaElCambioSobreLaVersionActualYLoConfirma() throws Exception {
        String id = itemConfirmadoQueLuegoCambiaEnElProducer("Local");

        // Primer UPDATED (basado en v1) → 409; el reenvío rebasado sobre v3 → aceptado (v4).
        producer.stubFor(WireMock.post(urlEqualTo(WEBHOOK)).withHeader("X-Event-Type", equalTo("UPDATED"))
                .inScenario("rebase").whenScenarioStateIs(STARTED).willSetStateTo("rebasado")
                .willReturn(aResponse().withStatus(409)));
        producer.stubFor(WireMock.post(urlEqualTo(WEBHOOK)).withHeader("X-Event-Type", equalTo("UPDATED"))
                .inScenario("rebase").whenScenarioStateIs("rebasado")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(canonical(4))));

        editar(id, "Gana el Consumer");
        awaitStatus(id, "CONFIRMED");

        String json = view(id);
        assertThat((String) JsonPath.read(json, "$.nombre")).isEqualTo("Gana el Consumer");
        assertThat((Integer) JsonPath.read(json, "$.version")).isEqualTo(4);

        List<LoggedRequest> updates = producer.findAll(postRequestedFor(urlEqualTo(WEBHOOK))
                .withHeader("X-Event-Type", equalTo("UPDATED")));
        assertThat(updates).hasSize(2);
        assertThat(updates.get(0).getBodyAsString()).contains("\"baseVersion\":1");
        assertThat(updates.get(1).getBodyAsString()).contains("\"baseVersion\":3");
        Set<String> keys = updates.stream().map(r -> r.getHeader("Idempotency-Key")).collect(Collectors.toSet());
        assertThat(keys).as("el rebase es el mismo evento del outbox: misma Idempotency-Key").hasSize(1);
    }

    @Test
    void conflictosRepetidosAcabanEnErrorDeConflictoTrasElTope() throws Exception {
        String id = itemConfirmadoQueLuegoCambiaEnElProducer("Disputado");
        producer.stubFor(WireMock.post(urlEqualTo(WEBHOOK)).withHeader("X-Event-Type", equalTo("UPDATED"))
                .willReturn(aResponse().withStatus(409)));

        editar(id, "Nunca entra");
        awaitStatus(id, "FAILED");

        String json = view(id);
        assertThat((String) JsonPath.read(json, "$.failureReason")).isEqualTo("CONFLICT");
        assertThat((String) JsonPath.read(json, "$.syncError")).contains("Conflicto");
        assertThat(producer.findAll(postRequestedFor(urlEqualTo(WEBHOOK)).withRequestBody(containing(id))
                .withHeader("X-Event-Type", equalTo("UPDATED")))).hasSize(3); // SYNC_MAX_RETRIES=3
    }
}
