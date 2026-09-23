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

    public record AnchorRequest(@NotBlank String markerType, @NotBlank String markerIdentifier,
                                @NotNull Pose physicalPose, @NotNull Pose digitalPose) {}

    public record Anchor(UUID id, UUID venueId, UUID floorId, String markerType, String markerIdentifier,
                         Pose physicalPose, Pose digitalPose, String calibrationStatus, Instant lastCalibratedAt) {}

    /** A live detection of one already-registered marker, reported by an AR client attempting
     * relocalization. `observedPose` is the marker's pose as the device's own tracking frame currently
     * sees it -- real sensor output, never fabricated (see docs/ar.md "Do not fabricate device sensor
     * data"). */
    public record AnchorObservation(@NotNull UUID anchorId, @NotNull Pose observedPose) {}

    public record RelocalizationRequest(@Valid List<AnchorObservation> observations) {}

    /** The device_frame -> venue_frame transform solved from the reported observations, plus a residual
     * (meters) reporting how much the anchors disagreed when more than one was used -- never a claimed
     * accuracy figure, an actual measured spread across the anchors that took part. */
    public record RelocalizationResponse(Pose deviceToVenueTransform, double residualMeters, int anchorsUsed) {}
}
