package com.delivery.order.exception;

import java.util.UUID;

public class DuplicateOrderException extends RuntimeException {
    public DuplicateOrderException(UUID idempotencyKey) {
        super("Order already exists for idempotency key: " + idempotencyKey);
    }
}
