package dev.chaya.api.frame;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Acceptance limits for a calibration. A calibration outside them is refused (422 CALIBRATION_INCONSISTENT), because
 * its own references disagree with each other by more than the stated tolerance -- not silently averaged.
 *
 * @param minDistanceReferences      independent measured distances required for a scale-from-distances calibration;
 *                                   at least two, so their agreement can be checked
 * @param maxScaleRelativeSpread     largest allowed relative deviation of any single reference's implied scale from
 *                                   the fitted scale (0.03 = 3 %)
 * @param maxControlPointRmsM        largest allowed RMS residual of a control-point fit, metres
 * @param maxFloorPointsRmsM         largest allowed RMS distance of operator floor points from their plane, metres
 * @param maxGravityDisagreementDeg  largest allowed angle between a control-point frame's up and the reconstructed
 *                                   floor plane's, when both exist
 * @param maxDeviceGravityTiltDeg    largest allowed tilt between an AR device's gravity-aligned up and canonical +Z
 *                                   implied by a relocalization
 */
@ConfigurationProperties("chaya.frames")
public record FrameProperties(int minDistanceReferences, double maxScaleRelativeSpread, double maxControlPointRmsM,
                              double maxFloorPointsRmsM, double maxGravityDisagreementDeg, double maxDeviceGravityTiltDeg) {

    public FrameProperties {
        if (minDistanceReferences < 2) minDistanceReferences = 2;
        if (maxScaleRelativeSpread <= 0) maxScaleRelativeSpread = 0.03;
        if (maxControlPointRmsM <= 0) maxControlPointRmsM = 0.05;
        if (maxFloorPointsRmsM <= 0) maxFloorPointsRmsM = 0.05;
        if (maxGravityDisagreementDeg <= 0) maxGravityDisagreementDeg = 5.0;
        if (maxDeviceGravityTiltDeg <= 0) maxDeviceGravityTiltDeg = 10.0;
    }
}
