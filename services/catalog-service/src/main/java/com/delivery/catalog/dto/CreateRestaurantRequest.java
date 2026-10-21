package com.delivery.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateRestaurantRequest(
        @NotBlank String name,
        String description,
        String phone,
        @NotNull Double latitude,
        @NotNull Double longitude,
        @NotBlank String deliveryZoneWkt,
        @NotNull Long h3Res8,
        String cityId,
        Integer averagePrepTimeMinutes
) {}
