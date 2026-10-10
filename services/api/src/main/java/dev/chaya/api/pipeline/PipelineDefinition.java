package dev.chaya.api.pipeline;

import dev.chaya.api.processing.JobStage;
import java.util.List;
import java.util.Map;
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
     * downstream artifacts from the spliced, venue-wide result. NAVIGATION_BAKING re-bakes the whole floor from the
     * merged scene; {@link dev.chaya.api.rescan.RescanService} omits it from the plan only when the parent version has
     * no navmesh at all (so neither does the new version). Navigation is never inherited from the parent: any change in
     * the region can add or remove an obstacle. The choice is made once, when the run is created, because
     * {@code pipeline_run.stages} is immutable once created.
     */
    public static final List<JobStage> INCREMENTAL_STAGES = List.of(
        JobStage.INPUT_VALIDATION,
        JobStage.FFMPEG_PREPROCESS,
        JobStage.FRAME_QUALITY_FILTER,
        JobStage.PRIVACY_PREPROCESS,
        JobStage.POSE_ESTIMATION,
        JobStage.SPLAT_RECONSTRUCTION,
        // The region's own labels: GEOMETRIC_CLEANUP cleans them with the cloud, REGION_SPLICE splices them into the
        // venue's (GLOBAL_LABELS) as SEMANTIC_LABELS_MERGED, and NAVIGATION_BAKING refuses to bake without labels for the
        // cloud it bakes -- so a re-bake keeps every wall and piece of furniture outside the region (review N-1).
        JobStage.SEMANTIC_SEGMENTATION,
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

    /**
     * What every stage must publish when it reports SUCCEEDED, because a later stage or the scan version's publication
     * consumes it (packages/contracts/pipeline/stage-artifacts.json, "requiredOutputs"; PipelineArtifactContractTest keeps
     * the two equal, and the worker's tests/unit/test_stage_artifact_contract.py checks what the worker really writes).
     * A SUCCEEDED report without them is recorded as FAILED with STAGE_OUTPUT_MISSING: the next stage would otherwise run
     * without its input, or the version would fail to publish only after every later stage had run.
     */
    public static final Map<JobStage, List<String>> REQUIRED_OUTPUTS = Map.ofEntries(
        Map.entry(JobStage.INPUT_VALIDATION, List.of()),
        Map.entry(JobStage.FFMPEG_PREPROCESS, List.of("FRAME_ARCHIVE")),
        Map.entry(JobStage.FRAME_QUALITY_FILTER, List.of("FRAME_ARCHIVE_SELECTED")),
        Map.entry(JobStage.PRIVACY_PREPROCESS, List.of("FRAME_ARCHIVE_ANON", "PRIVACY_MASKS")),
        Map.entry(JobStage.POSE_ESTIMATION, List.of("SPARSE_MODEL", "POSES")),
        Map.entry(JobStage.SPLAT_RECONSTRUCTION, List.of("SPLAT")),
        Map.entry(JobStage.SEMANTIC_SEGMENTATION, List.of("SEMANTIC_LABELS")),
        Map.entry(JobStage.GEOMETRIC_CLEANUP, List.of("SPLAT_CLEAN", "SEMANTIC_LABELS_CLEAN")),
        Map.entry(JobStage.REGION_ALIGNMENT, List.of("SPLAT_ALIGNED")),
        Map.entry(JobStage.REGION_SPLICE, List.of("SPLAT_MERGED", "SPLICE_REPORT")),
        Map.entry(JobStage.PLANE_FITTING, List.of("PLANE_MODEL", "GRAVITY_ESTIMATE")),
        Map.entry(JobStage.ARTIFACT_GENERATION, List.of("KSPLAT", "ARTIFACT_MANIFEST")),
        Map.entry(JobStage.SEMANTIC_INDEXING, List.of("DETECTED_OBJECTS")),
        Map.entry(JobStage.NAVIGATION_BAKING, List.of("NAVMESH", "NAVMESH_MANIFEST", "NAVIGATION_GRAPH")));

    /** The required outputs of `stage` that `published` lacks, in contract order. */
    public static List<String> missingOutputs(JobStage stage, Set<String> published) {
        return REQUIRED_OUTPUTS.getOrDefault(stage, List.of()).stream().filter(k -> !published.contains(k)).toList();
    }

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
