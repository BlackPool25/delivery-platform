package com.delivery.catalog.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateVideoReelRequest(
        UUID dishId,
        String masterM3u8Url,
        String thumbnailUrl,
        @NotNull Long h3Res8,
        Integer durationSec
) {}
