package dev.chaya.api.processing;

/**
 * Processing stages. Mirrors the CHECK constraint on processing_job.stage. The first group is the
 * reconstruction pipeline in execution order (see PipelineDefinition); the rest are legacy names kept
 * so existing rows and clients keep working.
 */
public enum JobStage {
    INPUT_VALIDATION,
    FFMPEG_PREPROCESS,
    FRAME_QUALITY_FILTER,
    PRIVACY_PREPROCESS,
    POSE_ESTIMATION,
    SPLAT_RECONSTRUCTION,
    SEMANTIC_SEGMENTATION,
    GEOMETRIC_CLEANUP,
    PLANE_FITTING,
    ARTIFACT_GENERATION,
    NAVIGATION_BAKING,
    SEMANTIC_INDEXING,

    // legacy
    MEDIA_FILTER,
    SPLAT_TRAINING,
    GEOMETRY_CLEANUP,
    SEGMENTATION,
    OBJECT_DETECTION,
    NAVMESH_GENERATION
}
