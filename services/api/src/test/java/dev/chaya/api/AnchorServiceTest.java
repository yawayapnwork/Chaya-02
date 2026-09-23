package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.ar.AnchorService;
import dev.chaya.api.ar.ArDtos.Anchor;
import dev.chaya.api.ar.ArDtos.AnchorObservation;
import dev.chaya.api.ar.ArDtos.AnchorRequest;
import dev.chaya.api.ar.ArDtos.Pose;
import dev.chaya.api.ar.ArDtos.RelocalizationResponse;
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

    private Actor actorFor(UUID org, UUID venue) {
        return new Actor(Actor.Kind.USER, "test-user", org, Set.of(venue), Set.of(Role.ADMIN));
    }

    private AnchorRequest markerAt(double x, double y, double z) {
        return new AnchorRequest("ARUCO_MARKER", "marker-" + UUID.randomUUID(), ORIGIN, new Pose(x, y, z, 0, 0, 0, 1));
    }

    @Test
    void createdAnchorStartsUncalibratedAndCanBeCalibrated() {
        var t = fx.tree();
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
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor created = anchors.create(actor, t.venue(), t.floor(), markerAt(1, 0, 0));
        anchors.calibrate(actor, t.venue(), t.floor(), created.id());

        Anchor updated = anchors.update(actor, t.venue(), t.floor(), created.id(), markerAt(2, 0, 0));
        assertThat(updated.calibrationStatus()).isEqualTo("UNCALIBRATED");
        assertThat(updated.lastCalibratedAt()).isNull();
    }

    @Test
    void relocalizationRefusesAnUncalibratedAnchor() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor created = anchors.create(actor, t.venue(), t.floor(), markerAt(1, 0, 0));

        assertThatThrownBy(() -> anchors.relocalize(actor, t.venue(), t.floor(),
            List.of(new AnchorObservation(created.id(), ORIGIN))))
            .isInstanceOf(ApiException.class).hasMessageContaining("not been calibrated");
    }

    @Test
    void relocalizationWithOneCalibratedAnchorSolvesTheTransformWithZeroResidual() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor created = anchors.create(actor, t.venue(), t.floor(), markerAt(4, 0, 0));
        anchors.calibrate(actor, t.venue(), t.floor(), created.id());

        RelocalizationResponse response = anchors.relocalize(actor, t.venue(), t.floor(),
            List.of(new AnchorObservation(created.id(), ORIGIN)));
        assertThat(response.anchorsUsed()).isEqualTo(1);
        assertThat(response.residualMeters()).isCloseTo(0, within(1e-9));
        assertThat(response.deviceToVenueTransform().x()).isCloseTo(4, within(1e-9));
    }

    @Test
    void relocalizationWithTwoCalibratedAnchorsReportsRealResidual() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor a1 = anchors.calibrate(actor, t.venue(), t.floor(),
            anchors.create(actor, t.venue(), t.floor(), markerAt(0, 0, 0)).id());
        Anchor a2 = anchors.calibrate(actor, t.venue(), t.floor(),
            anchors.create(actor, t.venue(), t.floor(), markerAt(0.3, 0, 0)).id());

        RelocalizationResponse response = anchors.relocalize(actor, t.venue(), t.floor(),
            List.of(new AnchorObservation(a1.id(), ORIGIN), new AnchorObservation(a2.id(), ORIGIN)));
        assertThat(response.anchorsUsed()).isEqualTo(2);
        assertThat(response.residualMeters()).isCloseTo(0.3, within(1e-9));
    }

    @Test
    void deletedAnchorIsNotListedAndCannotBeFetched() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor created = anchors.create(actor, t.venue(), t.floor(), markerAt(1, 0, 0));
        anchors.delete(actor, t.venue(), t.floor(), created.id());

        assertThat(anchors.list(actor, t.venue(), t.floor())).extracting(Anchor::id).doesNotContain(created.id());
        assertThatThrownBy(() -> anchors.get(actor, t.venue(), t.floor(), created.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anchorFromAnotherVenueIsNotFoundEvenWithACorrectFloorAndId() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        Anchor created = anchors.create(actor, t.venue(), t.floor(), markerAt(1, 0, 0));

        UUID otherVenue = fx.venue(t.org());
        UUID otherFloor = fx.floor(t.org(), otherVenue, 0);
        Actor otherActor = actorFor(t.org(), otherVenue);
        assertThatThrownBy(() -> anchors.get(otherActor, otherVenue, otherFloor, created.id()))
            .isInstanceOf(NotFoundException.class);
    }
}
