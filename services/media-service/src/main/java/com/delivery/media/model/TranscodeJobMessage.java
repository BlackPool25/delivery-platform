package com.delivery.media.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

public record TranscodeJobMessage(
        UUID jobId,
        UUID uploadId,
        String bucket,
        String sourceKey,
        String targetPrefix,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant createdAt
) {
    public static TranscodeJobMessage of(UUID uploadId, String bucket, String sourceKey, String targetPrefix) {
        return new TranscodeJobMessage(
                UUID.randomUUID(),
                uploadId,
                bucket,
                sourceKey,
                targetPrefix,
                Instant.now()
        );
    }
}
