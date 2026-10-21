package com.delivery.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemRequest(
        @NotNull UUID dishId,
        @NotBlank String dishName,
        @NotNull @DecimalMin("0.0") BigDecimal unitPrice,
        @Min(1) int quantity,
        String customizationNotes
) {}
