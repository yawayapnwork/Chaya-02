package dev.chaya.api.capture;

import java.util.EnumSet;
import java.util.Set;

/** Capture lifecycle; mirrors capture_session_guard in V9. The database is the final authority. */
public enum CaptureStatus {
    CREATED, UPLOADING, UPLOADED, VALIDATING, READY_FOR_PROCESSING, PROCESSING, COMPLETED, FAILED;

    public Set<CaptureStatus> allowedNext() {
        return switch (this) {
            case CREATED -> EnumSet.of(UPLOADING, FAILED);
            case UPLOADING -> EnumSet.of(UPLOADED, FAILED);
            case UPLOADED -> EnumSet.of(VALIDATING, FAILED);
            case VALIDATING -> EnumSet.of(READY_FOR_PROCESSING, FAILED);
            case READY_FOR_PROCESSING -> EnumSet.of(PROCESSING, FAILED);
            case PROCESSING -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED, FAILED -> EnumSet.noneOf(CaptureStatus.class);
        };
    }

    public boolean canTransitionTo(CaptureStatus next) {
        return allowedNext().contains(next);
    }

    public boolean acceptsMedia() {
        return this == CREATED || this == UPLOADING;
    }
}
