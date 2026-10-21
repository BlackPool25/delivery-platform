package com.delivery.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DishResponse(
        UUID id,
        UUID restaurantId,
        String name,
        String description,
        BigDecimal price,
        String cuisineType,
        boolean isAvailable,
        Instant createdAt,
        Instant updatedAt
) {}
