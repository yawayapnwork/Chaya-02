package dev.chaya.api.health;

/** Whether the vision service (CLIP embeddings for semantic search) answers ready. */
public interface VisionProbe {

    String NOT_CONFIGURED = "NOT_CONFIGURED";

    /** UP, DOWN, or {@link #NOT_CONFIGURED} when no vision service URL is set. */
    String status();
}
