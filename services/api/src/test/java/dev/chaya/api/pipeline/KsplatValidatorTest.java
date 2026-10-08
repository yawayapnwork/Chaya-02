package dev.chaya.api.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The viewer contract on the committed production-encoder files (packages/contracts/fixtures), and every way a file can
 * break it. The same mutations are run against the real viewer library in apps/web/lib/ksplat-validation.test.ts.
 */
class KsplatValidatorTest {

    static final Path FIXTURES = Path.of("../../packages/contracts/fixtures");
    static final Path CONTRACT = Path.of("../../packages/contracts/viewer/ksplat-contract.json");

    static byte[] small() throws IOException {
        return Files.readAllBytes(FIXTURES.resolve("ksplat/scene.ksplat"));
    }

    static KsplatValidator.KsplatInfo validate(byte[] b) {
        return KsplatValidator.validate(new ByteArrayInputStream(b), b.length);
    }

    static void refused(byte[] b, String code, String reason) {
        assertThatThrownBy(() -> validate(b)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.getMessage()).contains(reason);
        });
    }

    static byte[] mutated(Consumer<ByteBuffer> change) throws IOException {
        byte[] b = small();
        change.accept(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN));
        return b;
    }

    @Test
    void constantsAreTheSharedContract() throws IOException {
        JsonNode c = new ObjectMapper().readTree(CONTRACT.toFile());
        assertThat(KsplatValidator.LIBRARY).isEqualTo(c.get("library").asText() + "@" + c.get("libraryVersion").asText());
        assertThat(KsplatValidator.VERSION_MAJOR).isEqualTo(c.get("versionMajor").asInt());
        assertThat(KsplatValidator.VERSION_MINOR).isEqualTo(c.get("versionMinor").asInt());
        assertThat(KsplatValidator.FILE_HEADER_BYTES).isEqualTo(c.get("fileHeaderBytes").asInt());
        assertThat(KsplatValidator.SECTION_HEADER_BYTES).isEqualTo(c.get("sectionHeaderBytes").asInt());
        assertThat(KsplatValidator.COMPRESSION_LEVEL).isEqualTo(c.get("compressionLevel").asInt());
        assertThat(KsplatValidator.SH_DEGREE).isEqualTo(c.get("sphericalHarmonicsDegree").asInt());
        assertThat(KsplatValidator.BYTES_PER_SPLAT).isEqualTo(c.get("bytesPerSplat").asInt());
        assertThat(KsplatValidator.SECTION_COUNT).isEqualTo(c.get("sectionCount").asInt());
        assertThat(KsplatValidator.MIN_SPLAT_COUNT).isEqualTo(c.get("minSplatCount").asLong());
        assertThat(KsplatValidator.MAX_BYTES).isEqualTo(c.get("maxBytes").asLong());
    }

    @Test
    void theCommittedProductionFilesMeetTheContract() throws IOException {
        assertThat(validate(small()).asMetadata()).containsEntry("format", "ksplat").containsEntry("version", "0.1")
            .containsEntry("compressionLevel", 0).containsEntry("sphericalHarmonicsDegree", 0)
            .containsEntry("sectionCount", 1).containsEntry("splatCount", 3L)
            .containsEntry("contract", "@mkkellogg/gaussian-splats-3d@0.4.7");
        byte[] scene = Files.readAllBytes(FIXTURES.resolve("viewer-scene/scene.ksplat"));
        JsonNode fixture = new ObjectMapper().readTree(FIXTURES.resolve("viewer-scene/fixture.json").toFile());
        assertThat(validate(scene).splatCount()).isEqualTo(fixture.get("splat_count").asLong());
    }

    @Test
    void truncatedOrPaddedFilesAreRefused() throws IOException {
        byte[] b = small();
        refused(Arrays.copyOf(b, b.length - 1), "KSPLAT_INVALID", "TRUNCATED");
        refused(Arrays.copyOf(b, b.length - 44), "KSPLAT_INVALID", "TRUNCATED");
        refused(Arrays.copyOf(b, 5120), "KSPLAT_INVALID", "TRUNCATED");
        refused(Arrays.copyOf(b, 100), "KSPLAT_INVALID", "TRUNCATED");
        refused(new byte[0], "KSPLAT_INVALID", "TRUNCATED");
        refused(Arrays.copyOf(b, b.length + 1), "KSPLAT_INVALID", "TRAILING_BYTES");
        refused(Arrays.copyOf(b, b.length + 44), "KSPLAT_INVALID", "TRAILING_BYTES");
    }

    @Test
    void headersOutsideTheContractAreRefused() throws IOException {
        refused(mutated(h -> h.put(1, (byte) 0)), "KSPLAT_INVALID", "UNSUPPORTED_VERSION");   // 0.0, the library refuses too
        refused(mutated(h -> h.put(1, (byte) 2)), "KSPLAT_INVALID", "UNSUPPORTED_VERSION");   // 0.2, the library would accept
        refused(mutated(h -> h.put(0, (byte) 1)), "KSPLAT_INVALID", "UNSUPPORTED_VERSION");   // 1.1
        refused(mutated(h -> h.putShort(20, (short) 1)), "KSPLAT_INVALID", "UNSUPPORTED_COMPRESSION");
        refused(mutated(h -> h.putShort(4096 + 40, (short) 1)), "KSPLAT_INVALID", "UNSUPPORTED_SH_DEGREE");
        refused(mutated(h -> h.putInt(4, 2)), "KSPLAT_INVALID", "UNSUPPORTED_LAYOUT");          // two section slots
        refused(mutated(h -> h.putInt(8, 0)), "KSPLAT_INVALID", "UNSUPPORTED_LAYOUT");          // no section
        refused(mutated(h -> h.putInt(4096 + 12, 1)), "KSPLAT_INVALID", "UNSUPPORTED_LAYOUT");  // bucket count
        refused(mutated(h -> h.putShort(4096 + 20, (short) 12)), "KSPLAT_INVALID", "UNSUPPORTED_LAYOUT");
        refused(mutated(h -> h.putInt(16, 2)), "KSPLAT_INVALID", "INCONSISTENT_COUNTS");        // file splat count
        refused(mutated(h -> h.putInt(4096 + 4, 4)), "KSPLAT_INVALID", "INCONSISTENT_COUNTS");  // section max splats
        refused(mutated(h -> h.putInt(4096 + 28, 0)), "KSPLAT_INVALID", "INCONSISTENT_COUNTS"); // section storage size
        refused(Files.readAllBytes(FIXTURES.resolve("ksplat/scene.ply")), "KSPLAT_INVALID", "TRUNCATED"); // a PLY, 0.6 KB
    }

    @Test
    void anEmptyFileOfValidShapeIsRefused() throws IOException {
        byte[] empty = mutated(h -> {
            h.putInt(12, 0).putInt(16, 0).putInt(4096, 0).putInt(4096 + 4, 0).putInt(4096 + 28, 0);
        });
        byte[] headersOnly = Arrays.copyOf(empty, 5120);
        assertThatThrownBy(() -> KsplatValidator.validateHeaders(headersOnly, headersOnly.length))
            .isInstanceOf(ApiException.class).hasMessageContaining("EMPTY");
        refused(headersOnly, "KSPLAT_INVALID", "TRUNCATED"); // and below the minimum size before reading at all
    }

    @Test
    void anOversizedFileIsRefusedBeforeAnythingIsRead() {
        assertThatThrownBy(() -> KsplatValidator.validate(InputStreamThatMustNotBeRead.INSTANCE, KsplatValidator.MAX_BYTES + 1))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ARTIFACT_TOO_LARGE"));
    }

    static final class InputStreamThatMustNotBeRead extends java.io.InputStream {
        static final InputStreamThatMustNotBeRead INSTANCE = new InputStreamThatMustNotBeRead();

        @Override
        public int read() {
            throw new AssertionError("read before the size check");
        }
    }
}
