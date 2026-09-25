// producer-api/src/main/java/com/catalogo/producer/security/TokenValidator.java
package com.catalogo.producer.security;

/** Impide arrancar con tokens débiles o con el valor de ejemplo de .env.example. */
public final class TokenValidator {

    static final int MIN_LENGTH = 32;

    private TokenValidator() {}

    public static String requireStrong(String name, String token) {
        if (token == null || token.length() < MIN_LENGTH || token.toLowerCase().contains("cambiar")) {
            throw new IllegalStateException(name + " debe tener al menos " + MIN_LENGTH
                    + " caracteres y no puede ser el valor de ejemplo (genere uno con: openssl rand -hex 32)");
        }
        return token;
    }
}
