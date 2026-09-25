// producer-api/src/main/java/com/catalogo/producer/maintenance/BackupService.java
package com.catalogo.producer.maintenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Copia de seguridad periódica de la fuente de verdad con la API de backup online de SQLite
 * (consistente aunque la BD esté en modo WAL y en uso). Conserva las N copias más recientes.
 * Solo el Producer la necesita: la réplica del Consumer se reconstruye desde el Producer.
 */
@Component
@ConditionalOnProperty(name = "app.backup.enabled", havingValue = "true", matchIfMissing = true)
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final String PREFIX = "producer-";
    private static final String SUFFIX = ".db";

    private final DataSource dataSource;
    private final Path dir;
    private final int keep;

    public BackupService(DataSource dataSource, @Value("${app.backup.dir}") String dir,
                         @Value("${app.backup.keep}") int keep) {
        this.dataSource = dataSource;
        this.dir = Path.of(dir);
        this.keep = Math.max(keep, 1);
    }

    @Scheduled(initialDelayString = "${app.backup.initial-delay-ms}", fixedDelayString = "${app.backup.interval-ms}")
    public void scheduled() {
        try {
            backup();
        } catch (IOException | SQLException e) {
            log.error("Falló la copia de seguridad de la base de datos", e);
        }
    }

    /** Crea una copia y devuelve su ruta. El nombre lo genera la aplicación (sin entrada de usuario). */
    public Path backup() throws IOException, SQLException {
        Files.createDirectories(dir);
        Path target = dir.resolve(PREFIX + TS.format(Instant.now()) + SUFFIX).toAbsolutePath();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("backup to \"" + target + "\"");
        }
        prune();
        log.info("Copia de seguridad creada: {}", target);
        return target;
    }

    private void prune() throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(PREFIX) && n.endsWith(SUFFIX);
            }).sorted().toList();
        }
        for (int i = 0; i < files.size() - keep; i++) {
            Files.deleteIfExists(files.get(i));
        }
    }
}
