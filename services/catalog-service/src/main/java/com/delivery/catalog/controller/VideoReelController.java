package com.delivery.catalog.controller;

import com.delivery.catalog.dto.CreateVideoReelRequest;
import com.delivery.catalog.dto.VideoReelResponse;
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
@RequestMapping("/api/v1/catalog")
public class VideoReelController {

    private final CatalogService catalogService;

    public VideoReelController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @PostMapping("/restaurants/{restaurantId}/reels")
    public ResponseEntity<VideoReelResponse> addReel(
            @PathVariable UUID restaurantId,
            @Valid @RequestBody CreateVideoReelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.addVideoReel(restaurantId, request));
    }

    @GetMapping("/reels")
    public ResponseEntity<List<VideoReelResponse>> getReelsByH3(@RequestParam("h3") Long h3Index) {
        return ResponseEntity.ok(catalogService.getReelsByH3(h3Index));
    }
}
