package dev.chaya.api.frame;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Acceptance limits for a calibration. A calibration outside them is refused (422 CALIBRATION_INCONSISTENT and the codes
 * below), because its own references disagree with each other, or with independent holdouts, by more than the stated
 * tolerance -- not silently averaged.
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
 * @param maxAnchorResidualM         a relocalization anchor whose observation, mapped through the solved transform, lands
 *                                   farther than this from its registered position disagrees with the others
 * @param maxAnchorResidualDeg       the same, for its orientation
 * @param minReferenceLengthM        a measured distance shorter than this is refused: the same pixel error is a larger
 *                                   share of a short distance (0.3 m)
 * @param maxControlPointResidualM   largest allowed residual of any single control point after the fit (0.10 m); the worst
 *                                   one is named, so a mis-surveyed point is found, not averaged away
 * @param minControlPointSpanM       the control points must span at least this far (0.5 m): a tight cluster fixes the
 *                                   rotation poorly far from it
 * @param minTriangulationAngleDeg   a triangulated measurement point's views must see it from directions at least this far
 *                                   apart (2 degrees), else its depth is ill-conditioned
 * @param maxReprojectionRmsPx       largest RMS reprojection error of a triangulated point, in frame pixels (4 px)
 * @param maxReprojectionPx          largest reprojection error in any one of its views, in frame pixels (8 px)
 * @param maxVideoFrameOffsetSeconds a point marked in a video at time t is located in the extracted frame closest to t only
 *                                   if that frame is within this (0.05 s); otherwise the camera may have moved
 * @param maxHoldoutRelativeError    a HOLDOUT distance's calibrated length may differ from its measured one by at most this
 *                                   share (0.02)
 * @param maxHoldoutErrorM           a HOLDOUT control point's calibrated position may differ from its survey by at most this
 *                                   (0.05 m)
 * @param acceptOperatorSuppliedPoints frames whose reconstruction points were entered by hand (OPERATOR_SUPPLIED) are
 *                                   eligible for metric routing and physical AR guidance only when this is true; false by
 *                                   default, so only triangulated measurement points make a venue metrically usable
 */
@ConfigurationProperties("chaya.frames")
public record FrameProperties(int minDistanceReferences, double maxScaleRelativeSpread, double maxControlPointRmsM,
                              double maxFloorPointsRmsM, double maxGravityDisagreementDeg, double maxDeviceGravityTiltDeg,
                              double maxAnchorResidualM, double maxAnchorResidualDeg, double minReferenceLengthM,
                              double maxControlPointResidualM, double minControlPointSpanM, double minTriangulationAngleDeg,
                              double maxReprojectionRmsPx, double maxReprojectionPx, double maxVideoFrameOffsetSeconds,
                              double maxHoldoutRelativeError, double maxHoldoutErrorM, boolean acceptOperatorSuppliedPoints) {

    public FrameProperties {
        if (minDistanceReferences < 2) minDistanceReferences = 2;
        if (maxScaleRelativeSpread <= 0) maxScaleRelativeSpread = 0.03;
        if (maxControlPointRmsM <= 0) maxControlPointRmsM = 0.05;
        if (maxFloorPointsRmsM <= 0) maxFloorPointsRmsM = 0.05;
        if (maxGravityDisagreementDeg <= 0) maxGravityDisagreementDeg = 5.0;
        if (maxDeviceGravityTiltDeg <= 0) maxDeviceGravityTiltDeg = 10.0;
        if (maxAnchorResidualM <= 0) maxAnchorResidualM = 0.3;
        if (maxAnchorResidualDeg <= 0) maxAnchorResidualDeg = 8.0;
        if (minReferenceLengthM <= 0) minReferenceLengthM = 0.3;
        if (maxControlPointResidualM <= 0) maxControlPointResidualM = 0.10;
        if (minControlPointSpanM <= 0) minControlPointSpanM = 0.5;
        if (minTriangulationAngleDeg <= 0) minTriangulationAngleDeg = 2.0;
        if (maxReprojectionRmsPx <= 0) maxReprojectionRmsPx = 4.0;
        if (maxReprojectionPx <= 0) maxReprojectionPx = 8.0;
        if (maxVideoFrameOffsetSeconds <= 0) maxVideoFrameOffsetSeconds = 0.05;
        if (maxHoldoutRelativeError <= 0) maxHoldoutRelativeError = 0.02;
        if (maxHoldoutErrorM <= 0) maxHoldoutErrorM = 0.05;
    }

    public Triangulator.Limits triangulationLimits() {
        return new Triangulator.Limits(2, minTriangulationAngleDeg, maxReprojectionRmsPx, maxReprojectionPx);
    }
}
