package com.employeemonitor.api.screenshots;

import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Default store: screenshot bytes under a base directory, encrypted at rest with AES-256-GCM (see
 * {@link ScreenshotCipher}). Selected when {@code app.screenshots.storage} is unset or {@code filesystem}.
 * An S3/MinIO implementation can be dropped in behind {@link ScreenshotStorage} the same way.
 */
@Component
@ConditionalOnProperty(name = "app.screenshots.storage", havingValue = "filesystem", matchIfMissing = true)
public class FilesystemScreenshotStorage implements ScreenshotStorage {

    private static final Logger log = LoggerFactory.getLogger(FilesystemScreenshotStorage.class);

    private final Path baseDir;
    private final ScreenshotCipher cipher;

    public FilesystemScreenshotStorage(AppProperties.Screenshots props) {
        this.baseDir = Path.of(props.directory());
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create screenshot directory " + baseDir, e);
        }
        this.cipher = new ScreenshotCipher(props.encryptionKey());
        if (cipher.enabled()) {
            log.info("Screenshot storage at {} with AES-256-GCM encryption at rest", baseDir);
        } else {
            log.warn("SCREENSHOT_ENCRYPTION_KEY not set; screenshots are stored UNENCRYPTED. Set a 32-byte "
                    + "base64 key in production.");
        }
    }

    @Override
    public boolean encryptsAtRest() {
        return cipher.enabled();
    }

    @Override
    public void put(String key, byte[] data) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, cipher.encrypt(data));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Could not store screenshot");
        }
    }

    @Override
    public byte[] get(String key) {
        Path target = resolve(key);
        if (!Files.exists(target)) {
            throw ApiException.notFound("Screenshot blob", key);
        }
        try {
            return cipher.decrypt(Files.readAllBytes(target));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Could not read screenshot");
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            log.warn("Could not delete screenshot blob {}: {}", key, e.getMessage());
        }
    }

    private Path resolve(String storageKey) {
        // Shard into subdirectories by the first two characters to avoid one huge directory.
        String safe = storageKey.replaceAll("[^A-Za-z0-9._-]", "_");
        String shard = safe.length() >= 2 ? safe.substring(0, 2) : "00";
        return baseDir.resolve(shard).resolve(safe).normalize();
    }
}
