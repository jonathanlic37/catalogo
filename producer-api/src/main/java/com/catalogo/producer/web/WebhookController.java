// producer-api/src/main/java/com/catalogo/producer/web/WebhookController.java
package com.catalogo.producer.web;

import com.catalogo.producer.dto.EventType;
import com.catalogo.producer.dto.ItemResponse;
import com.catalogo.producer.dto.WebhookPayload;
import com.catalogo.producer.service.ApiException;
import com.catalogo.producer.service.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final IdempotencyService idempotencyService;

    public WebhookController(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/catalogo")
    public ResponseEntity<ItemResponse> receive(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Event-Type") EventType eventType,
            @Valid @RequestBody WebhookPayload payload) {

        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key inválida");
        }
        IdempotencyService.Result result = idempotencyService.process(idempotencyKey, eventType, payload);
        return ResponseEntity.status(result.status())
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.body());
    }
}
