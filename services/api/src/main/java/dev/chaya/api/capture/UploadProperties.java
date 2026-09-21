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

    /**
     * Content types that are the same container format under different names. Content sniffing cannot tell
     * an MP4 from a QuickTime file (both are ISO base media files, and FFmpeg's default "isom" brand is
     * reported as video/quicktime by Tika), nor WebM from Matroska.
     */
    private static final java.util.List<Set<String>> FAMILIES = java.util.List.of(
        Set.of("video/mp4", "video/quicktime"),
        Set.of("video/webm", "video/x-matroska"));

    public static boolean sameFamily(String claimed, String detected) {
        return claimed.equals(detected) || FAMILIES.stream().anyMatch(f -> f.contains(claimed) && f.contains(detected));
    }

    public long maxBytes(MediaKind kind) {
        return switch (kind) {
            case VIDEO -> maxVideoBytes;
            case IMAGE -> maxImageBytes;
            case METADATA -> maxMetadataBytes;
        };
    }
}
