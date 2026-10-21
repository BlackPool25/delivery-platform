package com.delivery.tracking.model;

import java.time.Instant;
import java.util.UUID;

public record CourierState(
        UUID courierId,
        Double latitude,
        Double longitude,
        Double speedKmh,
        Double heading,
        Instant timestamp,
        String h3Index,
        boolean isFlagged,
        String fraudReason,
        Instant flaggedAt
) {}
