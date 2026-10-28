package com.employeemonitor.api.updates;

import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stores signed agent packages on the filesystem. No encryption at rest is needed: integrity and authenticity are
 * guaranteed by the SHA-256 and the RSA signature the agent verifies before installing.
 */
@Component
public class UpdatePackageStorage {

    private final Path baseDir;

    public UpdatePackageStorage(AppProperties.Updates props) {
        this.baseDir = Path.of(props.directory());
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create updates directory " + baseDir, e);
        }
    }

    public void put(String key, byte[] data) {
        try {
            Path target = resolve(key);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Could not store package");
        }
    }

    public byte[] get(String key) {
        Path target = resolve(key);
        if (!Files.exists(target)) {
            throw ApiException.notFound("Package", key);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Could not read package");
        }
    }

    private Path resolve(String key) {
        String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
        return baseDir.resolve(safe).normalize();
    }
}
