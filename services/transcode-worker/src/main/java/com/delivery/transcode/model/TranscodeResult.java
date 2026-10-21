package com.delivery.transcode.model;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public record TranscodeResult(
        UUID jobId,
        UUID uploadId,
        Duration duration,
        double averageCpuUtilizationPercent,
        boolean isHardwareAccelerated,
        List<String> generatedFiles,
        boolean success,
        String errorMessage
) {}
