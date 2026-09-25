// producer-api/src/main/java/com/catalogo/producer/web/ItemController.java
package com.catalogo.producer.web;

import com.catalogo.producer.dto.ItemRequest;
import com.catalogo.producer.dto.ItemResponse;
import com.catalogo.producer.dto.PageResponse;
import com.catalogo.producer.service.ItemService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lectura autoritativa del catálogo (SSoT) y administración directa desde la aplicación central.
 * Los cambios del Consumer entran por webhook; estos endpoints son el origen canónico de datos
 * que el Consumer descubre con la reconciliación.
 */
@RestController
@RequestMapping("/api/items")
public class ItemController {

    static final int MAX_PAGE_SIZE = 1000;

    private final ItemService service;

    public ItemController(ItemService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ItemResponse> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "500") int size) {
        return service.list(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }

    @GetMapping("/{id}")
    public ItemResponse get(@PathVariable String id) {
        return service.get(id);
    }

    /** Crea un ítem en la fuente de verdad. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ItemResponse create(@Valid @RequestBody ItemRequest req) {
        return service.createDirect(req);
    }

    /** Edita un ítem en la fuente de verdad. */
    @PutMapping("/{id}")
    public ItemResponse update(@PathVariable String id, @Valid @RequestBody ItemRequest req) {
        return service.updateDirect(id, req);
    }

    /** Elimina un ítem de la fuente de verdad. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.deleteDirect(id);
    }
}
