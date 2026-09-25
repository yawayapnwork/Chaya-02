package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.ar.AnchorService;
import dev.chaya.api.ar.ArDeviceFrame;
import dev.chaya.api.ar.ArDtos.Anchor;
import dev.chaya.api.ar.ArDtos.AnchorObservation;
import dev.chaya.api.ar.ArDtos.AnchorRequest;
import dev.chaya.api.ar.ArDtos.Pose;
import dev.chaya.api.ar.ArDtos.RelocalizationResponse;
import dev.chaya.api.frame.Quaternion;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Anchor CRUD, calibration gating, and relocalization end-to-end against real Postgres (ar_anchor from
 * V14__ar_anchors.sql). Skipped, not failed, without Docker (AbstractIntegrationTest).
 */
class AnchorServiceTest extends AbstractIntegrationTest {

    @Autowired
    private AnchorService anchors;

    private static final Pose ORIGIN = new Pose(0, 0, 0, 0, 0, 0, 1);

    /** A marker whose digital orientation is the canonical axes, seen by a gravity-aligned (+Y up) device standing at its
     * own origin: the observed orientation is the canonical axes expressed in device axes. */
    private static final Pose SEEN_FROM_DEVICE_ORIGIN;

    static {
        Quaternion q = ArDeviceFrame.DEVICE_TO_CANONICAL_AXES.conjugate();
        SEEN_FROM_DEVICE_ORIGIN = new Pose(0, 0, 0, q.x(), q.y(), q.z(), q.w());
    }

    /** A tree whose floor has a canonical coordinate frame (an identity fixture frame), as anchors require. */
    private Fixtures.Tree calibratedTree() {
        var t = fx.tree();
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL");
        return t;
    }

    private Actor actorFor(UUID org, UUID venue) {
        return new Actor(Actor.Kind.USER, "test-user", org, Set.of(venue), Set.of(Role.ADMIN));
    }

    private AnchorRequest markerAt(double x, double y, double z) {
        return new AnchorRequest("ARUCO_MARKER", "marker-" + UUID.randomUUID(), ORIGIN, new Pose(x, y, z, 0, 0, 0, 1));
    }

    @Test
    void createdAnchorStartsUncalibratedAndCanBeCalibrated() {
        var t = calibratedTree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor created = anchors.create(actor, t.venue(), t.floor(), markerAt(1, 0, 0));
        assertThat(created.calibrationStatus()).isEqualTo("UNCALIBRATED");
        assertThat(created.lastCalibratedAt()).isNull();

        Anchor calibrated = anchors.calibrate(actor, t.venue(), t.floor(), created.id());
        assertThat(calibrated.calibrationStatus()).isEqualTo("CALIBRATED");
        assertThat(calibrated.lastCalibratedAt()).isNotNull();
    }

    @Test
    void editingAnchorPoseResetsCalibration() {
        var t = calibratedTree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor created = anchors.create(actor, t.venue(), t.floor(), markerAt(1, 0, 0));
        anchors.calibrate(actor, t.venue(), t.floor(), created.id());

        Anchor updated = anchors.update(actor, t.venue(), t.floor(), created.id(), markerAt(2, 0, 0));
        assertThat(updated.calibrationStatus()).isEqualTo("UNCALIBRATED");
        assertThat(updated.lastCalibratedAt()).isNull();
    }

    @Test
    void relocalizationRefusesAnUncalibratedAnchor() {
        var t = calibratedTree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor created = anchors.create(actor, t.venue(), t.floor(), markerAt(1, 0, 0));

        assertThatThrownBy(() -> anchors.relocalize(actor, t.venue(), t.floor(),
            List.of(new AnchorObservation(created.id(), ORIGIN))))
            .isInstanceOf(ApiException.class).hasMessageContaining("not been calibrated");
    }

    @Test
    void relocalizationWithOneCalibratedAnchorSolvesTheTransformAndReportsTheResidualAsUnknown() {
        var t = calibratedTree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor created = anchors.create(actor, t.venue(), t.floor(), markerAt(4, 0, 0));
        anchors.calibrate(actor, t.venue(), t.floor(), created.id());

        RelocalizationResponse response = anchors.relocalize(actor, t.venue(), t.floor(),
            List.of(new AnchorObservation(created.id(), SEEN_FROM_DEVICE_ORIGIN)));
        assertThat(response.anchorsUsed()).isEqualTo(1);
        assertThat(response.residualMeters()).as("one anchor has nothing to disagree with: unknown, not zero").isNull();
        assertThat(response.deviceToVenueTransform().x()).isCloseTo(4, within(1e-9));
        assertThat(response.gravityTiltDegrees()).isLessThan(1e-9);
        assertThat(response.deviceFrameConvention()).isEqualTo(ArDeviceFrame.CONVENTION);
        assertThat(response.coordinateFrameId()).isEqualTo(created.coordinateFrameId());
    }

    @Test
    void relocalizationWithTwoCalibratedAnchorsReportsRealResidual() {
        var t = calibratedTree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor a1 = anchors.calibrate(actor, t.venue(), t.floor(),
            anchors.create(actor, t.venue(), t.floor(), markerAt(0, 0, 0)).id());
        Anchor a2 = anchors.calibrate(actor, t.venue(), t.floor(),
            anchors.create(actor, t.venue(), t.floor(), markerAt(0.3, 0, 0)).id());

        RelocalizationResponse response = anchors.relocalize(actor, t.venue(), t.floor(),
            List.of(new AnchorObservation(a1.id(), SEEN_FROM_DEVICE_ORIGIN), new AnchorObservation(a2.id(), SEEN_FROM_DEVICE_ORIGIN)));
        assertThat(response.anchorsUsed()).isEqualTo(2);
        assertThat(response.residualMeters()).isCloseTo(0.3, within(1e-9));
    }

    @Test
    void relocalizationRefusesATransformThatContradictsGravity() {
        var t = calibratedTree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor a = anchors.calibrate(actor, t.venue(), t.floor(), anchors.create(actor, t.venue(), t.floor(), markerAt(1, 0, 0)).id());
        // An observation reported as if the device frame were +Z up: the solved transform would tip the device's up
        // (+Y) onto the venue's horizontal.
        assertThatThrownBy(() -> anchors.relocalize(actor, t.venue(), t.floor(), List.of(new AnchorObservation(a.id(), ORIGIN))))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("RELOCALIZATION_GRAVITY_MISMATCH"));
    }

    @Test
    void anchorsNeedACalibratedFloorFrame() {
        var t = fx.tree(); // no coordinate frame
        Actor actor = actorFor(t.org(), t.venue());
        assertThatThrownBy(() -> anchors.create(actor, t.venue(), t.floor(), markerAt(1, 0, 0)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_CALIBRATED"));
    }

    @Test
    void anAnchorPlacedInAnOlderFrameCannotRelocalizeOrBeCalibrated() {
        var t = calibratedTree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor a = anchors.calibrate(actor, t.venue(), t.floor(), anchors.create(actor, t.venue(), t.floor(), markerAt(1, 0, 0)).id());
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL"); // a different reconstruction becomes current

        assertThatThrownBy(() -> anchors.relocalize(actor, t.venue(), t.floor(), List.of(new AnchorObservation(a.id(), SEEN_FROM_DEVICE_ORIGIN))))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ANCHOR_FRAME_STALE"));
        assertThatThrownBy(() -> anchors.calibrate(actor, t.venue(), t.floor(), a.id()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ANCHOR_FRAME_STALE"));
        Anchor replaced = anchors.update(actor, t.venue(), t.floor(), a.id(), markerAt(1, 0, 0));
        assertThat(replaced.coordinateFrameId()).as("re-entering the pose binds it to the current frame").isNotEqualTo(a.coordinateFrameId());
    }

    @Test
    void duplicateObservationsOfOneAnchorAreRejected() {
        var t = calibratedTree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor a = anchors.calibrate(actor, t.venue(), t.floor(), anchors.create(actor, t.venue(), t.floor(), markerAt(1, 0, 0)).id());
        assertThatThrownBy(() -> anchors.relocalize(actor, t.venue(), t.floor(), List.of(
                new AnchorObservation(a.id(), SEEN_FROM_DEVICE_ORIGIN), new AnchorObservation(a.id(), SEEN_FROM_DEVICE_ORIGIN))))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_OBSERVATIONS"));
    }

    @Test
    void deletedAnchorIsNotListedAndCannotBeFetched() {
        var t = calibratedTree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor created = anchors.create(actor, t.venue(), t.floor(), markerAt(1, 0, 0));
        anchors.delete(actor, t.venue(), t.floor(), created.id());

        assertThat(anchors.list(actor, t.venue(), t.floor())).extracting(Anchor::id).doesNotContain(created.id());
        assertThatThrownBy(() -> anchors.get(actor, t.venue(), t.floor(), created.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anchorFromAnotherVenueIsNotFoundEvenWithACorrectFloorAndId() {
        var t = calibratedTree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor created = anchors.create(actor, t.venue(), t.floor(), markerAt(1, 0, 0));

        UUID otherVenue = fx.venue(t.org());
        UUID otherFloor = fx.floor(t.org(), otherVenue, 0);
        fx.calibratedFloor(t.org(), otherVenue, otherFloor, "FLOOR_LOCAL");
        Actor otherActor = actorFor(t.org(), otherVenue);
        assertThatThrownBy(() -> anchors.get(otherActor, otherVenue, otherFloor, created.id()))
            .isInstanceOf(NotFoundException.class);
    }
}
