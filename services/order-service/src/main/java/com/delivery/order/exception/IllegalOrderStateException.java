package com.delivery.order.exception;

public class IllegalOrderStateException extends RuntimeException {
    public IllegalOrderStateException(String message) {
        super(message);
    }
}
