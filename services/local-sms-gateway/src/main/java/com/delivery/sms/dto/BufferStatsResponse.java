package com.delivery.sms.dto;

public record BufferStatsResponse(
        long totalReceived,
        int currentBuffered,
        int capacity,
        int distinctPhones
) {}
