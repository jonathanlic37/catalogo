// producer-api/src/test/java/com/catalogo/producer/WebhookIdempotencyTest.java
package com.catalogo.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.catalogo.producer.maintenance.BackupService;
import com.catalogo.producer.maintenance.IdempotencyPurger;
import com.catalogo.producer.model.IdempotencyRecord;
import com.catalogo.producer.repository.IdempotencyRepository;
import com.catalogo.producer.repository.ItemRepository;
import com.catalogo.producer.security.TokenValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class WebhookIdempotencyTest {

    /** Credencial del Consumer (SERVICE): webhook + lectura. */
    private static final String TOKEN = "test-token-0123456789-abcdefghijklmnopqrstuvwxyz";
    /** Credencial de administración (ADMIN): escritura directa + lectura + métricas. */
    private static final String ADMIN = "admin-token-0123456789-abcdefghijklmnopqrstuvwxyz";

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + tmp.resolve("producer-test.db"));
        r.add("app.security.consumer-token", () -> TOKEN);
        r.add("app.security.admin-token", () -> ADMIN);
        r.add("app.cors.allowed-origins", () -> "http://localhost:8088");
        r.add("app.backup.dir", () -> tmp.resolve("backups").toString());
        r.add("app.backup.keep", () -> "2");
        r.add("app.backup.initial-delay-ms", () -> "3600000");
    }

    @Autowired MockMvc mvc;
    @Autowired ItemRepository items;
    @Autowired IdempotencyRepository idempotency;
    @Autowired IdempotencyPurger purger;
    @Autowired BackupService backups;

    private static String body(String id, String nombre) {
        return "{\"id\":\"%s\",\"nombre\":\"%s\",\"descripcion\":\"d\",\"estado\":\"ACTIVO\"}".formatted(id, nombre);
    }

    private ResultActions webhook(String key, String event, String json) throws Exception {
        return mvc.perform(post("/webhooks/catalogo").header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", key).header("X-Event-Type", event)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void webhookDuplicadoNoCorrompeDatos() throws Exception {
        String id = UUID.randomUUID().toString();
        String key = key();

        webhook(key, "CREATED", body(id, "Cafe"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.version").value(1));

        // Reenvío idéntico: misma respuesta, sin segunda escritura ni error por id duplicado.
        webhook(key, "CREATED", body(id, "Cafe"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(items.findAll().stream().filter(i -> i.getId().equals(id))).hasSize(1);
    }

    @Test
    void mismaClaveConOtroContenidoDa409() throws Exception {
        String id = UUID.randomUUID().toString();
        String key = key();

        webhook(key, "CREATED", body(id, "Uno")).andExpect(status().isCreated());
        webhook(key, "CREATED", body(id, "Dos")).andExpect(status().isConflict());
    }

    @Test
    void actualizacionIncrementaVersionYRechazaVersionObsoleta() throws Exception {
        String id = UUID.randomUUID().toString();
        webhook(key(), "CREATED", body(id, "A")).andExpect(status().isCreated());

        String ok = "{\"id\":\"%s\",\"nombre\":\"B\",\"estado\":\"INACTIVO\",\"baseVersion\":1}".formatted(id);
        webhook(key(), "UPDATED", ok)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.nombre").value("B"))
                .andExpect(jsonPath("$.estado").value("INACTIVO"));

        String stale = "{\"id\":\"%s\",\"nombre\":\"C\",\"estado\":\"ACTIVO\",\"baseVersion\":1}".formatted(id);
        webhook(key(), "UPDATED", stale).andExpect(status().isConflict());
    }

    @Test
    void borradoEsIdempotenteYLuegoElItemNoExiste() throws Exception {
        String id = UUID.randomUUID().toString();
        webhook(key(), "CREATED", body(id, "Efimero")).andExpect(status().isCreated());

        String key = key();
        String del = "{\"id\":\"%s\"}".formatted(id);
        webhook(key, "DELETED", del).andExpect(status().isOk());
        webhook(key, "DELETED", del)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        // Con una clave nueva, borrar lo ya borrado es un 404 (el Consumer lo trata como éxito).
        webhook(key(), "DELETED", del).andExpect(status().isNotFound());
        mvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void listaPaginadaConOrdenEstable() throws Exception {
        for (int i = 0; i < 3; i++) {
            webhook(key(), "CREATED", body(UUID.randomUUID().toString(), "pag-" + i)).andExpect(status().isCreated());
        }
        mvc.perform(get("/api/items?page=0&size=2").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalPages").isNumber())
                .andExpect(jsonPath("$.last").value(false));
        // Un tamaño fuera de rango se acota en lugar de devolver toda la tabla.
        mvc.perform(get("/api/items?size=999999").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1000));
    }

    @Test
    void sinTokenDa401() throws Exception {
        mvc.perform(get("/api/items")).andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/catalogo").header("Idempotency-Key", "k").header("X-Event-Type", "CREATED")
                        .contentType(MediaType.APPLICATION_JSON).content(body("x", "y")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validacionNoExponeStackTrace() throws Exception {
        webhook(key(), "CREATED", body("v1", "n".repeat(500)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.nombre").exists())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void erroresEstandarDeSpringConservanSuCodigo() throws Exception {
        // Antes caían en el manejador genérico y devolvían 500.
        mvc.perform(post("/webhooks/catalogo").header("Authorization", "Bearer " + TOKEN)
                        .header("Idempotency-Key", key()).header("X-Event-Type", "CREATED")
                        .contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/webhooks/catalogo").header("Authorization", "Bearer " + TOKEN)
                        .header("X-Event-Type", "CREATED")
                        .contentType(MediaType.APPLICATION_JSON).content(body("h1", "n")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/webhooks/catalogo").header("Authorization", "Bearer " + TOKEN)
                        .header("Idempotency-Key", key()).header("X-Event-Type", "NOPE")
                        .contentType(MediaType.APPLICATION_JSON).content(body("h2", "n")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void purgaEliminaSoloRegistrosDeIdempotenciaAntiguos() {
        Instant old = Instant.now().minus(30, ChronoUnit.DAYS);
        idempotency.save(new IdempotencyRecord("purga-vieja", "h", 200, "{}", old));
        idempotency.save(new IdempotencyRecord("purga-reciente", "h", 200, "{}"));

        assertThat(purger.purge()).isGreaterThanOrEqualTo(1);

        assertThat(idempotency.existsById("purga-vieja")).isFalse();
        assertThat(idempotency.existsById("purga-reciente")).isTrue();
    }

    @Test
    void backupGeneraCopiaValidaYConservaSoloLasUltimas() throws Exception {
        Path first = backups.backup();
        assertThat(first).exists();
        assertThat(Files.size(first)).isGreaterThan(0);
        backups.backup();
        backups.backup();

        try (Stream<Path> files = Files.list(tmp.resolve("backups"))) {
            assertThat(files.count()).as("app.backup.keep=2").isEqualTo(2);
        }
    }

    @Test
    void elBackupEsUnaCopiaSqliteLegibleConLosDatos() throws Exception {
        String id = UUID.randomUUID().toString();
        webhook(key(), "CREATED", body(id, "Respaldo")).andExpect(status().isCreated());

        Path backup = backups.backup();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backup);
             PreparedStatement ps = c.prepareStatement("select count(*) from items where id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void tokensDebilesONoCambiadosSeRechazan() {
        assertThatThrownBy(() -> TokenValidator.requireStrong("T", "corto")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TokenValidator.requireStrong("T", "cambiar-token-consumer-a-producer-0000000000000000"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(TokenValidator.requireStrong("T", TOKEN)).isEqualTo(TOKEN);
    }

    @Test
    void escrituraDirectaEnElProducerCreaActualizaYBorra() throws Exception {
        String created = mvc.perform(post("/api/items").header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Directo\",\"descripcion\":\"d\",\"estado\":\"ACTIVO\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Directo"));

        mvc.perform(put("/api/items/" + id).header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Editado\",\"estado\":\"INACTIVO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.nombre").value("Editado"));

        mvc.perform(delete("/api/items/" + id).header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void elWebhookAceptaElContratoDelConsumerYLaRespuestaCumpleElContrato() throws Exception {
        String payload = resource("/contracts/webhook-created.json");

        String response = mvc.perform(post("/webhooks/catalogo").header("Authorization", "Bearer " + TOKEN)
                        .header("Idempotency-Key", key()).header("X-Event-Type", "CREATED")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // El Producer acepta el payload tal como lo define el contrato y responde con la forma acordada.
        assertContractKeys(response, "/contracts/item-response.json");
    }

    @Test
    void descartaEventosFueraDeOrden() throws Exception {
        String id = UUID.randomUUID().toString();
        String old = "{\"id\":\"%s\",\"nombre\":\"Viejo\",\"estado\":\"ACTIVO\",\"occurredAt\":\"2026-01-01T00:00:00Z\"}"
                .formatted(id);
        String recent = "{\"id\":\"%s\",\"nombre\":\"Reciente\",\"estado\":\"ACTIVO\",\"occurredAt\":\"2026-01-02T00:00:00Z\"}"
                .formatted(id);

        webhook(key(), "CREATED", old).andExpect(status().isCreated());
        webhook(key(), "UPDATED", recent).andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Reciente")).andExpect(jsonPath("$.version").value(2));

        // Evento más antiguo entregado tarde: se descarta y no revierte el estado.
        webhook(key(), "UPDATED", old).andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Reciente")).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void laVersionPrevaleceSobreOccurredAt() throws Exception {
        String id = UUID.randomUUID().toString();
        webhook(key(), "CREATED", ("{\"id\":\"%s\",\"nombre\":\"A\",\"estado\":\"ACTIVO\","
                + "\"occurredAt\":\"2026-06-01T00:00:00Z\"}").formatted(id)).andExpect(status().isCreated());

        // Basado en la versión vigente pero con un reloj atrasado: se aplica (no depende de relojes).
        webhook(key(), "UPDATED", ("{\"id\":\"%s\",\"nombre\":\"B\",\"estado\":\"ACTIVO\",\"baseVersion\":1,"
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\"}").formatted(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("B"))
                .andExpect(jsonPath("$.version").value(2));

        // Basado en una versión superada, aunque su occurredAt sea el más reciente: conflicto, no se aplica.
        webhook(key(), "UPDATED", ("{\"id\":\"%s\",\"nombre\":\"C\",\"estado\":\"ACTIVO\",\"baseVersion\":1,"
                + "\"occurredAt\":\"2027-01-01T00:00:00Z\"}").formatted(id))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + TOKEN))
                .andExpect(jsonPath("$.nombre").value("B"));
    }

    @Test
    void borradoConVersionObsoletaDa409YNoBorra() throws Exception {
        String id = UUID.randomUUID().toString();
        webhook(key(), "CREATED", body(id, "Vivo")).andExpect(status().isCreated());
        webhook(key(), "UPDATED", "{\"id\":\"%s\",\"nombre\":\"Vivo 2\",\"estado\":\"ACTIVO\",\"baseVersion\":1}"
                .formatted(id)).andExpect(status().isOk());

        // El Consumer quería borrar la versión 1, pero la fuente de verdad ya está en la 2.
        webhook(key(), "DELETED", "{\"id\":\"%s\",\"baseVersion\":1}".formatted(id))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());

        webhook(key(), "DELETED", "{\"id\":\"%s\",\"baseVersion\":2}".formatted(id)).andExpect(status().isOk());
        mvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void separacionDePrivilegiosEntreConsumerYAdministracion() throws Exception {
        String item = "{\"nombre\":\"X\",\"estado\":\"ACTIVO\"}";
        // El Consumer NO puede escribir directamente en la fuente de verdad: solo por el webhook.
        mvc.perform(post("/api/items").header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(item))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(put("/api/items/abc").header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(item))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/items/abc").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());

        // La administración no puede suplantar al Consumer en el webhook.
        mvc.perform(post("/webhooks/catalogo").header("Authorization", "Bearer " + ADMIN)
                        .header("Idempotency-Key", key()).header("X-Event-Type", "CREATED")
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID().toString(), "Y")))
                .andExpect(status().isForbidden());

        // Ambas pueden leer.
        mvc.perform(get("/api/items").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
        mvc.perform(get("/api/items").header("Authorization", "Bearer " + ADMIN)).andExpect(status().isOk());
    }

    @Test
    void lasCredencialesDeServicioYAdministracionDebenSerDistintas() {
        assertThatThrownBy(() -> TokenValidator.requireDistinct("A", TOKEN, "B", TOKEN))
                .isInstanceOf(IllegalStateException.class);
        TokenValidator.requireDistinct("A", TOKEN, "B", ADMIN);
    }

    @Test
    void payloadConCamposDesconocidosSeRechaza() throws Exception {
        String id = UUID.randomUUID().toString();
        webhook(key(), "CREATED", "{\"id\":\"%s\",\"nombre\":\"X\",\"estado\":\"ACTIVO\",\"version\":99}".formatted(id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.trace").doesNotExist());
        mvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void tokenDeDesarrolloDeEnvExampleSeAcepta() {
        String dev = "dev-only-consumer-to-producer-token-for-local-use-0001";
        assertThat(TokenValidator.requireStrong("T", dev)).isEqualTo(dev);
    }

    private String resource(String path) throws Exception {
        try (var in = getClass().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Compara el conjunto de claves no nulas de un JSON con el del fixture de contrato. */
    private void assertContractKeys(String actualJson, String resourcePath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode expected = mapper.readTree(resource(resourcePath));
        JsonNode actual = mapper.readTree(actualJson);
        Set<String> expectedKeys = new TreeSet<>();
        expected.fieldNames().forEachRemaining(k -> {
            if (!expected.get(k).isNull()) {
                expectedKeys.add(k);
            }
        });
        Set<String> actualKeys = new TreeSet<>();
        actual.fieldNames().forEachRemaining(actualKeys::add);
        assertThat(actualKeys).as("claves de " + resourcePath).isEqualTo(expectedKeys);
    }

    @Test
    void escrituraDirectaSinTokenValidoDa401() throws Exception {
        mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"X\",\"estado\":\"ACTIVO\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/items/abc").header("Authorization", "Bearer otro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"X\",\"estado\":\"ACTIVO\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/items/abc")).andExpect(status().isUnauthorized());
    }
}
