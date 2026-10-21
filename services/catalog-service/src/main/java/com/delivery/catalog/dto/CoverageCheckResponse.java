package com.delivery.catalog.dto;

import java.util.UUID;

public record CoverageCheckResponse(
        UUID restaurantId,
        String restaurantName,
        boolean covered,
        Double latitude,
        Double longitude,
        Double distanceMeters
) {}
