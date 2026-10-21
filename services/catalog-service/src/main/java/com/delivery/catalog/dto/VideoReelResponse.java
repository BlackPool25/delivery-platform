package com.delivery.catalog.dto;

import java.time.Instant;
import java.util.UUID;

public record VideoReelResponse(
        UUID id,
        UUID restaurantId,
        UUID dishId,
        String masterM3u8Url,
        String thumbnailUrl,
        Long h3Res8,
        int durationSec,
        Instant createdAt
) {}
