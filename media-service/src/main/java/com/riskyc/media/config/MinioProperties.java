package com.riskyc.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code endpoint} is the Docker-internal address media-service itself uses to
 * talk to MinIO (bucket admin calls). {@code publicEndpoint} is what gets baked
 * into presigned URLs handed to clients — it must be reachable from wherever
 * the mobile app runs, which "minio" (a Docker Compose service name) never is.
 */
@ConfigurationProperties(prefix = "riskyc.minio")
public record MinioProperties(String endpoint, String publicEndpoint, String accessKey, String secretKey,
                               String bucket, String region) {
}
