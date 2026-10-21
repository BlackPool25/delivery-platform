package com.delivery.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RestaurantResponse(
        UUID id,
        String name,
        String description,
        String phone,
        Double latitude,
        Double longitude,
        String deliveryZoneWkt,
        Long h3Res8,
        String cityId,
        boolean isActive,
        boolean isAcceptingOrders,
        int averagePrepTimeMinutes,
        BigDecimal rating,
        Instant createdAt,
        Instant updatedAt
) {}
