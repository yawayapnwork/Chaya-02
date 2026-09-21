package dev.chaya.api.processing;

/** Processing stages, in pipeline order. Mirrors the CHECK constraint on processing_job.stage. */
public enum JobStage {
    MEDIA_FILTER,
    POSE_ESTIMATION,
    SPLAT_TRAINING,
    GEOMETRY_CLEANUP,
    SEGMENTATION,
    OBJECT_DETECTION,
    NAVMESH_GENERATION
}
