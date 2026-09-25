// consumer-api/src/test/java/com/catalogo/consumer/SyncServiceWireMockTest.java
package com.catalogo.consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catalogo.consumer.maintenance.OutboxPurger;
import com.catalogo.consumer.model.EventType;
import com.catalogo.consumer.model.OutboxEvent;
import com.catalogo.consumer.model.OutboxStatus;
import com.catalogo.consumer.repository.OutboxEventRepository;
import com.catalogo.consumer.security.TokenValidator;
import com.catalogo.consumer.sync.ReconcileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Valida el flujo Consumer → webhook → Producer (simulado con WireMock). */
@SpringBootTest
@AutoConfigureMockMvc
class SyncServiceWireMockTest {

    private static final String FRONT_TOKEN = "front-token-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final String PRODUCER_TOKEN = "producer-token-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final String WEBHOOK = "/webhooks/catalogo";

    static final WireMockServer producer = new WireMockServer(options().dynamicPort().globalTemplating(true));

    static {
        producer.start();
    }

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + tmp.resolve("consumer-test.db"));
        r.add("app.producer.base-url", () -> "http://localhost:" + producer.port());
        r.add("app.producer.token", () -> PRODUCER_TOKEN);
        r.add("app.security.frontend-token", () -> FRONT_TOKEN);
        r.add("app.cors.allowed-origins", () -> "http://localhost:8088");
        r.add("app.sync.retry-interval-ms", () -> "200");
        r.add("app.sync.max-retries", () -> "8");
        r.add("app.sync.reconcile-interval-ms", () -> "3600000");
    }

    @AfterAll
    static void stop() {
        producer.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired ReconcileService reconcile;
    @Autowired OutboxEventRepository outbox;
    @Autowired OutboxPurger purger;

    @BeforeEach
    void reset() {
        producer.resetAll();
    }

    private StubMapping stubOk() {
        return producer.stubFor(WireMock.post(urlEqualTo(WEBHOOK)).willReturn(aResponse()
                .withStatus(201).withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"{{jsonPath request.body '$.id'}}\",\"nombre\":\"{{jsonPath request.body '$.nombre'}}\","
                        + "\"descripcion\":\"molido\",\"estado\":\"ACTIVO\","
                        + "\"fechaCreacion\":\"2026-01-01T00:00:00Z\",\"fechaActualizacion\":\"2026-01-01T00:00:00Z\","
                        + "\"version\":1}")));
    }

    private void stubStatus(int status) {
        producer.stubFor(WireMock.post(urlEqualTo(WEBHOOK)).willReturn(aResponse().withStatus(status)));
    }

    private String create(String nombre) throws Exception {
        String json = mvc.perform(post("/api/items").header("Authorization", "Bearer " + FRONT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"%s\",\"descripcion\":\"molido\",\"estado\":\"ACTIVO\"}".formatted(nombre)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.syncStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String create() throws Exception {
        return create("Cafe");
    }

    private String syncStatus(String id) throws Exception {
        String json = mvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.syncStatus");
    }

    private void awaitConfirmed(String id) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(syncStatus(id)).isEqualTo("CONFIRMED"));
    }

    private void awaitGone(String id) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + FRONT_TOKEN))
                        .andExpect(status().isNotFound()));
    }

    @Test
    void crearEnviaWebhookAlProducerYQuedaConfirmado() throws Exception {
        stubOk();

        String id = create();
        awaitConfirmed(id);

        producer.verify(1, postRequestedFor(urlEqualTo(WEBHOOK))
                .withHeader("Authorization", equalTo("Bearer " + PRODUCER_TOKEN))
                .withHeader("Idempotency-Key", matching(".+"))
                .withHeader("X-Event-Type", equalTo("CREATED"))
                .withRequestBody(containing(id)));

        mvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.pendingOperation").doesNotExist());
    }

    @Test
    void editarUnItemConfirmadoEnviaUpdatedConLaVersionBase() throws Exception {
        stubOk();
        String id = create();
        awaitConfirmed(id);

        mvc.perform(put("/api/items/" + id).header("Authorization", "Bearer " + FRONT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Cafe 2\",\"estado\":\"INACTIVO\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pendingOperation").value("UPDATED"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                producer.verify(1, postRequestedFor(urlEqualTo(WEBHOOK))
                        .withHeader("X-Event-Type", equalTo("UPDATED"))
                        .withRequestBody(containing("\"baseVersion\":1"))));
    }

    @Test
    void siElProducerFallaQuedaPendienteYReintentaConLaMismaIdempotencyKey() throws Exception {
        stubStatus(503);

        String id = create();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(producer.findAll(postRequestedFor(urlEqualTo(WEBHOOK))
                        .withRequestBody(containing(id)))).hasSizeGreaterThanOrEqualTo(2));
        assertThat(syncStatus(id)).isEqualTo("PENDING");

        producer.resetMappings();
        stubOk();
        awaitConfirmed(id);

        List<LoggedRequest> sent = producer.findAll(postRequestedFor(urlEqualTo(WEBHOOK))
                .withRequestBody(containing(id)));
        Set<String> keys = sent.stream().map(r -> r.getHeader("Idempotency-Key")).collect(Collectors.toSet());
        assertThat(keys).as("todos los reintentos deben reutilizar la misma Idempotency-Key").hasSize(1);
    }

    @Test
    void rechazoDelProducerMarcaFailedSinReintentar() throws Exception {
        stubStatus(409);

        String id = create();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(syncStatus(id)).isEqualTo("FAILED"));
        assertThat(producer.findAll(postRequestedFor(urlEqualTo(WEBHOOK)).withRequestBody(containing(id)))).hasSize(1);
        mvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(jsonPath("$.syncError").value(org.hamcrest.Matchers.containsString("Conflicto")));
    }

    @Test
    void eliminarUnItemConfirmadoLoQuitaDeLaReplicaTrasConfirmar() throws Exception {
        stubOk();
        String id = create();
        awaitConfirmed(id);

        mvc.perform(delete("/api/items/" + id).header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pendingOperation").value("DELETED"));

        awaitGone(id);
        producer.verify(1, postRequestedFor(urlEqualTo(WEBHOOK)).withHeader("X-Event-Type", equalTo("DELETED")));
    }

    @Test
    void eliminarLoQueYaNoExisteEnElProducerSeTrataComoExito() throws Exception {
        stubOk();
        String id = create();
        awaitConfirmed(id);

        producer.resetMappings();
        stubStatus(404);
        mvc.perform(delete("/api/items/" + id).header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(status().isAccepted());

        awaitGone(id);
    }

    @Test
    void noSePuedeEditarUnItemPendiente() throws Exception {
        stubStatus(503);
        String id = create();

        mvc.perform(put("/api/items/" + id).header("Authorization", "Bearer " + FRONT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"x\",\"estado\":\"ACTIVO\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void listaPaginadaConBusquedaYResumen() throws Exception {
        stubOk();
        String tag = "pag" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 3; i++) {
            awaitConfirmed(create(tag + "-" + i));
        }

        mvc.perform(get("/api/items?q=" + tag + "&size=2").header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.last").value(false));
        mvc.perform(get("/api/items?q=" + tag + "&size=2&page=1").header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.last").value(true));
        // Los comodines de LIKE del usuario se escapan: "%" no equivale a "todo".
        mvc.perform(get("/api/items?q=%25").header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/items?estado=INACTIVO&q=" + tag).header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/items?size=100000").header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(jsonPath("$.size").value(100));

        mvc.perform(get("/api/items/summary").header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.pendientes").isNumber())
                .andExpect(jsonPath("$.fallidos").isNumber());
    }

    @Test
    void reconciliacionSiembraLaReplicaYRetiraLoBorradoEnElProducer() throws Exception {
        String seeded = "seed-" + UUID.randomUUID();
        producer.stubFor(WireMock.get(urlPathEqualTo("/api/items")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(("{\"content\":[{\"id\":\"%s\",\"nombre\":\"Sembrado\",\"estado\":\"ACTIVO\",\"version\":5,"
                        + "\"fechaCreacion\":\"2026-01-01T00:00:00Z\",\"fechaActualizacion\":\"2026-01-01T00:00:00Z\"}],"
                        + "\"page\":0,\"size\":500,\"totalElements\":1,\"totalPages\":1,\"last\":true}")
                        .formatted(seeded))));

        reconcile.reconcileAll();

        mvc.perform(get("/api/items/" + seeded).header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.version").value(5));

        // Ahora el Producer ya no lo lista y una consulta puntual confirma que no existe: se retira.
        producer.resetMappings();
        producer.stubFor(WireMock.get(urlPathEqualTo("/api/items")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"content\":[],\"page\":0,\"size\":500,\"totalElements\":0,\"totalPages\":0,\"last\":true}")));
        producer.stubFor(WireMock.get(urlPathEqualTo("/api/items/" + seeded)).willReturn(aResponse().withStatus(404)));
        reconcile.reconcileAll();
        mvc.perform(get("/api/items/" + seeded).header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void reconciliacionNoBorraUnItemAusenteDeLaListaSiElProducerAunLoTiene() throws Exception {
        stubOk();
        String id = create();
        awaitConfirmed(id);

        // La lista no lo incluye (p. ej. se desplazó entre páginas) pero la consulta puntual sí lo devuelve.
        producer.stubFor(WireMock.get(urlPathEqualTo("/api/items")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"content\":[],\"page\":0,\"size\":500,\"totalElements\":0,\"totalPages\":0,\"last\":true}")));
        producer.stubFor(WireMock.get(urlPathEqualTo("/api/items/" + id)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(("{\"id\":\"%s\",\"nombre\":\"Cafe\",\"estado\":\"ACTIVO\",\"version\":1,"
                        + "\"fechaCreacion\":\"2026-01-01T00:00:00Z\",\"fechaActualizacion\":\"2026-01-01T00:00:00Z\"}")
                        .formatted(id))));

        reconcile.reconcileAll();

        assertThat(syncStatus(id)).isEqualTo("CONFIRMED");
    }

    @Test
    void validacionSeguridadYErroresEstandar() throws Exception {
        mvc.perform(get("/api/items")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/items").header("Authorization", "Bearer otro")).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/items").header("Authorization", "Bearer " + FRONT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"\",\"estado\":\"ACTIVO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.nombre").exists())
                .andExpect(jsonPath("$.trace").doesNotExist());

        // Antes caían en el manejador genérico y devolvían 500.
        mvc.perform(post("/api/items").header("Authorization", "Bearer " + FRONT_TOKEN)
                        .contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/items?estado=NOPE").header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/items/no-existe").header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void elPayloadDelWebhookCumpleElContratoConElProducer() throws Exception {
        stubOk();
        String id = create();
        awaitConfirmed(id);

        LoggedRequest sent = producer.findAll(postRequestedFor(urlEqualTo(WEBHOOK))
                .withRequestBody(containing(id))).get(0);

        Set<String> actual = keys(sent.getBodyAsString());
        Set<String> expected = nonNullKeys(resource("/contracts/webhook-created.json"));
        assertThat(actual).as("claves del payload enviado al Producer").isEqualTo(expected);
    }

    private String resource(String path) throws Exception {
        try (var in = getClass().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> keys(String json) throws Exception {
        JsonNode node = new ObjectMapper().readTree(json);
        Set<String> result = new TreeSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private Set<String> nonNullKeys(String json) throws Exception {
        JsonNode node = new ObjectMapper().readTree(json);
        Set<String> result = new TreeSet<>();
        node.fieldNames().forEachRemaining(k -> {
            if (!node.get(k).isNull()) {
                result.add(k);
            }
        });
        return result;
    }

    @Test
    void reconciliacionManualDevuelveResumenYExigeToken() throws Exception {
        String seeded = "seed-" + UUID.randomUUID();
        producer.stubFor(WireMock.get(urlPathEqualTo("/api/items")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(("{\"content\":[{\"id\":\"%s\",\"nombre\":\"Sembrado\",\"estado\":\"ACTIVO\",\"version\":5,"
                        + "\"fechaCreacion\":\"2026-01-01T00:00:00Z\",\"fechaActualizacion\":\"2026-01-01T00:00:00Z\"}],"
                        + "\"page\":0,\"size\":500,\"totalElements\":1,\"totalPages\":1,\"last\":true}")
                        .formatted(seeded))));

        mvc.perform(post("/api/reconcile").header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        mvc.perform(get("/api/items/" + seeded).header("Authorization", "Bearer " + FRONT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncStatus").value("CONFIRMED"));

        mvc.perform(post("/api/reconcile")).andExpect(status().isUnauthorized());
    }

    @Test
    void purgaSoloEventosSentAntiguos() {
        OutboxEvent old = new OutboxEvent("purga-" + UUID.randomUUID(), "item-x", EventType.CREATED, "{}",
                Instant.now().minus(30, ChronoUnit.DAYS));
        old.setStatus(OutboxStatus.SENT);
        old.setSentAt(Instant.now().minus(30, ChronoUnit.DAYS));
        outbox.save(old);

        OutboxEvent recent = new OutboxEvent("keep-" + UUID.randomUUID(), "item-y", EventType.CREATED, "{}",
                Instant.now());
        recent.setStatus(OutboxStatus.SENT);
        recent.setSentAt(Instant.now());
        outbox.save(recent);

        assertThat(purger.purge()).isGreaterThanOrEqualTo(1);
        assertThat(outbox.existsById(old.getId())).isFalse();
        assertThat(outbox.existsById(recent.getId())).isTrue();
    }

    @Test
    void tokensDebilesONoCambiadosSeRechazan() {
        assertThatThrownBy(() -> TokenValidator.requireStrong("T", "corto")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TokenValidator.requireStrong("T", "cambiar-token-frontend-a-consumer-0000000000000000"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(TokenValidator.requireStrong("T", FRONT_TOKEN)).isEqualTo(FRONT_TOKEN);
    }
}
