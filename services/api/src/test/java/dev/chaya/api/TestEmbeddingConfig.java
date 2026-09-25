package dev.chaya.api;

import dev.chaya.api.search.EmbeddingUnavailableException;
import dev.chaya.api.search.TextEmbeddingClient;
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
 * actual CLIP behaviour -- that would need the real model (see the Python-side clip_embeddings tests).
 */
@TestConfiguration
public class TestEmbeddingConfig {

    public static final AtomicBoolean UNAVAILABLE = new AtomicBoolean(false);
    public static final String MODEL = "test-embedding";

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
            return new TextEmbeddingClient.Embedding(embedFor(text), MODEL);
        };
    }
}
