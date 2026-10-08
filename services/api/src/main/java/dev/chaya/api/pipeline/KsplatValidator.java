package dev.chaya.api.pipeline;

import dev.chaya.api.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The .ksplat contract (packages/contracts/viewer/ksplat-contract.json): the layout the pinned viewer library
 * (@mkkellogg/gaussian-splats-3d 0.4.7) reads, narrowed to the one the worker writes and the viewer's compatibility test
 * proves -- version 0.1, one section, compression level 0, spherical-harmonics degree 0, 44-byte records. The library
 * itself checks only the version: a truncated file or one with trailing bytes loads as garbage or throws from inside a
 * timer. So the API refuses to publish anything else, the same rules apply in the worker before upload
 * (chaya_worker.ksplat.validate) and in the viewer before loading (apps/web/lib/ksplat-validation.ts).
 */
public final class KsplatValidator {

    public static final String LIBRARY = "@mkkellogg/gaussian-splats-3d@0.4.7";
    public static final int VERSION_MAJOR = 0;
    public static final int VERSION_MINOR = 1;
    public static final int FILE_HEADER_BYTES = 4096;
    public static final int SECTION_HEADER_BYTES = 1024;
    public static final int COMPRESSION_LEVEL = 0;
    public static final int SH_DEGREE = 0;
    public static final int BYTES_PER_SPLAT = 44;
    public static final int SECTION_COUNT = 1;
    public static final long MIN_SPLAT_COUNT = 1;
    /** 512 MiB, about 12.2 million splats: beyond what a browser tab can hold as download, parsed buffer and GPU textures. */
    public static final long MAX_BYTES = 512L * 1024 * 1024;

    static final int HEADERS = FILE_HEADER_BYTES + SECTION_HEADER_BYTES * SECTION_COUNT;

    private KsplatValidator() {}

    /** What a valid file's headers say; stored with the published artifact (processing_artifact.format_metadata). */
    public record KsplatInfo(int versionMajor, int versionMinor, int compressionLevel, int sphericalHarmonicsDegree,
                             int sectionCount, long splatCount) {
        public Map<String, Object> asMetadata() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("format", "ksplat");
            m.put("contract", LIBRARY);
            m.put("version", versionMajor + "." + versionMinor);
            m.put("compressionLevel", compressionLevel);
            m.put("sphericalHarmonicsDegree", sphericalHarmonicsDegree);
            m.put("sectionCount", sectionCount);
            m.put("splatCount", splatCount);
            return m;
        }
    }

    /** Refuses before reading anything: a size no valid file has. */
    public static void requirePlausibleSize(long sizeBytes) {
        if (sizeBytes > MAX_BYTES) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_TOO_LARGE",
                ".ksplat is " + sizeBytes + " bytes; the limit is " + MAX_BYTES);
        }
        if (sizeBytes < HEADERS + BYTES_PER_SPLAT * MIN_SPLAT_COUNT) {
            throw invalid("TRUNCATED", sizeBytes + " bytes is shorter than the headers and one splat");
        }
    }

    /** Validates a whole file of {@code sizeBytes} bytes from its first bytes; reads only the headers. */
    public static KsplatInfo validate(InputStream in, long sizeBytes) {
        requirePlausibleSize(sizeBytes);
        byte[] head;
        try {
            head = in.readNBytes(HEADERS);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_MISSING", "the .ksplat could not be read: " + e.getMessage());
        }
        if (head.length < HEADERS) {
            throw invalid("TRUNCATED", "only " + head.length + " header bytes");
        }
        return validateHeaders(head, sizeBytes);
    }

    /** The checks themselves, on the file header and section header bytes. */
    static KsplatInfo validateHeaders(byte[] head, long sizeBytes) {
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int major = h.get(0) & 0xff;
        int minor = h.get(1) & 0xff;
        if (major != VERSION_MAJOR || minor != VERSION_MINOR) {
            throw invalid("UNSUPPORTED_VERSION", "version " + major + "." + minor + "; only " + VERSION_MAJOR + "." + VERSION_MINOR);
        }
        long maxSections = u32(h, 4);
        long sections = u32(h, 8);
        if (maxSections != SECTION_COUNT || sections != SECTION_COUNT) {
            throw invalid("UNSUPPORTED_LAYOUT", maxSections + " section slots, " + sections + " sections; only " + SECTION_COUNT);
        }
        int level = h.getShort(20) & 0xffff;
        if (level != COMPRESSION_LEVEL) {
            throw invalid("UNSUPPORTED_COMPRESSION", "compression level " + level + "; only " + COMPRESSION_LEVEL);
        }
        int s = FILE_HEADER_BYTES;
        int shDegree = h.getShort(s + 40) & 0xffff;
        if (shDegree != SH_DEGREE) {
            throw invalid("UNSUPPORTED_SH_DEGREE", "spherical-harmonics degree " + shDegree + "; only " + SH_DEGREE);
        }
        // Level 0 has no position buckets; any bucket storage would move where the reader expects the splat data.
        if (u32(h, s + 8) != 0 || u32(h, s + 12) != 0 || (h.getShort(s + 20) & 0xffff) != 0
            || u32(h, s + 32) != 0 || u32(h, s + 36) != 0) {
            throw invalid("UNSUPPORTED_LAYOUT", "bucket fields are set; compression level 0 has no buckets");
        }
        long maxSplats = u32(h, 12);
        long splats = u32(h, 16);
        long sectionSplats = u32(h, s);
        long sectionMaxSplats = u32(h, s + 4);
        if (splats != maxSplats || sectionSplats != splats || sectionMaxSplats != splats) {
            throw invalid("INCONSISTENT_COUNTS", "splat counts disagree: file " + splats + "/" + maxSplats
                + ", section " + sectionSplats + "/" + sectionMaxSplats);
        }
        if (splats < MIN_SPLAT_COUNT) {
            throw invalid("EMPTY", "the file has no splats");
        }
        if (u32(h, s + 28) != splats * BYTES_PER_SPLAT) {
            throw invalid("INCONSISTENT_COUNTS", "section storage size " + u32(h, s + 28) + " is not " + splats + " x " + BYTES_PER_SPLAT);
        }
        long expected = HEADERS + splats * BYTES_PER_SPLAT;
        if (sizeBytes < expected) {
            throw invalid("TRUNCATED", sizeBytes + " bytes; " + splats + " splats need " + expected);
        }
        if (sizeBytes > expected) {
            throw invalid("TRAILING_BYTES", sizeBytes + " bytes; " + splats + " splats need exactly " + expected);
        }
        return new KsplatInfo(major, minor, level, shDegree, (int) sections, splats);
    }

    private static long u32(ByteBuffer b, int offset) {
        return b.getInt(offset) & 0xffffffffL;
    }

    private static ApiException invalid(String reason, String detail) {
        return new ApiException(HttpStatus.CONFLICT, "KSPLAT_INVALID",
            "the .ksplat does not meet the viewer contract (" + reason + "): " + detail);
    }
}
