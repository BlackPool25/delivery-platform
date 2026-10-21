package com.delivery.media.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Value("${minio.bucket:delivery-media}")
    private String bucket;

    @Bean
    public MinioClient minioClient() {
        log.info("Connecting to MinIO S3 cluster at {}", endpoint);
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                log.info("Creating default sovereign media bucket: {}", bucket);
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            } else {
                log.info("Media bucket {} already exists and is active", bucket);
            }
        } catch (Exception e) {
            log.warn("Could not auto-verify bucket '{}' at startup (may be initialized later): {}", bucket, e.getMessage());
        }

        return client;
    }

    public String getBucket() {
        return bucket;
    }
}
