package dev.chaya.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.search.EmbeddingUnavailableException;
import dev.chaya.api.search.TextEmbeddingClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Deterministic stand-in for services/vision (the real CLIP text encoder): the same text always embeds to
 * the same 512-dimensional unit vector, seeded from the text itself, so a test can reason about cosine
 * similarity ordering without a real model. Tests assert against THIS function's geometry, never against
 * actual CLIP behaviour.
 *
 * <p>With {@link #REAL_CLIP} set, it instead serves REAL CLIP ViT-B/32 text-tower vectors from {@link #CLIP_FIXTURE}
 * (computed by the production encoder, benchmarks/b3_semantic_search/clip_vectors.py), under the real model id, and fails
 * for any text the fixture does not have. Tests that need actual CLIP geometry (text-to-text vs text-to-image scales)
 * use that.
 */
@TestConfiguration
public class TestEmbeddingConfig {

    public static final AtomicBoolean UNAVAILABLE = new AtomicBoolean(false);
    public static final String MODEL = "test-embedding";
    public static final AtomicBoolean REAL_CLIP = new AtomicBoolean(false);
    public static final Path CLIP_FIXTURE = Path.of("../../packages/contracts/fixtures/search/clip-vit-b32-openai.json");
    private static JsonNode clipFixture;

    /** The real-CLIP fixture: {"text_model", "image_model", "texts": {text: [512]}, "crops": {id: {"embedding": [512]}}}. */
    public static synchronized JsonNode clipFixture() {
        if (clipFixture == null) {
            try {
                clipFixture = new ObjectMapper().readTree(CLIP_FIXTURE.toFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return clipFixture;
    }

    public static float[] vector(JsonNode array) {
        float[] v = new float[array.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) array.get(i).asDouble();
        }
        return v;
    }

    /** A real CLIP text-tower vector of `text` (lower-cased, as SemanticSearchService does before embedding). */
    public static float[] clipText(String text) {
        JsonNode v = clipFixture().get("texts").get(text.strip().toLowerCase(Locale.ROOT));
        if (v == null) {
            throw new IllegalStateException("no real CLIP vector for '" + text + "' in " + CLIP_FIXTURE);
        }
        return vector(v);
    }

    public static float[] embedFor(String text) {
        Random rng = new Random(text.strip().toLowerCase(Locale.ROOT).hashCode());
        float[] v = new float[512];
        double normSq = 0;
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) rng.nextGaussian();
            normSq += (double) v[i] * v[i];
        }
        double norm = Math.sqrt(normSq);
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }

    public static String vectorLiteral(float[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }

    @Bean
    @Primary
    TextEmbeddingClient testEmbeddingClient() {
        return text -> {
            if (UNAVAILABLE.get()) {
                throw new EmbeddingUnavailableException("vision service is down (test)");
            }
            if (REAL_CLIP.get()) {
                return new TextEmbeddingClient.Embedding(clipText(text), clipFixture().get("text_model").asText());
            }
            return new TextEmbeddingClient.Embedding(embedFor(text), MODEL);
        };
    }
}
