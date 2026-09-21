package dev.chaya.api.processing;

import java.util.EnumSet;
import java.util.Set;

/**
 * Processing job lifecycle. Mirrors processing_job_guard in V4__processing.sql; the database is
 * the final authority, this enum lets callers fail fast with a clear message.
 */
public enum JobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public Set<JobStatus> allowedNext() {
        return switch (this) {
            case QUEUED -> EnumSet.of(RUNNING, CANCELLED);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED, CANCELLED);
            case FAILED -> EnumSet.of(QUEUED); // retry
            case SUCCEEDED, CANCELLED -> EnumSet.noneOf(JobStatus.class);
        };
    }

    public boolean canTransitionTo(JobStatus next) {
        return allowedNext().contains(next);
    }
}
