package com.delivery.catalog.controller;

import com.delivery.catalog.dto.CreateDishRequest;
import com.delivery.catalog.dto.DishResponse;
import com.delivery.catalog.service.CatalogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog")
public class DishController {

    private final CatalogService catalogService;

    public DishController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/restaurants/{restaurantId}/dishes")
    public ResponseEntity<List<DishResponse>> getDishes(
            @PathVariable UUID restaurantId,
            @RequestParam(value = "availableOnly", defaultValue = "false") boolean availableOnly) {
        return ResponseEntity.ok(catalogService.getDishesForRestaurant(restaurantId, availableOnly));
    }

    @GetMapping("/dishes/{dishId}")
    public ResponseEntity<DishResponse> getDish(@PathVariable UUID dishId) {
        return ResponseEntity.ok(catalogService.getDish(dishId));
    }

    @PostMapping("/restaurants/{restaurantId}/dishes")
    public ResponseEntity<DishResponse> addDish(
            @PathVariable UUID restaurantId,
            @Valid @RequestBody CreateDishRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.addDish(restaurantId, request));
    }

    @PostMapping("/dishes/{dishId}/86")
    public ResponseEntity<DishResponse> eightySixDishPost(@PathVariable UUID dishId) {
        return ResponseEntity.ok(catalogService.eightySixDish(dishId));
    }

    @PatchMapping("/dishes/{dishId}/86")
    public ResponseEntity<DishResponse> eightySixDishPatch(@PathVariable UUID dishId) {
        return ResponseEntity.ok(catalogService.eightySixDish(dishId));
    }

    @PatchMapping("/dishes/{dishId}/availability")
    public ResponseEntity<DishResponse> updateAvailabilityPatch(
            @PathVariable UUID dishId,
            @RequestParam("available") boolean available) {
        return ResponseEntity.ok(catalogService.updateDishAvailability(dishId, available));
    }

    @PutMapping("/dishes/{dishId}/availability")
    public ResponseEntity<DishResponse> updateAvailabilityPut(
            @PathVariable UUID dishId,
            @RequestParam("available") boolean available) {
        return ResponseEntity.ok(catalogService.updateDishAvailability(dishId, available));
    }
}
