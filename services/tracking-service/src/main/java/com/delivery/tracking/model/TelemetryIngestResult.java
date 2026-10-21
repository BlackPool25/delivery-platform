package com.delivery.tracking.model;

import java.time.Instant;

public record TelemetryIngestResult(
        String status,
        boolean accepted,
        double calculatedSpeedKmh,
        double maxAllowedSpeedKmh,
        String message,
        Instant timestamp
) {
    public static TelemetryIngestResult accepted(double speed, String message) {
        return new TelemetryIngestResult("ACCEPTED", true, speed, 120.0, message, Instant.now());
    }

    public static TelemetryIngestResult flaggedSpoofing(double calculatedSpeed, double maxSpeed, String message) {
        return new TelemetryIngestResult("FLAGGED_SPOOFING", false, calculatedSpeed, maxSpeed, message, Instant.now());
    }
}
