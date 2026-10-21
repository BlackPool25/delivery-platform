package com.delivery.tracking.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebSocketMessage(
        String type,
        UUID courierId,
        Double latitude,
        Double longitude,
        Double speedKmh,
        Double heading,
        Instant timestamp,
        String payload
) {}
