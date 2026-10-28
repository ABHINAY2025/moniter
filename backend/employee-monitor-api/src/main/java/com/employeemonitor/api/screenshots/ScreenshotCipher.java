package com.employeemonitor.api.screenshots;

import com.employeemonitor.api.common.ApiException;
import org.springframework.http.HttpStatus;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption for screenshot bytes at rest (a random 12-byte IV is prepended to the ciphertext). Shared
 * by every {@link ScreenshotStorage} backend (filesystem or database) so the encryption is identical regardless of
 * where the bytes live. With no key configured it passes bytes through unchanged (acceptable only for local dev).
 */
public final class ScreenshotCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public ScreenshotCipher(String base64Key) {
        this.key = parseKey(base64Key);
    }

    /** Whether bytes are actually encrypted (a key is configured). */
    public boolean enabled() {
        return key != null;
    }

    public byte[] encrypt(byte[] plain) {
        if (key == null) {
            return plain;
        }
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

    public byte[] decrypt(byte[] stored) {
        if (key == null) {
            return stored;
        }
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
