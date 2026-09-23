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

    /**
     * The incremental re-scan plan (docs/rescan.md): captures and reconstructs only the selected region,
     * aligns it against the venue's existing reconstruction and splices it in, then re-derives the
     * downstream artifacts from the spliced, venue-wide result. NAVIGATION_BAKING is included here but
     * {@link dev.chaya.api.rescan.RescanService} omits it from the plan entirely (not merely skips running
     * it) whenever the selected region does not intersect the floor's current routing graph -- "only
     * affected regions require navigation updates" is decided once, up front, because the region is chosen
     * before capture even starts, and it changes what {@code pipeline_run.stages} is created with, not
     * something toggled mid-run (pipeline_run's plan is immutable once created).
     */
    public static final List<JobStage> INCREMENTAL_STAGES = List.of(
        JobStage.INPUT_VALIDATION,
        JobStage.FFMPEG_PREPROCESS,
        JobStage.FRAME_QUALITY_FILTER,
        JobStage.PRIVACY_PREPROCESS,
        JobStage.POSE_ESTIMATION,
        JobStage.SPLAT_RECONSTRUCTION,
        JobStage.GEOMETRIC_CLEANUP,
        JobStage.REGION_ALIGNMENT,
        JobStage.REGION_SPLICE,
        JobStage.PLANE_FITTING,
        JobStage.ARTIFACT_GENERATION,
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

    /** The incremental plan, with privacy preprocessing and (per {@code includeNavigationBaking}, decided
     * once at rescan initiation -- see the class docstring above) navigation baking each optionally
     * removed. */
    public static List<JobStage> incrementalPlan(boolean privacyEnabled, boolean includeNavigationBaking) {
        return INCREMENTAL_STAGES.stream()
            .filter(s -> privacyEnabled || s != JobStage.PRIVACY_PREPROCESS)
            .filter(s -> includeNavigationBaking || s != JobStage.NAVIGATION_BAKING)
            .toList();
    }
}
