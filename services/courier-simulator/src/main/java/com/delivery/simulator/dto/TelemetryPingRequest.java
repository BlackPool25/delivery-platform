package com.delivery.simulator.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record TelemetryPingRequest(
        @NotNull
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        Double latitude,

        @NotNull
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        Double longitude,

        @NotNull
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant timestamp,

        Double speedKmh,
        Double heading,
        Double altitude,
        Double accuracy
) {}
