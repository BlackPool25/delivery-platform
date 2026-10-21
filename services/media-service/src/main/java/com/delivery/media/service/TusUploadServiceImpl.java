package com.delivery.media.service;

import com.delivery.media.config.MinioConfig;
import com.delivery.media.model.TranscodeJobMessage;
import com.delivery.media.model.UploadMetadata;
import com.delivery.media.model.UploadStatus;
import com.delivery.media.model.UploadStatusResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TusUploadServiceImpl implements TusUploadService {

    private static final Logger log = LoggerFactory.getLogger(TusUploadServiceImpl.class);

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;
    private final RabbitTemplate rabbitTemplate;

    @Value("${media.staging-dir:/tmp/delivery-media/staging}")
    private String stagingDir;

    @Value("${media.rabbitmq.exchange:video.transcode.exchange}")
    private String exchange;

    @Value("${media.rabbitmq.routing-key:video.transcode.job}")
    private String routingKey;

    // In-memory registry of active uploads
    private final Map<UUID, UploadMetadata> uploadRegistry = new ConcurrentHashMap<>();

    public TusUploadServiceImpl(MinioClient minioClient,
                                MinioConfig minioConfig,
                                RabbitTemplate rabbitTemplate) {
        this.minioClient = minioClient;
        this.minioConfig = minioConfig;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public UploadMetadata createUpload(long uploadLength, String metadataHeader, String contentType) {
        UUID uploadId = UUID.randomUUID();
        String filename = parseFilenameFromMetadata(metadataHeader);
        if (filename == null) {
            filename = uploadId + ".mp4";
        }

        UploadMetadata metadata = new UploadMetadata(
                uploadId,
                filename,
                uploadLength,
                contentType != null ? contentType : "video/mp4",
                UploadStatus.CREATED
        );

        // Ensure staging directory exists
        try {
            Files.createDirectories(Paths.get(stagingDir));
            Path stagingFile = getStagingFilePath(uploadId);
            Files.createFile(stagingFile);
        } catch (IOException e) {
            log.error("Failed to initialize staging file for upload {}: {}", uploadId, e.getMessage());
            throw new RuntimeException("Could not initialize upload staging file", e);
        }

        uploadRegistry.put(uploadId, metadata);
        log.info("Initialized TUS upload: uploadId={}, length={}, filename={}", uploadId, uploadLength, filename);
        return metadata;
    }

    @Override
    public long appendChunk(UUID uploadId, long offset, InputStream chunkStream, long chunkLength) {
        UploadMetadata metadata = uploadRegistry.get(uploadId);
        if (metadata == null) {
            throw new IllegalArgumentException("Upload resource not found: " + uploadId);
        }

        synchronized (metadata) {
            if (offset != metadata.getUploadOffset()) {
                throw new IllegalStateException(String.format(
                        "Upload-Offset mismatch for %s: expected %d, got %d",
                        uploadId, metadata.getUploadOffset(), offset));
            }

            Path stagingPath = getStagingFilePath(uploadId);
            File stagingFile = stagingPath.toFile();

            try (RandomAccessFile raf = new RandomAccessFile(stagingFile, "rw")) {
                raf.seek(offset);
                byte[] buffer = new byte[8192];
                long totalBytesRead = 0;
                int read;
                while (totalBytesRead < chunkLength && (read = chunkStream.read(buffer, 0, (int) Math.min(buffer.length, chunkLength - totalBytesRead))) != -1) {
                    raf.write(buffer, 0, read);
                    totalBytesRead += read;
                }

                long newOffset = offset + totalBytesRead;
                metadata.setUploadOffset(newOffset);
                metadata.setStatus(UploadStatus.UPLOADING);

                log.debug("Appended chunk to upload {}: bytesWritten={}, newOffset={}/{}",
                        uploadId, totalBytesRead, newOffset, metadata.getUploadLength());

                // Check if upload is completely assembled
                if (newOffset >= metadata.getUploadLength()) {
                    completeUploadAndEnqueueTranscode(metadata, stagingFile);
                }

                return newOffset;
            } catch (IOException e) {
                log.error("Failed to append chunk to upload {}: {}", uploadId, e.getMessage(), e);
                metadata.setStatus(UploadStatus.FAILED);
                throw new RuntimeException("Error writing chunk to staging file", e);
            }
        }
    }

    @Override
    public UploadMetadata getUpload(UUID uploadId) {
        return uploadRegistry.get(uploadId);
    }

    @Override
    public void terminateUpload(UUID uploadId) {
        UploadMetadata metadata = uploadRegistry.remove(uploadId);
        if (metadata != null) {
            try {
                Files.deleteIfExists(getStagingFilePath(uploadId));
                log.info("Terminated upload {} and purged staging file", uploadId);
            } catch (IOException e) {
                log.warn("Failed to delete staging file for upload {}: {}", uploadId, e.getMessage());
            }
        }
    }

    @Override
    public UploadStatusResponse getUploadStatus(UUID uploadId) {
        UploadMetadata metadata = uploadRegistry.get(uploadId);
        if (metadata == null) {
            return null;
        }

        String hlsPlaylistUrl = null;
        if (metadata.getStatus() == UploadStatus.COMPLETED || metadata.getStatus() == UploadStatus.QUEUED_FOR_TRANSCODING) {
            hlsPlaylistUrl = String.format("/media-storage/%s/hls/%s/master.m3u8",
                    minioConfig.getBucket(), uploadId);
        }

        return new UploadStatusResponse(
                metadata.getUploadId(),
                metadata.getStatus(),
                metadata.getOriginalFilename(),
                metadata.getUploadLength(),
                metadata.getUploadOffset(),
                metadata.getMinioObjectKey(),
                hlsPlaylistUrl,
                metadata.getCreatedAt(),
                metadata.getCompletedAt(),
                "Upload status: " + metadata.getStatus()
        );
    }

    private void completeUploadAndEnqueueTranscode(UploadMetadata metadata, File stagingFile) {
        UUID uploadId = metadata.getUploadId();
        String objectKey = "raw/" + uploadId + ".mp4";
        String targetPrefix = "hls/" + uploadId + "/";

        log.info("Assembling and streaming master video to MinIO: uploadId={}, objectKey={}, size={} bytes",
                uploadId, objectKey, stagingFile.length());

        try (InputStream is = new FileInputStream(stagingFile)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .object(objectKey)
                    .stream(is, stagingFile.length(), -1)
                    .contentType(metadata.getContentType())
                    .build());

            metadata.setMinioObjectKey(objectKey);
            metadata.setStatus(UploadStatus.QUEUED_FOR_TRANSCODING);
            metadata.setCompletedAt(Instant.now());

            // Emit asynchronous transcode job to RabbitMQ
            TranscodeJobMessage jobMessage = TranscodeJobMessage.of(
                    uploadId,
                    minioConfig.getBucket(),
                    objectKey,
                    targetPrefix
            );

            log.info("Publishing hardware transcode task to RabbitMQ: jobId={}, uploadId={}",
                    jobMessage.jobId(), uploadId);
            rabbitTemplate.convertAndSend(exchange, routingKey, jobMessage);

            // Clean up local staging file to bound host disk consumption
            Files.deleteIfExists(stagingFile.toPath());
            log.info("Successfully uploaded raw video and enqueued transcode for uploadId={}", uploadId);

        } catch (Exception e) {
            log.error("Failed to complete MinIO upload / RabbitMQ dispatch for {}: {}", uploadId, e.getMessage(), e);
            metadata.setStatus(UploadStatus.FAILED);
            throw new RuntimeException("MinIO upload or RabbitMQ dispatch failure", e);
        }
    }

    private Path getStagingFilePath(UUID uploadId) {
        return Paths.get(stagingDir, uploadId.toString() + ".part");
    }

    private String parseFilenameFromMetadata(String metadataHeader) {
        if (metadataHeader == null || metadataHeader.isBlank()) {
            return null;
        }
        for (String pair : metadataHeader.split(",")) {
            String[] kv = pair.trim().split(" ");
            if (kv.length == 2 && ("filename".equalsIgnoreCase(kv[0]) || "name".equalsIgnoreCase(kv[0]))) {
                try {
                    return new String(Base64.getDecoder().decode(kv[1]), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }
}
