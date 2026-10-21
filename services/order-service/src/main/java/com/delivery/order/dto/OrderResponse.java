package com.delivery.order.dto;

import com.delivery.order.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID userId,
        UUID restaurantId,
        UUID courierId,
        OrderStatus status,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal tax,
        BigDecimal totalAmount,
        Double dropoffLatitude,
        Double dropoffLongitude,
        String dropoffBuilding,
        String dropoffFloor,
        String dropoffRoom,
        String dropoffGateCode,
        String dropoffNotes,
        String verificationPin,
        Instant dispatchLeaseExpiresAt,
        String cityId,
        UUID idempotencyKey,
        String cancellationReason,
        Long version,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {}
