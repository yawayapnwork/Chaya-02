package dev.chaya.api.ar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ArDtos {

    private ArDtos() {}

    /** Position + orientation (unit quaternion), in one frame. Never assumed normalized by callers --
     * see CoordinateTransform, which normalizes defensively before using q. */
    public record Pose(double x, double y, double z, double qx, double qy, double qz, double qw) {}

    /** markerSizeMeters: the printed width of the marker, in metres. Required for IMAGE_TARGET (WebXR image tracking needs
     * it, and checks the width it measures against it; docs/ar.md). */
    public record AnchorRequest(@NotBlank String markerType, @NotBlank String markerIdentifier, Double markerSizeMeters,
                                @NotNull Pose physicalPose, @NotNull Pose digitalPose) {}

    /** digitalPose is in canonical venue metres, +Z up, in coordinateFrameId (the floor's current frame when it was set;
     * docs/coordinate-frames.md). For an IMAGE_TARGET it is the pose of the printed image's centre, with the image's own
     * axes (docs/ar.md, "Marker pose convention"). scanVersionId: the ScanVersion the pose was entered against (the version
     * its floor published then, V28); coordinateFrameId a calibration of that version's reconstruction. poseId/poseRevision:
     * the immutable versioned pose record (ar_anchor_pose) these values are -- a recalibration or an edit adds a revision and
     * never changes an earlier one, so the anchor as of an older version keeps its pose. A pre-V28 anchor that was never
     * entered against a version has no pose record (scanVersionId, poseId null) and cannot be used for relocalization. */
    public record Anchor(UUID id, UUID venueId, UUID floorId, String markerType, String markerIdentifier, Double markerSizeMeters,
                         Pose physicalPose, Pose digitalPose, String calibrationStatus, Instant lastCalibratedAt,
                         UUID coordinateFrameId, UUID scanVersionId, UUID poseId, Integer poseRevision) {}

    /** A live detection of one already-registered marker, reported by an AR client attempting
     * relocalization. `observedPose` is the marker's pose as the device's own tracking frame currently
     * sees it -- real sensor output, never fabricated (see docs/ar.md "Do not fabricate device sensor
     * data") -- in the device convention {@link ArDeviceFrame#CONVENTION}: metres, gravity-aligned, +Y up. */
    public record AnchorObservation(@NotNull UUID anchorId, @NotNull Pose observedPose) {}

    /** scanVersionId (optional): the scan version the client is showing. When given it must be the version the floor
     * publishes, or the request is refused (VERSION_MISMATCH): a device must not be placed in one version's frame while it
     * draws another version's content. */
    public record RelocalizationRequest(@Valid List<AnchorObservation> observations, UUID scanVersionId) {
        public RelocalizationRequest(List<AnchorObservation> observations) {
            this(observations, null);
        }
    }

    /** The device_frame -> venue_frame transform solved from the reported observations. residualMeters / residualDegrees:
     * the largest distance (orientation difference) between a used anchor's observation mapped through the transform and
     * its registered pose -- null with one anchor, where there is nothing to compare against; never a claimed accuracy
     * figure. anchorResiduals has one entry per observation; rejectedAnchorIds are anchors set aside as inconsistent with
     * the others (with three or more observed). anchorsUsed counts the anchors the transform was solved from. */
    public record RelocalizationResponse(Pose deviceToVenueTransform, Double residualMeters, int anchorsUsed,
                                         double gravityTiltDegrees, String deviceFrameConvention, UUID coordinateFrameId,
                                         UUID scanVersionId, Double residualDegrees, List<AnchorResidual> anchorResiduals,
                                         List<UUID> rejectedAnchorIds) {}

    /**
     * One observed anchor, mapped through the solved transform and compared with its registered pose. Measured, not
     * assumed. used=false: set aside as inconsistent with the other anchors (moved, misidentified or mis-registered).
     */
    public record AnchorResidual(UUID anchorId, double translationMeters, double rotationDegrees, boolean used) {}
}
