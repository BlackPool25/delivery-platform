package com.delivery.media;

import com.delivery.media.model.TranscodeJobMessage;
import com.delivery.media.model.UploadStatus;
import com.delivery.media.model.UploadStatusResponse;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MediaUploadTusIntegrationTest {

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

        registry.add("minio.endpoint", () -> "http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(9000));
        registry.add("minio.access-key", () -> "minioadmin");
        registry.add("minio.secret-key", () -> "minioadmin");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MinioClient minioClient;

    private String getBaseUrl() {
        return "http://localhost:" + port + "/api/v1/media/uploads";
    }

    @Test
    @Order(1)
    @DisplayName("Verify TUS OPTIONS discovery handshake returns compliant protocol headers")
    void testTusOptions() {
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Void> response = restTemplate.exchange(
                getBaseUrl(),
                HttpMethod.OPTIONS,
                request,
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getHeaders().getFirst("Tus-Resumable")).isEqualTo("1.0.0");
        assertThat(response.getHeaders().getFirst("Tus-Version")).isEqualTo("1.0.0");
        assertThat(response.getHeaders().getFirst("Tus-Extension")).contains("creation");
    }

    @Test
    @Order(2)
    @DisplayName("Verification Gate: Chunked TUS video upload, assembly, MinIO storage, and RabbitMQ dispatch")
    void testChunkedTusUploadAndRabbitMqJobDispatch() throws Exception {
        int totalSize = 65536; // 64 KB test video stream
        int chunkSize = 32768; // 32 KB chunk size

        byte[] chunk1 = new byte[chunkSize];
        byte[] chunk2 = new byte[chunkSize];
        for (int i = 0; i < chunkSize; i++) {
            chunk1[i] = (byte) (i % 128);
            chunk2[i] = (byte) ((i + chunkSize) % 128);
        }

        String metadataHeader = "filename " + Base64.getEncoder().encodeToString("food_reel_1080p.mp4".getBytes());

        // Step 1: POST to initiate upload
        HttpHeaders postHeaders = new HttpHeaders();
        postHeaders.set("Upload-Length", String.valueOf(totalSize));
        postHeaders.set("Upload-Metadata", metadataHeader);
        postHeaders.set("Tus-Resumable", "1.0.0");

        HttpEntity<Void> postRequest = new HttpEntity<>(postHeaders);
        ResponseEntity<Void> postResponse = restTemplate.postForEntity(getBaseUrl(), postRequest, Void.class);

        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String location = postResponse.getHeaders().getFirst("Location");
        assertThat(location).isNotBlank();

        UUID uploadId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        String uploadResourceUrl = "http://localhost:" + port + location;

        // Step 2: HEAD before sending chunks (verify offset is 0)
        HttpHeaders headHeaders = new HttpHeaders();
        headHeaders.set("Tus-Resumable", "1.0.0");
        ResponseEntity<Void> headResponse1 = restTemplate.exchange(
                uploadResourceUrl,
                HttpMethod.HEAD,
                new HttpEntity<>(headHeaders),
                Void.class
        );
        assertThat(headResponse1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(headResponse1.getHeaders().getFirst("Upload-Offset")).isEqualTo("0");
        assertThat(headResponse1.getHeaders().getFirst("Upload-Length")).isEqualTo(String.valueOf(totalSize));

        // Step 3: PATCH chunk 1 (offset 0)
        HttpHeaders patch1Headers = new HttpHeaders();
        patch1Headers.set("Upload-Offset", "0");
        patch1Headers.set("Content-Type", "application/offset+octet-stream");
        patch1Headers.set("Tus-Resumable", "1.0.0");

        HttpEntity<byte[]> patch1Request = new HttpEntity<>(chunk1, patch1Headers);
        ResponseEntity<Void> patch1Response = restTemplate.exchange(
                uploadResourceUrl,
                HttpMethod.PATCH,
                patch1Request,
                Void.class
        );
        assertThat(patch1Response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(patch1Response.getHeaders().getFirst("Upload-Offset")).isEqualTo(String.valueOf(chunkSize));

        // Step 4: PATCH chunk 2 (offset 32768) to complete upload
        HttpHeaders patch2Headers = new HttpHeaders();
        patch2Headers.set("Upload-Offset", String.valueOf(chunkSize));
        patch2Headers.set("Content-Type", "application/offset+octet-stream");
        patch2Headers.set("Tus-Resumable", "1.0.0");

        HttpEntity<byte[]> patch2Request = new HttpEntity<>(chunk2, patch2Headers);
        ResponseEntity<Void> patch2Response = restTemplate.exchange(
                uploadResourceUrl,
                HttpMethod.PATCH,
                patch2Request,
                Void.class
        );
        assertThat(patch2Response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(patch2Response.getHeaders().getFirst("Upload-Offset")).isEqualTo(String.valueOf(totalSize));

        // Step 5: Verify upload status endpoint
        ResponseEntity<UploadStatusResponse> statusResponse = restTemplate.getForEntity(
                uploadResourceUrl + "/status",
                UploadStatusResponse.class
        );
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody()).isNotNull();
        assertThat(statusResponse.getBody().status()).isEqualTo(UploadStatus.QUEUED_FOR_TRANSCODING);
        assertThat(statusResponse.getBody().uploadLength()).isEqualTo(totalSize);
        assertThat(statusResponse.getBody().minioObjectKey()).isEqualTo("raw/" + uploadId + ".mp4");
        assertThat(statusResponse.getBody().hlsMasterPlaylistUrl()).contains("master.m3u8");

        // Step 6: Verify raw video file exists in MinIO
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket("delivery-media-test")
                        .object("raw/" + uploadId + ".mp4")
                        .build()
        )) {
            byte[] downloadedBytes = is.readAllBytes();
            assertThat(downloadedBytes.length).isEqualTo(totalSize);
            assertThat(downloadedBytes[0]).isEqualTo(chunk1[0]);
            assertThat(downloadedBytes[chunkSize]).isEqualTo(chunk2[0]);
        }

        // Step 7: Verify transcode task message was enqueued into RabbitMQ
        Object message = rabbitTemplate.receiveAndConvert("video.transcode.jobs", 5000);
        assertThat(message).isNotNull();
        assertThat(message).isInstanceOf(TranscodeJobMessage.class);
        TranscodeJobMessage transcodeJob = (TranscodeJobMessage) message;
        assertThat(transcodeJob.uploadId()).isEqualTo(uploadId);
        assertThat(transcodeJob.sourceKey()).isEqualTo("raw/" + uploadId + ".mp4");
        assertThat(transcodeJob.targetPrefix()).isEqualTo("hls/" + uploadId + "/");
    }
}
