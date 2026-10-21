package com.delivery.auth.exception;

public class OtpExpiredException extends RuntimeException {
    public OtpExpiredException(String message) {
        super(message);
    }
}
