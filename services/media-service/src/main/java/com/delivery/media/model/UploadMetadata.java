package com.delivery.media.model;

import java.time.Instant;
import java.util.UUID;

public class UploadMetadata {

    private final UUID uploadId;
    private final String originalFilename;
    private final long uploadLength;
    private long uploadOffset;
    private final String contentType;
    private UploadStatus status;
    private String minioObjectKey;
    private final Instant createdAt;
    private Instant completedAt;

    public UploadMetadata(UUID uploadId, String originalFilename, long uploadLength,
                          String contentType, UploadStatus status) {
        this.uploadId = uploadId;
        this.originalFilename = originalFilename;
        this.uploadLength = uploadLength;
        this.uploadOffset = 0L;
        this.contentType = contentType;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public UUID getUploadId() {
        return uploadId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public long getUploadLength() {
        return uploadLength;
    }

    public synchronized long getUploadOffset() {
        return uploadOffset;
    }

    public synchronized void setUploadOffset(long uploadOffset) {
        this.uploadOffset = uploadOffset;
    }

    public String getContentType() {
        return contentType;
    }

    public synchronized UploadStatus getStatus() {
        return status;
    }

    public synchronized void setStatus(UploadStatus status) {
        this.status = status;
    }

    public synchronized String getMinioObjectKey() {
        return minioObjectKey;
    }

    public synchronized void setMinioObjectKey(String minioObjectKey) {
        this.minioObjectKey = minioObjectKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized Instant getCompletedAt() {
        return completedAt;
    }

    public synchronized void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
