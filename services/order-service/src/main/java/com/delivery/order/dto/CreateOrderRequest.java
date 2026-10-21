package com.delivery.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
        @NotNull UUID userId,
        @NotNull UUID restaurantId,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double dropoffLatitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double dropoffLongitude,
        @NotBlank @Size(max = 128) String dropoffBuilding,
        String dropoffFloor,
        String dropoffRoom,
        String dropoffGateCode,
        String dropoffNotes,
        @NotBlank @Size(min = 4, max = 6) String verificationPin,
        UUID idempotencyKey,
        @NotEmpty @Valid List<OrderItemRequest> items
) {}
