package com.bocollections.backend.service.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.UUID;

/**
 * Remote storage backend — writes to an S3-compatible bucket (AWS S3, or anything
 * S3-compatible sitting behind a CDN, e.g. Cloudflare R2/MinIO). Credentials/region
 * come from the standard AWS SDK default provider chain (env vars, profile, etc.) —
 * nothing bespoke here. Selected via app.storage.mode=s3.
 */
@Service
@ConditionalOnProperty(name = "app.storage.mode", havingValue = "s3")
@Slf4j
public class S3StorageService implements StorageService {

    private final S3Client s3;
    private final String bucket;
    private final String publicBaseUrl;

    public S3StorageService(
            @Value("${app.storage.s3.region:us-east-1}") String region,
            @Value("${app.storage.s3.bucket}") String bucket,
            @Value("${app.storage.s3.public-base-url}") String publicBaseUrl) {
        this.s3 = S3Client.builder().region(Region.of(region)).build();
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
    }

    @Override
    public String store(byte[] bytes, String contentType) {
        String key = UUID.randomUUID() + StorageService.extensionFor(contentType);
        s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(bytes));
        return key;
    }

    @Override
    public Resource load(String key) {
        try {
            return new InputStreamResource(s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()));
        } catch (S3Exception e) {
            throw new IllegalArgumentException("Could not load stored file: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            log.warn("Failed to delete stored object: {}", key, e);
        }
    }

    @Override
    public String publicUrl(String key) {
        return publicBaseUrl + key;
    }
}
