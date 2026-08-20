package com.bocollections.backend.service.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Default storage backend — writes under a local directory. Good for local dev and
 * simple self-hosted deployments; swap to S3StorageService via app.storage.mode for
 * cloud/CDN-backed storage.
 */
@Service
@ConditionalOnProperty(name = "app.storage.mode", havingValue = "local", matchIfMissing = true)
@Slf4j
public class LocalFilesystemStorageService implements StorageService {

    private final Path root;

    public LocalFilesystemStorageService(@Value("${app.storage.local-path:./data/scan-photos}") String localPath) {
        this.root = Path.of(localPath);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create storage directory: " + root, e);
        }
    }

    @Override
    public String store(byte[] bytes, String contentType) {
        String key = UUID.randomUUID() + StorageService.extensionFor(contentType);
        try {
            Files.write(resolve(key), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file: " + key, e);
        }
        return key;
    }

    @Override
    public Resource load(String key) {
        return new FileSystemResource(resolve(validateKey(key)));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(validateKey(key)));
        } catch (IOException e) {
            log.warn("Failed to delete stored file: {}", key, e);
        }
    }

    @Override
    public String publicUrl(String key) {
        return "/media/" + key;
    }

    /** Keys are always UUID + a fixed extension we generated — reject anything else to rule out path traversal. */
    private String validateKey(String key) {
        if (!key.matches("^[a-fA-F0-9-]{36}\\.[a-z]{3,4}$")) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return key;
    }

    private Path resolve(String key) {
        return root.resolve(key).normalize();
    }
}
