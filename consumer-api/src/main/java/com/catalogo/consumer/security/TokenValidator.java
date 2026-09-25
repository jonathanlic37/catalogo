// consumer-api/src/main/java/com/catalogo/consumer/security/TokenValidator.java
package com.catalogo.consumer.security;

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
}
