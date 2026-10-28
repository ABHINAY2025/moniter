package com.employeemonitor.api.screenshots;

/**
 * Object storage for screenshot bytes. The default implementation is an encrypted local filesystem store; an
 * S3/MinIO implementation can be dropped in behind this interface without touching the rest of the module.
 * Bytes are never exposed directly - retrieval always goes through the authorized, audited service.
 */
public interface ScreenshotStorage {

    /** Whether bytes are encrypted at rest by this store. */
    boolean encryptsAtRest();

    void put(String key, byte[] data);

    byte[] get(String key);

    void delete(String key);
}
