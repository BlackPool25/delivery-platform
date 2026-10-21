package com.delivery.auth.dto;

public record RequestOtpResponse(
        String phone,
        String message,
        long expiresInSeconds
) {}
