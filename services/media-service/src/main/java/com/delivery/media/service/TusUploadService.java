package com.delivery.media.service;

import com.delivery.media.model.UploadMetadata;
import com.delivery.media.model.UploadStatusResponse;

import java.io.InputStream;
import java.util.UUID;

public interface TusUploadService {

    UploadMetadata createUpload(long uploadLength, String metadataHeader, String contentType);

    long appendChunk(UUID uploadId, long offset, InputStream chunkStream, long chunkLength);

    UploadMetadata getUpload(UUID uploadId);

    void terminateUpload(UUID uploadId);

    UploadStatusResponse getUploadStatus(UUID uploadId);
}
