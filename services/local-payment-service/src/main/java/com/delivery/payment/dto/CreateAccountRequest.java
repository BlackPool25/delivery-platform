package com.delivery.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateAccountRequest(
        @NotNull(message = "userId must not be null")
        UUID userId,

        @NotNull(message = "initialBalance must not be null")
        @DecimalMin(value = "0.00", message = "initialBalance cannot be negative")
        BigDecimal initialBalance,

        String currency
) {}
