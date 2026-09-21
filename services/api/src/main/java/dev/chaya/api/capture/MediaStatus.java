package dev.chaya.api.capture;

/** Raw media lifecycle; mirrors capture_media_guard in V9. ACCEPTED and REJECTED are terminal. */
public enum MediaStatus { PENDING, VALIDATING, ACCEPTED, REJECTED, QUARANTINED }
