package com.employeemonitor.api.screenshots;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ScreenshotDtos {

    private ScreenshotDtos() {
    }

    /** Agent upload. The image is sent base64-encoded; size is bounded by app.screenshots.max-bytes. */
    public record UploadRequest(
            @NotNull UUID clientEventId,
            @NotNull Instant capturedAt,
            @NotBlank @Pattern(regexp = "image/(png|jpeg)", message = "must be image/png or image/jpeg") String contentType,
            Integer width,
            Integer height,
            @NotBlank @Size(max = 16_000_000) String imageBase64) {
    }

    public record UploadResponse(Long id, boolean duplicate) {
    }

    public record ScreenshotMetadata(Long id, Instant capturedAt, String contentType, Integer width, Integer height,
                                     int sizeBytes) {
        public static ScreenshotMetadata from(Screenshot s) {
            return new ScreenshotMetadata(s.getId(), s.getCapturedAt(), s.getContentType(), s.getWidth(),
                    s.getHeight(), s.getSizeBytes());
        }
    }

    public record ScreenshotContent(byte[] bytes, String contentType) {
    }
}
