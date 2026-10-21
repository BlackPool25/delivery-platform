package com.delivery.media.service;

import com.delivery.media.config.MinioConfig;
import com.delivery.media.model.TranscodeJobMessage;
import com.delivery.media.model.UploadStatus;
import com.delivery.media.model.UploadStatusResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

@Service
public class DirectUploadServiceImpl implements DirectUploadService {

    private static final Logger log = LoggerFactory.getLogger(DirectUploadServiceImpl.class);

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;
    private final RabbitTemplate rabbitTemplate;

    @Value("${media.rabbitmq.exchange:video.transcode.exchange}")
    private String exchange;

    @Value("${media.rabbitmq.routing-key:video.transcode.job}")
    private String routingKey;

    public DirectUploadServiceImpl(MinioClient minioClient,
                                  MinioConfig minioConfig,
                                  RabbitTemplate rabbitTemplate) {
        this.minioClient = minioClient;
        this.minioConfig = minioConfig;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public UploadStatusResponse uploadMultipart(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file cannot be empty");
        }

        UUID uploadId = UUID.randomUUID();
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "video.mp4";
        String objectKey = "raw/" + uploadId + ".mp4";
        String targetPrefix = "hls/" + uploadId + "/";

        log.info("Direct multipart upload received: uploadId={}, filename={}, size={}",
                uploadId, originalFilename, file.getSize());

        try (InputStream is = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .object(objectKey)
                    .stream(is, file.getSize(), -1)
                    .contentType(file.getContentType() != null ? file.getContentType() : "video/mp4")
                    .build());

            // Dispatch transcode job to RabbitMQ
            TranscodeJobMessage job = TranscodeJobMessage.of(
                    uploadId,
                    minioConfig.getBucket(),
                    objectKey,
                    targetPrefix
            );

            rabbitTemplate.convertAndSend(exchange, routingKey, job);

            String hlsUrl = String.format("/media-storage/%s/hls/%s/master.m3u8",
                    minioConfig.getBucket(), uploadId);

            return new UploadStatusResponse(
                    uploadId,
                    UploadStatus.QUEUED_FOR_TRANSCODING,
                    originalFilename,
                    file.getSize(),
                    file.getSize(),
                    objectKey,
                    hlsUrl,
                    Instant.now(),
                    Instant.now(),
                    "Video uploaded successfully and queued for hardware transcoding"
            );
        } catch (Exception e) {
            log.error("Failed to upload video to MinIO: {}", e.getMessage(), e);
            throw new RuntimeException("Direct upload failed", e);
        }
    }
}
