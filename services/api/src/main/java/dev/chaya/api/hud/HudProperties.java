package dev.chaya.api.hud;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for the live capture HUD. Defaults are conservative; see docs/capture-hud.md. */
@ConfigurationProperties("chaya.hud")
public record HudProperties(
    int maxPoseSamplesPerRequest,
    int maxQualitySamplesPerRequest,
    int maxPoseSamplesConsidered,
    long positionStaleAfterMs,
    long broadcastIntervalMs,
    long heartbeatIntervalMs,
    long emitterTimeoutMs) {

    public HudProperties {
        if (maxPoseSamplesPerRequest <= 0 || maxQualitySamplesPerRequest <= 0 || maxPoseSamplesConsidered <= 0) {
            throw new IllegalStateException("chaya.hud sample limits must be positive");
        }
    }
}
