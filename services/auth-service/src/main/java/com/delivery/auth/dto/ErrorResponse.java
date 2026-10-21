package com.delivery.auth.dto;

import java.time.Instant;

public record ErrorResponse(
        String error,
        String message,
        Instant timestamp
) {
    public ErrorResponse(String error, String message) {
        this(error, message, Instant.now());
    }
}
