package com.bocollections.backend.service.storage;

import org.springframework.core.io.Resource;

/**
 * Persists binary content (scan-session photos) behind a pluggable backend.
 * Selected via {@code app.storage.mode: local|s3} — see LocalFilesystemStorageService
 * and S3StorageService. Callers only ever store/retrieve a String key; the DB never
 * holds image bytes.
 */
public interface StorageService {

    String store(byte[] bytes, String contentType);

    Resource load(String key);

    void delete(String key);

    String publicUrl(String key);

    /** Shared so both implementations agree on the same content-type-to-extension mapping. */
    static String extensionFor(String contentType) {
        return "image/png".equals(contentType) ? ".png" : ".jpg";
    }
}
