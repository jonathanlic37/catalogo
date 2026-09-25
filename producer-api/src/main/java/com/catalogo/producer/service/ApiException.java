// producer-api/src/main/java/com/catalogo/producer/service/ApiException.java
package com.catalogo.producer.service;

import org.springframework.http.HttpStatus;

/** Error de negocio con estado HTTP; el mensaje es seguro para exponer al cliente. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
