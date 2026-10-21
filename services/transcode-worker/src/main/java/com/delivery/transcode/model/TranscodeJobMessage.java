package com.delivery.transcode.model;

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
) {}
