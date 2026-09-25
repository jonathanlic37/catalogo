// consumer-api/src/test/java/com/catalogo/consumer/SyncProducerWinsTest.java
package com.catalogo.consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.time.Duration;
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

/**
 * Escenarios «Producer no disponible» y «el registro cambió en el Producer» con la política
 * PRODUCER_WINS. SYNC_MAX_RETRIES=2 a propósito: los fallos transitorios deben ignorar ese tope.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SyncProducerWinsTest {

    private static final String WEBHOOK = "/webhooks/catalogo";

    static final WireMockServer producer = new WireMockServer(options().dynamicPort().globalTemplating(true));

    static {
        producer.start();
    }

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + tmp.resolve("producer-wins.db"));
        r.add("app.producer.base-url", () -> "http://localhost:" + producer.port());
        r.add("app.producer.token", () -> "producer-token-0123456789-abcdefghijklmnopqrstuvwxyz");
        r.add("app.oidc.issuer", () -> "http://localhost:8095/realms/catalogo");
        r.add("app.oidc.jwk-set-uri", () -> "http://localhost:1/jwks");
        r.add("app.cors.allowed-origins", () -> "http://localhost:8088");
        r.add("app.sync.retry-interval-ms", () -> "100");
        r.add("app.sync.max-retries", () -> "2");
        r.add("app.sync.reconcile-interval-ms", () -> "3600000");
        r.add("app.sync.conflict-policy", () -> "PRODUCER_WINS");
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

    /** Respuesta canónica del Producer: refleja el payload recibido con la versión indicada. */
    private static String canonical(int version) {
        return "{\"id\":\"{{jsonPath request.body '$.id'}}\",\"nombre\":\"{{jsonPath request.body '$.nombre'}}\","
                + "\"estado\":\"ACTIVO\",\"tipo\":\"PRODUCTO\",\"fechaCreacion\":\"2026-01-01T00:00:00Z\","
                + "\"fechaActualizacion\":\"2026-01-01T00:00:00Z\",\"version\":" + version + "}";
    }

    private void stubCreatedOk() {
        producer.stubFor(WireMock.post(urlEqualTo(WEBHOOK)).withHeader("X-Event-Type", equalTo("CREATED"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody(canonical(1))));
    }

    private String create(String nombre) throws Exception {
        String json = mvc.perform(post("/api/items").with(user()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"%s\",\"estado\":\"ACTIVO\"}".formatted(nombre)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return JsonPath.read(json, "$.id");
    }

    private String view(String id) throws Exception {
        return mvc.perform(get("/api/items/" + id).with(user())).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void awaitStatus(String id, String syncStatus) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat((String) JsonPath.read(view(id), "$.syncStatus")).isEqualTo(syncStatus));
    }

    @Test
    void conElProducerCaidoSigueReintentandoSinLimiteYSeConfirmaAlVolver() throws Exception {
        producer.stubFor(WireMock.post(urlEqualTo(WEBHOOK)).willReturn(aResponse().withStatus(503)));
        String id = create("Sin Producer");

        // Supera con holgura SYNC_MAX_RETRIES=2 y sigue PENDING: un fallo transitorio nunca es definitivo.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(producer.findAll(postRequestedFor(urlEqualTo(WEBHOOK)))).hasSizeGreaterThanOrEqualTo(4));
        String json = view(id);
        assertThat((String) JsonPath.read(json, "$.syncStatus")).isEqualTo("PENDING");
        assertThat((String) JsonPath.read(json, "$.syncError")).contains("503");
        assertThat((Integer) JsonPath.read(json, "$.syncAttempts")).isGreaterThanOrEqualTo(3);
        assertThat(json).doesNotContain("failureReason");

        // El Producer vuelve: se confirma solo, sin que el usuario pulse «Reintentar».
        producer.resetMappings();
        stubCreatedOk();
        awaitStatus(id, "CONFIRMED");
    }

    @Test
    void producerWinsAdoptaLaVersionDelProducerAnteUnConflicto() throws Exception {
        stubCreatedOk();
        String id = create("Local");
        awaitStatus(id, "CONFIRMED");

        // Otro escritor cambió el ítem en el Producer (v3): el UPDATED basado en v1 recibe 409.
        producer.stubFor(WireMock.post(urlEqualTo(WEBHOOK)).withHeader("X-Event-Type", equalTo("UPDATED"))
                .willReturn(aResponse().withStatus(409)));
        producer.stubFor(WireMock.get(urlPathEqualTo("/api/items/" + id)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(("{\"id\":\"%s\",\"nombre\":\"Versión del Producer\",\"estado\":\"INACTIVO\","
                        + "\"tipo\":\"PRODUCTO\",\"fechaCreacion\":\"2026-01-01T00:00:00Z\","
                        + "\"fechaActualizacion\":\"2026-02-01T00:00:00Z\",\"version\":3}").formatted(id))));

        mvc.perform(put("/api/items/" + id).with(user()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Edición local\",\"estado\":\"ACTIVO\"}"))
                .andExpect(status().isAccepted());

        awaitStatus(id, "CONFIRMED");
        String json = view(id);
        assertThat((String) JsonPath.read(json, "$.nombre")).isEqualTo("Versión del Producer");
        assertThat((Integer) JsonPath.read(json, "$.version")).isEqualTo(3);
        assertThat((String) JsonPath.read(json, "$.estado")).isEqualTo("INACTIVO");
    }
}
