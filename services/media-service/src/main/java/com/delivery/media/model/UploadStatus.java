package com.delivery.media.model;

public enum UploadStatus {
    CREATED,
    UPLOADING,
    UPLOADED,
    QUEUED_FOR_TRANSCODING,
    PROCESSING,
    COMPLETED,
    FAILED
}
