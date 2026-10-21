package com.delivery.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderItemResponse(
        UUID id,
        UUID dishId,
        String dishName,
        BigDecimal unitPrice,
        int quantity,
        String customizationNotes,
        Instant createdAt
) {}
