package com.employeemonitor.api.screenshots;

import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Stores screenshot bytes in PostgreSQL (bytea), encrypted at rest with AES-256-GCM. Selected with
 * {@code app.screenshots.storage=database}. Keeps everything in one place (pg_dump backs it up, no filesystem
 * volume to manage); retention is still enforced by the service, which deletes the blob with the metadata.
 */
@Component
@ConditionalOnProperty(name = "app.screenshots.storage", havingValue = "database")
public class DatabaseScreenshotStorage implements ScreenshotStorage {

    private static final Logger log = LoggerFactory.getLogger(DatabaseScreenshotStorage.class);

    private final ScreenshotBlobRepository blobs;
    private final ScreenshotCipher cipher;

    public DatabaseScreenshotStorage(ScreenshotBlobRepository blobs, AppProperties.Screenshots props) {
        this.blobs = blobs;
        this.cipher = new ScreenshotCipher(props.encryptionKey());
        if (cipher.enabled()) {
            log.info("Screenshot storage in PostgreSQL with AES-256-GCM encryption at rest");
        } else {
            log.warn("SCREENSHOT_ENCRYPTION_KEY not set; screenshots are stored UNENCRYPTED in the database. "
                    + "Set a 32-byte base64 key in production.");
        }
    }

    @Override
    public boolean encryptsAtRest() {
        return cipher.enabled();
    }

    @Override
    public void put(String key, byte[] data) {
        blobs.save(new ScreenshotBlob(key, cipher.encrypt(data), Instant.now()));
    }

    @Override
    public byte[] get(String key) {
        return blobs.findById(key)
                .map(b -> cipher.decrypt(b.getData()))
                .orElseThrow(() -> ApiException.notFound("Screenshot blob", key));
    }

    @Override
    public void delete(String key) {
        blobs.deleteById(key);
    }
}
