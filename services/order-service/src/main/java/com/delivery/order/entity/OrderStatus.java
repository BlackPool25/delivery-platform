package com.delivery.order.entity;

public enum OrderStatus {
    PLACED,
    PAYMENT_PENDING,
    PAYMENT_CONFIRMED,
    ACCEPTED,
    PREPARING,
    READY_FOR_PICKUP,
    DISPATCHED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED,
    SETTLED,
    REFUNDED
}
