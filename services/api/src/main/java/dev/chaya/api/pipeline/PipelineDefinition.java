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
        // SEMANTIC_INDEXING before NAVIGATION_BAKING: neither depends on the other's output, and a
        // stage failure stops the run from advancing (see PipelineService#advance). recast-cli
        // (NAVIGATION_BAKING's hard dependency) is a much rarer thing to have installed than the
        // reconstruction toolchain SEMANTIC_INDEXING needs, so putting it last means a worker without it
        // still produces a fully searchable reconstruction -- only routing is unavailable, not search too.
        JobStage.SEMANTIC_INDEXING,
        JobStage.NAVIGATION_BAKING);

    /** Artifact kinds that count as "a reconstruction exists" for time-boxed partial results. Matches the
     * kind chaya_worker.stages.splat_reconstruction actually publishes (SPLAT) for its trained Gaussian
     * cloud, plus SPLAT_PARTIAL for a worker that checkpoints before the time budget is fully spent. */
    public static final Set<String> RECONSTRUCTION_KINDS = Set.of("SPLAT", "SPLAT_PARTIAL");

    public static final String TIME_LIMIT_EXCEEDED = "TIME_LIMIT_EXCEEDED";
    public static final String WORKER_LOST = "WORKER_LOST";

    private PipelineDefinition() {}

    /** Privacy preprocessing is part of the plan unless it was explicitly disabled for the run. */
    public static List<JobStage> plan(boolean privacyEnabled) {
        return privacyEnabled ? STAGES : STAGES.stream().filter(s -> s != JobStage.PRIVACY_PREPROCESS).toList();
    }
}
