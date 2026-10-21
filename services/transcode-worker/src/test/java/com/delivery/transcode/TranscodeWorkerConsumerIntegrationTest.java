package com.delivery.transcode;

import com.delivery.transcode.model.TranscodeJobMessage;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TranscodeWorkerConsumerIntegrationTest {

    @Container
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3-management-alpine")
    );

    @Container
    static GenericContainer<?> minioContainer = new GenericContainer<>(
            DockerImageName.parse("minio/minio:latest")
    )
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000);

    @DynamicPropertySource
    static void configureEnvironment(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbitMQContainer::getHost);
        registry.add("spring.rabbitmq.port", rabbitMQContainer::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitMQContainer::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitMQContainer::getAdminPassword);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");

        registry.add("minio.endpoint", () -> "http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(9000));
        registry.add("minio.access-key", () -> "minioadmin");
        registry.add("minio.secret-key", () -> "minioadmin");
        registry.add("minio.bucket", () -> "delivery-media-worker-test");
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MinioClient minioClient;

    @Test
    @Order(1)
    @DisplayName("End-to-End Transcode Worker: RabbitMQ job consumption, MinIO raw fetch, transcoding, and HLS upload")
    void testEndToEndTranscodeWorkerFlow() throws Exception {
        String bucket = "delivery-media-worker-test";
        UUID uploadId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String sourceKey = "raw/" + uploadId + ".mp4";
        String targetPrefix = "hls/" + uploadId + "/";

        // Step 1: Ensure bucket exists
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }

        // Step 2: Upload source test video to MinIO
        File cachedVideo = new File("/tmp/test_30s_1080p.mp4");
        assertThat(cachedVideo.exists()).isTrue();

        try (InputStream is = new FileInputStream(cachedVideo)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(sourceKey)
                    .stream(is, cachedVideo.length(), -1)
                    .contentType("video/mp4")
                    .build());
        }

        // Step 3: Declare queue and publish job to RabbitMQ
        rabbitTemplate.execute(channel -> {
            channel.queueDeclare("video.transcode.jobs", true, false, false, null);
            return null;
        });

        TranscodeJobMessage job = new TranscodeJobMessage(
                jobId,
                uploadId,
                bucket,
                sourceKey,
                targetPrefix,
                java.time.Instant.now()
        );

        rabbitTemplate.convertAndSend("video.transcode.jobs", job);

        // Step 4: Await HLS ladder artifacts in MinIO (up to 30s)
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .ignoreExceptionsInstanceOf(io.minio.errors.ErrorResponseException.class)
                .untilAsserted(() -> {
                    try (InputStream s3is = minioClient.getObject(
                            GetObjectArgs.builder()
                                    .bucket(bucket)
                                    .object(targetPrefix + "master.m3u8")
                                    .build())) {
                        String masterContent = new String(s3is.readAllBytes(), StandardCharsets.UTF_8);
                        assertThat(masterContent).contains("stream_0.m3u8");
                        assertThat(masterContent).contains("stream_1.m3u8");
                        assertThat(masterContent).contains("stream_2.m3u8");
                    }

                    try (InputStream is0 = minioClient.getObject(
                            GetObjectArgs.builder().bucket(bucket).object(targetPrefix + "stream_0.m3u8").build())) {
                        assertThat(is0.readAllBytes().length).isGreaterThan(0);
                    }
                    try (InputStream is1 = minioClient.getObject(
                            GetObjectArgs.builder().bucket(bucket).object(targetPrefix + "stream_1.m3u8").build())) {
                        assertThat(is1.readAllBytes().length).isGreaterThan(0);
                    }
                    try (InputStream is2 = minioClient.getObject(
                            GetObjectArgs.builder().bucket(bucket).object(targetPrefix + "stream_2.m3u8").build())) {
                        assertThat(is2.readAllBytes().length).isGreaterThan(0);
                    }
                });
    }
}
