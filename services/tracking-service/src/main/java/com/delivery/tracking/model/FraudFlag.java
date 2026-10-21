package com.delivery.tracking.model;

import java.time.Instant;
import java.util.UUID;

public record FraudFlag(
        UUID courierId,
        double calculatedSpeedKmh,
        double maxAllowedSpeedKmh,
        String reason,
        Instant flaggedAt
) {}
