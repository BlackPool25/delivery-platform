package com.delivery.simulator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderDto(
        UUID id,
        UUID userId,
        UUID restaurantId,
        UUID courierId,
        String status,
        Double dropoffLatitude,
        Double dropoffLongitude,
        String verificationPin,
        Instant dispatchLeaseExpiresAt
) {}
