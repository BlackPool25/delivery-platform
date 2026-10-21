package com.delivery.tracking.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record TelemetryPing(
        @NotNull(message = "Latitude is mandatory")
        @DecimalMin(value = "-90.0", message = "Latitude must be >= -90.0")
        @DecimalMax(value = "90.0", message = "Latitude must be <= 90.0")
        Double latitude,

        @NotNull(message = "Longitude is mandatory")
        @DecimalMin(value = "-180.0", message = "Longitude must be >= -180.0")
        @DecimalMax(value = "180.0", message = "Longitude must be <= 180.0")
        Double longitude,

        @NotNull(message = "Timestamp is mandatory")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant timestamp,

        Double speedKmh,
        Double heading,
        Double altitude,
        Double accuracy
) {}
