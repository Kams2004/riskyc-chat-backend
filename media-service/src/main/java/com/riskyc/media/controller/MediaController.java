package com.riskyc.media.controller;

import com.riskyc.media.config.MinioProperties;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Issues pre-signed MinIO URLs so the mobile client uploads/downloads media
 * directly to object storage instead of proxying bytes through this service.
 * Clients are expected to encrypt media before upload (see architecture notes
 * on end-to-end encryption) so MinIO only ever stores ciphertext.
 *
 * Documented MVP gap: this controller has NO authentication check of any
 * kind today — not even bare JWT signature verification, let alone the
 * session-revocation checks added to auth-service/messaging-service. Anyone
 * who can reach this service can mint a presigned upload/download URL for
 * any objectKey. This service is Postgres-free (MinIO-only), so wiring in
 * JwtIssuer + the revocation checks the other two services now have would
 * be a reasonable next step, not attempted in this pass.
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    /** Small slack over the client's own 30s status-video cap, not a real limit of its own. */
    private static final long MAX_TRIM_WINDOW_MS = 35_000;

    private final MinioClient presigningMinioClient;
    /** Docker-internal client (see MinioConfig#minioClient) — trimVideo below is the one place in this service that actually reads/writes object bytes itself, everything else only ever hands out presigned URLs. */
    private final MinioClient internalMinioClient;
    private final MinioProperties properties;

    public MediaController(@Qualifier("presigningMinioClient") MinioClient presigningMinioClient,
                            @Qualifier("minioClient") MinioClient internalMinioClient, MinioProperties properties) {
        this.presigningMinioClient = presigningMinioClient;
        this.internalMinioClient = internalMinioClient;
        this.properties = properties;
    }

    public record UploadUrlResponse(String objectKey, String uploadUrl) {
    }

    public record DownloadUrlResponse(String downloadUrl) {
    }

    @PostMapping("/upload-url")
    public UploadUrlResponse createUploadUrl() throws Exception {
        String objectKey = UUID.randomUUID().toString();
        String url = presigningMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket(properties.bucket())
                .object(objectKey)
                .expiry(15, TimeUnit.MINUTES)
                .build());
        return new UploadUrlResponse(objectKey, url);
    }

    @GetMapping("/{objectKey}/download-url")
    public DownloadUrlResponse createDownloadUrl(@PathVariable String objectKey) throws Exception {
        String url = presigningMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(properties.bucket())
                .object(objectKey)
                .expiry(15, TimeUnit.MINUTES)
                .build());
        return new DownloadUrlResponse(url);
    }

    public record TrimVideoRequest(String objectKey, long startMs, long endMs) {
    }

    public record TrimVideoResponse(String objectKey) {
    }

    /**
     * The one exception to "this service never touches media bytes" above —
     * a status video's trim window (see mobile's VideoTrimmer) can't be
     * expressed as just an upload URL, so the full video (already uploaded
     * via the normal presigned-PUT flow, objectKey unchanged) is fetched
     * back from MinIO here, cut with ffmpeg using stream copy (-c copy —
     * repackages the selected byte range into a new container without
     * decoding/re-encoding, so no quality loss, at the cost of snapping the
     * actual start point to the nearest preceding keyframe rather than
     * being frame-exact), and the trimmed result is uploaded under a NEW
     * objectKey — the original is left untouched. -ss before -i is an input
     * seek (fast); -t after -i is an explicit output duration in seconds,
     * avoiding any ambiguity about whether -to would be relative to the
     * seek point or the original timeline.
     */
    @PostMapping("/trim-video")
    public TrimVideoResponse trimVideo(@RequestBody TrimVideoRequest request) throws Exception {
        if (request.startMs() < 0 || request.endMs() <= request.startMs()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid trim range");
        }
        if (request.endMs() - request.startMs() > MAX_TRIM_WINDOW_MS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trim window too long");
        }

        Path inputFile = Files.createTempFile("riskyc-trim-in-", ".mp4");
        Path outputFile = Files.createTempFile("riskyc-trim-out-", ".mp4");
        try {
            try (InputStream in = internalMinioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(request.objectKey())
                    .build())) {
                Files.copy(in, inputFile, StandardCopyOption.REPLACE_EXISTING);
            }

            double startSec = request.startMs() / 1000.0;
            double durationSec = (request.endMs() - request.startMs()) / 1000.0;
            Process process = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-ss", String.valueOf(startSec),
                    "-i", inputFile.toAbsolutePath().toString(),
                    "-t", String.valueOf(durationSec),
                    "-c", "copy",
                    outputFile.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Video trim timed out");
            }
            if (process.exitValue() != 0 || Files.size(outputFile) == 0) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Video trim failed");
            }

            String trimmedObjectKey = UUID.randomUUID().toString();
            try (InputStream out = Files.newInputStream(outputFile)) {
                internalMinioClient.putObject(PutObjectArgs.builder()
                        .bucket(properties.bucket())
                        .object(trimmedObjectKey)
                        .stream(out, Files.size(outputFile), -1)
                        .contentType("video/mp4")
                        .build());
            }
            return new TrimVideoResponse(trimmedObjectKey);
        } finally {
            Files.deleteIfExists(inputFile);
            Files.deleteIfExists(outputFile);
        }
    }
}
