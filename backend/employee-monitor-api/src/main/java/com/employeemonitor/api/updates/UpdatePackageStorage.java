package com.employeemonitor.api.updates;

/**
 * Storage for signed agent packages. No encryption at rest is needed: integrity and authenticity are guaranteed by
 * the SHA-256 and the RSA signature the agent verifies before installing. Backed by the filesystem by default, or by
 * PostgreSQL when {@code app.updates.storage=database}.
 */
public interface UpdatePackageStorage {

    void put(String key, byte[] data);

    byte[] get(String key);
}
