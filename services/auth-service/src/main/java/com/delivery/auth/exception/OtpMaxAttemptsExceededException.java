package com.delivery.auth.exception;

public class OtpMaxAttemptsExceededException extends RuntimeException {
    public OtpMaxAttemptsExceededException(String message) {
        super(message);
    }
}
