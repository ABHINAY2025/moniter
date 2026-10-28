package com.employeemonitor.api.updates;

import com.employeemonitor.api.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Stores signed agent packages in PostgreSQL (bytea). Selected with {@code app.updates.storage=database}. */
@Component
@ConditionalOnProperty(name = "app.updates.storage", havingValue = "database")
public class DatabaseUpdatePackageStorage implements UpdatePackageStorage {

    private static final Logger log = LoggerFactory.getLogger(DatabaseUpdatePackageStorage.class);

    private final UpdateBlobRepository blobs;

    public DatabaseUpdatePackageStorage(UpdateBlobRepository blobs) {
        this.blobs = blobs;
        log.info("Agent update packages stored in PostgreSQL");
    }

    @Override
    public void put(String key, byte[] data) {
        blobs.save(new UpdateBlob(key, data, Instant.now()));
    }

    @Override
    public byte[] get(String key) {
        return blobs.findById(key)
                .map(UpdateBlob::getData)
                .orElseThrow(() -> ApiException.notFound("Package", key));
    }
}
