package com.delivery.catalog.controller;

import com.delivery.catalog.dto.CoverageCheckResponse;
import com.delivery.catalog.dto.CreateRestaurantRequest;
import com.delivery.catalog.dto.RestaurantResponse;
import com.delivery.catalog.service.CatalogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog/restaurants")
public class RestaurantController {

    private final CatalogService catalogService;

    public RestaurantController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @PostMapping
    public ResponseEntity<RestaurantResponse> createRestaurant(@Valid @RequestBody CreateRestaurantRequest request) {
        RestaurantResponse response = catalogService.createRestaurant(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RestaurantResponse> getRestaurant(@PathVariable UUID id) {
        return ResponseEntity.ok(catalogService.getRestaurant(id));
    }

    @GetMapping("/{id}/coverage")
    public ResponseEntity<CoverageCheckResponse> checkCoverage(
            @PathVariable UUID id,
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng) {
        CoverageCheckResponse response = catalogService.checkDeliveryCoverage(id, lat, lng);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/nearby")
    public ResponseEntity<List<RestaurantResponse>> findNearby(
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng) {
        List<RestaurantResponse> responses = catalogService.findActiveRestaurantsCovering(lat, lng);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/h3/{h3Cell}")
    public ResponseEntity<List<RestaurantResponse>> findByH3(@PathVariable Long h3Cell) {
        return ResponseEntity.ok(catalogService.findByH3Cell(h3Cell));
    }
}
