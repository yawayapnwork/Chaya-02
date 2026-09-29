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
     * axes (docs/ar.md, "Marker pose convention"). scanVersionId: the ScanVersion the anchor was registered against
     * (floor_current_scan_version when it was set, or bound by the bootstrap of that reconstruction's version); null while
     * the floor's current reconstruction has no version. */
    public record Anchor(UUID id, UUID venueId, UUID floorId, String markerType, String markerIdentifier, Double markerSizeMeters,
                         Pose physicalPose, Pose digitalPose, String calibrationStatus, Instant lastCalibratedAt,
                         UUID coordinateFrameId, UUID scanVersionId) {}

    /** A live detection of one already-registered marker, reported by an AR client attempting
     * relocalization. `observedPose` is the marker's pose as the device's own tracking frame currently
     * sees it -- real sensor output, never fabricated (see docs/ar.md "Do not fabricate device sensor
     * data") -- in the device convention {@link ArDeviceFrame#CONVENTION}: metres, gravity-aligned, +Y up. */
    public record AnchorObservation(@NotNull UUID anchorId, @NotNull Pose observedPose) {}

    public record RelocalizationRequest(@Valid List<AnchorObservation> observations) {}

    /** The device_frame -> venue_frame transform solved from the reported observations, plus a residual
     * (meters) reporting how much the anchors disagreed when more than one was used -- never a claimed
     * accuracy figure, an actual measured spread across the anchors that took part. */
    public record RelocalizationResponse(Pose deviceToVenueTransform, Double residualMeters, int anchorsUsed,
                                         double gravityTiltDegrees, String deviceFrameConvention, UUID coordinateFrameId) {}
}
