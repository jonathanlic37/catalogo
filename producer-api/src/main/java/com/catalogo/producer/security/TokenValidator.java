// producer-api/src/main/java/com/catalogo/producer/security/TokenValidator.java
package com.catalogo.producer.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Impide arrancar con tokens débiles o con un marcador sin sustituir ("cambiar..."). Acepta los
 * tokens de desarrollo de .env.example (prefijo {@code dev-only-}) para que el sistema arranque
 * con {@code cp .env.example .env && docker compose up --build}, pero lo advierte en el log.
 * Nunca registra el valor del token.
 */
public final class TokenValidator {

    private static final Logger log = LoggerFactory.getLogger(TokenValidator.class);
    static final int MIN_LENGTH = 32;
    static final String DEV_PREFIX = "dev-only-";

    private TokenValidator() {}

    public static String requireStrong(String name, String token) {
        if (token == null || token.length() < MIN_LENGTH || token.toLowerCase().contains("cambiar")) {
            throw new IllegalStateException(name + " debe tener al menos " + MIN_LENGTH
                    + " caracteres y no puede ser un marcador sin sustituir (genere uno con: openssl rand -hex 32)");
        }
        if (token.startsWith(DEV_PREFIX)) {
            log.warn("{} usa el token de desarrollo de .env.example: sustitúyalo fuera de un entorno local", name);
        }
        return token;
    }

    /**
     * Ningún token aceptado puede repetirse: dos credenciales con privilegios distintos anularían la
     * separación, y un token «anterior» igual al actual no sería una rotación.
     */
    public static void requireAllDistinct(java.util.List<String> tokens) {
        if (new java.util.HashSet<>(tokens).size() != tokens.size()) {
            throw new IllegalStateException("Los tokens del Producer (actuales y *_PREVIOUS) deben ser todos distintos");
        }
    }

    /** Una ventana de rotación abierta debe cerrarse: se recuerda en el log en cada arranque. */
    public static void warnRotationWindow(String consumerPrevious, String adminPrevious) {
        if (!consumerPrevious.isBlank() || !adminPrevious.isBlank()) {
            log.warn("Rotación de tokens en curso: se aceptan también los tokens *_PREVIOUS. "
                    + "Vacíelos cuando todos los clientes usen el token nuevo");
        }
    }

    /** Dos credenciales con privilegios distintos no pueden compartir valor (anularía la separación). */
    public static void requireDistinct(String nameA, String a, String nameB, String b) {
        if (a != null && a.equals(b)) {
            throw new IllegalStateException(nameA + " y " + nameB + " deben ser distintos");
        }
    }
}
