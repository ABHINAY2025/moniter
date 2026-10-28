package com.employeemonitor.api.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Opaque token generation and hashing. Only hashes of opaque tokens are ever persisted. */
public final class SecureTokens {

    public static final String REFRESH_PREFIX = "rt_";
    public static final String DEVICE_PREFIX = "dvt_";
    public static final String ENROLLMENT_PREFIX = "enr_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureTokens() {
    }

    public static String generate(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
