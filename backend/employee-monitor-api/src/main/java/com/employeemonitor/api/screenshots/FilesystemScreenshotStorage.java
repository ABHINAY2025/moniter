package com.employeemonitor.api.screenshots;

import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Stores screenshot bytes under a base directory, optionally encrypted with AES-256-GCM (a random 12-byte IV is
 * prepended to the ciphertext). The AES key comes from configuration/environment; with no key configured the
 * store writes raw bytes and logs a warning (acceptable only for local development).
 */
@Component
public class FilesystemScreenshotStorage implements ScreenshotStorage {

    private static final Logger log = LoggerFactory.getLogger(FilesystemScreenshotStorage.class);
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final Path baseDir;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public FilesystemScreenshotStorage(AppProperties.Screenshots props) {
        this.baseDir = Path.of(props.directory());
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create screenshot directory " + baseDir, e);
        }
        this.key = parseKey(props.encryptionKey());
        if (key == null) {
            log.warn("SCREENSHOT_ENCRYPTION_KEY not set; screenshots are stored UNENCRYPTED. Set a 32-byte "
                    + "base64 key in production.");
        } else {
            log.info("Screenshot storage at {} with AES-256-GCM encryption at rest", baseDir);
        }
    }

    @Override
    public boolean encryptsAtRest() {
        return key != null;
    }

    @Override
    public void put(String key, byte[] data) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            byte[] toWrite = this.key == null ? data : encrypt(data);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, toWrite);
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
            byte[] raw = Files.readAllBytes(target);
            return this.key == null ? raw : decrypt(raw);
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

    private byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Encryption failed");
        }
    }

    private byte[] decrypt(byte[] stored) {
        try {
            byte[] iv = Arrays.copyOfRange(stored, 0, IV_LENGTH);
            byte[] ct = Arrays.copyOfRange(stored, IV_LENGTH, stored.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ct);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Decryption failed");
        }
    }

    private static SecretKey parseKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(base64.trim());
        if (bytes.length != 32) {
            throw new IllegalStateException("SCREENSHOT_ENCRYPTION_KEY must be 32 bytes (base64-encoded)");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
