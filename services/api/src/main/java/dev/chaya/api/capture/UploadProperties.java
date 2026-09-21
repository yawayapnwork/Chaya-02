package dev.chaya.api.capture;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chaya.uploads")
public record UploadProperties(
    int partSizeBytes,
    long maxVideoBytes,
    long maxImageBytes,
    long maxMetadataBytes,
    int maxFilesPerCapture,
    int minImagesWithoutVideo) {

    public static final int MIN_S3_PART_BYTES = 5 * 1024 * 1024;

    public UploadProperties {
        if (partSizeBytes < MIN_S3_PART_BYTES) {
            throw new IllegalStateException("chaya.uploads.part-size-bytes must be at least 5 MiB (S3 multipart minimum)");
        }
    }

    /** Content types accepted per kind. Both the claimed and the detected type must be in this set. */
    public static Set<String> allowedTypes(MediaKind kind) {
        return switch (kind) {
            case VIDEO -> Set.of("video/mp4", "video/quicktime", "video/webm", "video/x-matroska");
            case IMAGE -> Set.of("image/jpeg", "image/png", "image/heic");
            case METADATA -> Set.of("application/json");
        };
    }

    public long maxBytes(MediaKind kind) {
        return switch (kind) {
            case VIDEO -> maxVideoBytes;
            case IMAGE -> maxImageBytes;
            case METADATA -> maxMetadataBytes;
        };
    }
}
