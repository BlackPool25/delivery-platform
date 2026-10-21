package com.delivery.transcode.consumer;

import com.delivery.transcode.model.TranscodeJobMessage;
import com.delivery.transcode.model.TranscodeResult;
import com.delivery.transcode.pipeline.FfmpegPipelineExecutor;
import com.rabbitmq.client.Channel;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

@Component
public class TranscodeJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(TranscodeJobConsumer.class);

    private final FfmpegPipelineExecutor pipelineExecutor;
    private final MinioClient minioClient;

    @Value("${transcode.temp-dir:/tmp/transcode}")
    private String baseTempDir;

    public TranscodeJobConsumer(FfmpegPipelineExecutor pipelineExecutor, MinioClient minioClient) {
        this.pipelineExecutor = pipelineExecutor;
        this.minioClient = minioClient;
    }

    @RabbitListener(
            queues = "${transcode.rabbitmq.queue:video.transcode.jobs}",
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void consumeTranscodeJob(TranscodeJobMessage job,
                                   Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        UUID uploadId = job.uploadId();
        log.info("Received transcoding task from RabbitMQ: jobId={}, uploadId={}, bucket={}, sourceKey={}",
                job.jobId(), uploadId, job.bucket(), job.sourceKey());

        Path jobDir = Paths.get(baseTempDir, uploadId.toString());
        Path hlsDir = jobDir.resolve("hls");
        Path inputFilePath = jobDir.resolve("input.mp4");

        try {
            Files.createDirectories(hlsDir);

            // Step 1: Download raw source video from MinIO S3
            log.info("Downloading raw video from MinIO S3: {}/{} -> {}", job.bucket(), job.sourceKey(), inputFilePath);
            try (InputStream s3Stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(job.bucket())
                            .object(job.sourceKey())
                            .build())) {
                Files.copy(s3Stream, inputFilePath);
            }

            // Step 2: Execute hardware VAAPI (or fallback) FFmpeg pipeline
            TranscodeResult result = pipelineExecutor.execute(
                    job.jobId(),
                    uploadId,
                    inputFilePath.toFile(),
                    hlsDir.toFile()
            );

            if (!result.success()) {
                throw new RuntimeException("Transcode execution failed: " + result.errorMessage());
            }

            // Step 3: Stream generated HLS ladder assets to MinIO S3
            String targetPrefix = job.targetPrefix();
            log.info("Uploading {} HLS ladder files to MinIO: targetPrefix={}",
                    result.generatedFiles().size(), targetPrefix);

            for (String relativeFilename : result.generatedFiles()) {
                File file = hlsDir.resolve(relativeFilename).toFile();
                String s3Key = targetPrefix + relativeFilename;
                String contentType = determineContentType(relativeFilename);

                try (InputStream fis = new FileInputStream(file)) {
                    minioClient.putObject(PutObjectArgs.builder()
                            .bucket(job.bucket())
                            .object(s3Key)
                            .stream(fis, file.length(), -1)
                            .contentType(contentType)
                            .build());
                }
            }

            // Step 4: Acknowledge RabbitMQ message
            channel.basicAck(deliveryTag, false);
            log.info("Hardware transcoding task completed & acknowledged: uploadId={}, duration={}ms",
                    uploadId, result.duration().toMillis());

        } catch (Exception e) {
            log.error("Fatal failure in transcode worker for uploadId={}: {}", uploadId, e.getMessage(), e);
            try {
                // Reject and route to Dead Letter Queue (DLQ)
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ackEx) {
                log.error("Failed to basicNack message: {}", ackEx.getMessage());
            }
        } finally {
            // Step 5: Clean up temporary files on disk
            deleteDirectoryQuietly(jobDir);
        }
    }

    private String determineContentType(String filename) {
        if (filename.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        } else if (filename.endsWith(".m4s")) {
            return "video/iso.segment";
        } else if (filename.endsWith(".mp4")) {
            return "video/mp4";
        }
        return "application/octet-stream";
    }

    private void deleteDirectoryQuietly(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (Exception e) {
            log.warn("Could not cleanly purge temporary directory {}: {}", path, e.getMessage());
        }
    }
}
