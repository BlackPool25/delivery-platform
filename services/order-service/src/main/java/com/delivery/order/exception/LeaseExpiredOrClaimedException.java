package com.delivery.order.exception;

import java.util.UUID;

public class LeaseExpiredOrClaimedException extends RuntimeException {
    public LeaseExpiredOrClaimedException(UUID orderId) {
        super("Order dispatch lease expired or already claimed by another courier: " + orderId);
    }

    public LeaseExpiredOrClaimedException(String message) {
        super(message);
    }
}
