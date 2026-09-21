package dev.chaya.api.pipeline;

import dev.chaya.api.processing.JobStage;
import java.util.List;
import java.util.Set;

/** The reconstruction pipeline plan. Stages run strictly in this order, one job at a time. */
public final class PipelineDefinition {

    public static final List<JobStage> STAGES = List.of(
        JobStage.INPUT_VALIDATION,
        JobStage.FFMPEG_PREPROCESS,
        JobStage.FRAME_QUALITY_FILTER,
        JobStage.PRIVACY_PREPROCESS,
        JobStage.POSE_ESTIMATION,
        JobStage.SPLAT_RECONSTRUCTION,
        JobStage.SEMANTIC_SEGMENTATION,
        JobStage.GEOMETRIC_CLEANUP,
        JobStage.PLANE_FITTING,
        JobStage.ARTIFACT_GENERATION,
        JobStage.NAVIGATION_BAKING,
        JobStage.SEMANTIC_INDEXING);

    /** Artifact kinds that count as "a reconstruction exists" for time-boxed partial results. */
    public static final Set<String> RECONSTRUCTION_KINDS = Set.of("SPLAT", "SPLAT_PARTIAL");

    public static final String TIME_LIMIT_EXCEEDED = "TIME_LIMIT_EXCEEDED";
    public static final String WORKER_LOST = "WORKER_LOST";

    private PipelineDefinition() {}

    /** Privacy preprocessing is part of the plan unless it was explicitly disabled for the run. */
    public static List<JobStage> plan(boolean privacyEnabled) {
        return privacyEnabled ? STAGES : STAGES.stream().filter(s -> s != JobStage.PRIVACY_PREPROCESS).toList();
    }
}
