package com.riskyc.media.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    private final MinioProperties properties;

    public MinioConfig(MinioProperties properties) {
        this.properties = properties;
    }

    /** Used for server-side admin calls (bucket create/exists) over the Docker-internal network. */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    /** Used only to generate presigned URLs, so the host baked into them is one clients can reach. */
    @Bean
    @Qualifier("presigningMinioClient")
    public MinioClient presigningMinioClient() {
        return MinioClient.builder()
                .endpoint(properties.publicEndpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    /**
     * Ensures the media bucket exists on startup so local/dev environments
     * work without a manual `mc mb` step. Bucket provisioning for prod should
     * be managed via infra-as-code instead of relying on this.
     */
    @Component
    static class BucketInitializer {
        private final MinioClient minioClient;
        private final MinioProperties properties;

        BucketInitializer(MinioClient minioClient, MinioProperties properties) {
            this.minioClient = minioClient;
            this.properties = properties;
        }

        @PostConstruct
        void ensureBucketExists() throws Exception {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
            }
        }
    }
}
