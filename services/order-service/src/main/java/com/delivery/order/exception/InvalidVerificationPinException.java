package com.delivery.order.exception;

public class InvalidVerificationPinException extends RuntimeException {
    public InvalidVerificationPinException(String message) {
        super(message);
    }
}
