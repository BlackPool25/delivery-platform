package com.delivery.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record RefundPaymentRequest(
        @NotNull(message = "orderId must not be null")
        UUID orderId,

        @NotNull(message = "userId must not be null")
        UUID userId,

        @NotNull(message = "amount must not be null")
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        BigDecimal amount,

        String reason,

        UUID idempotencyKey
) {}
