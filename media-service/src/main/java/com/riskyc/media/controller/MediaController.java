package com.riskyc.media.controller;

import com.riskyc.media.config.MinioProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Issues pre-signed MinIO URLs so the mobile client uploads/downloads media
 * directly to object storage instead of proxying bytes through this service.
 * Clients are expected to encrypt media before upload (see architecture notes
 * on end-to-end encryption) so MinIO only ever stores ciphertext.
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MediaController(@Qualifier("presigningMinioClient") MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    public record UploadUrlResponse(String objectKey, String uploadUrl) {
    }

    public record DownloadUrlResponse(String downloadUrl) {
    }

    @PostMapping("/upload-url")
    public UploadUrlResponse createUploadUrl() throws Exception {
        String objectKey = UUID.randomUUID().toString();
        String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket(properties.bucket())
                .object(objectKey)
                .expiry(15, TimeUnit.MINUTES)
                .build());
        return new UploadUrlResponse(objectKey, url);
    }

    @GetMapping("/{objectKey}/download-url")
    public DownloadUrlResponse createDownloadUrl(@PathVariable String objectKey) throws Exception {
        String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(properties.bucket())
                .object(objectKey)
                .expiry(15, TimeUnit.MINUTES)
                .build());
        return new DownloadUrlResponse(url);
    }
}
