package com.employeemonitor.api.screenshots;

import com.employeemonitor.api.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemScreenshotStorageTest {

    private static final byte[] DATA = "the-screenshot-bytes-\0\1\2\3".getBytes();

    @Test
    void encryptsAtRestAndRoundTrips(@TempDir Path dir) throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        var storage = new FilesystemScreenshotStorage(
                new AppProperties.Screenshots(dir.toString(), key, 0));

        assertThat(storage.encryptsAtRest()).isTrue();
        storage.put("ab12.png", DATA);
        assertThat(storage.get("ab12.png")).isEqualTo(DATA);

        // On disk the bytes are not the plaintext (IV + ciphertext + tag).
        Path onDisk = dir.resolve("ab").resolve("ab12.png");
        assertThat(java.nio.file.Files.readAllBytes(onDisk)).isNotEqualTo(DATA);
        assertThat(java.nio.file.Files.readAllBytes(onDisk).length).isGreaterThan(DATA.length);

        storage.delete("ab12.png");
        assertThat(java.nio.file.Files.exists(onDisk)).isFalse();
    }

    @Test
    void storesPlaintextWhenNoKeyConfigured(@TempDir Path dir) throws Exception {
        var storage = new FilesystemScreenshotStorage(new AppProperties.Screenshots(dir.toString(), null, 0));
        assertThat(storage.encryptsAtRest()).isFalse();
        storage.put("cd34.jpg", DATA);
        assertThat(storage.get("cd34.jpg")).isEqualTo(DATA);
        assertThat(java.nio.file.Files.readAllBytes(dir.resolve("cd").resolve("cd34.jpg"))).isEqualTo(DATA);
    }

    @Test
    void rejectsWrongLengthKey(@TempDir Path dir) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new FilesystemScreenshotStorage(new AppProperties.Screenshots(dir.toString(), "c2hvcnQ=", 0)))
                .isInstanceOf(IllegalStateException.class);
    }
}
