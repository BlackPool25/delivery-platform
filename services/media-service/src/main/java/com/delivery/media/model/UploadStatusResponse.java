package com.delivery.media.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UploadStatusResponse(
        UUID uploadId,
        UploadStatus status,
        String originalFilename,
        long uploadLength,
        long uploadOffset,
        String minioObjectKey,
        String hlsMasterPlaylistUrl,
        Instant createdAt,
        Instant completedAt,
        String message
) {}
