package dev.chaya.api.planning;

import static org.assertj.core.api.Assertions.assertThat;

import dev.chaya.api.planning.Reconnaissance.Assessment;
import dev.chaya.api.planning.Reconnaissance.Issue;
import dev.chaya.api.planning.Reconnaissance.PoseEvidence;
import dev.chaya.api.planning.Reconnaissance.Requirements;
import dev.chaya.api.planning.Reconnaissance.TrackingEvent;
import dev.chaya.api.planning.Reconnaissance.Trajectory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a reconnaissance walk must contain, on deterministic synthetic walks. */
class ReconnaissanceTest {

    private static final Requirements REQ = Requirements.defaults();

    /** A straight walk east at 1 m/s, 4 Hz, from `t0`. */
    private static List<PoseEvidence> walk(String source, String frame, double t0, double seconds, Double yaw) {
        List<PoseEvidence> out = new ArrayList<>();
        for (int i = 0; i <= seconds * 4; i++) {
            double t = t0 + i * 0.25;
            out.add(new PoseEvidence(t, 1 + (t - t0), 2, yaw, source, frame));
        }
        return out;
    }

    private static List<String> codes(Assessment a) {
        return a.issues().stream().map(Issue::code).toList();
    }

    @Test
    void videoAloneIsNotAReconnaissanceInput() {
        Assessment a = Reconnaissance.assess(List.of(), List.of(), null, 0, false, true, REQ);
        assertThat(a.verdict()).isEqualTo("NOT_STARTED");
        assertThat(codes(a)).contains("VIDEO_ONLY");
        assertThat(a.issues().get(0).message()).contains("no metric scale");
        assertThat(a.metric()).isFalse();
    }

    @Test
    void aTenSecondTrackedWalkWithWallObservationsIsReadyAndBoundsAreEstimable() {
        Assessment a = Reconnaissance.assess(walk("webxr", "s1", 0, 10, 0.0), List.of(), null, 40, false, false, REQ);
        assertThat(a.verdict()).isEqualTo("READY");
        assertThat(a.positionBasis()).isEqualTo("TRACKED");
        assertThat(a.metric()).isTrue();
        assertThat(a.boundsEstimable()).isTrue();
        assertThat(a.segments()).isEqualTo(1);
        assertThat(a.headingShare()).isEqualTo(1.0);
        assertThat(a.pathLengthMeters()).isCloseTo(10.0, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void aShortWalkIsIncompleteAndSaysWhatIsMissing() {
        Assessment a = Reconnaissance.assess(walk("webxr", "s1", 0, 2, null), List.of(), null, 3, false, false, REQ);
        assertThat(a.verdict()).isEqualTo("INCOMPLETE");
        assertThat(codes(a)).contains("WALK_TOO_SHORT", "PATH_TOO_SHORT", "BOUNDS_NEED_EVIDENCE", "HEADINGS_MISSING");
        assertThat(a.boundsEstimable()).isFalse();
    }

    @Test
    void trackingLossSplitsTheTrajectoryAndTooMuchLossMakesTheWalkUnreliable() {
        List<PoseEvidence> poses = new ArrayList<>(walk("webxr", "s1", 0, 5, 0.0));
        poses.addAll(walk("webxr", "s1", 7, 5, 0.0).stream().map(p -> new PoseEvidence(p.t(), p.x() + 5, p.y(), 0.0, "webxr", "s1")).toList());
        List<TrackingEvent> events = List.of(new TrackingEvent(5.5, "LOST"), new TrackingEvent(6.9, "TRACKING"));

        Trajectory tr = Reconnaissance.trajectory(poses, events, null, REQ);
        assertThat(tr.samples().get(0).segment()).isZero();
        assertThat(tr.samples().get(tr.samples().size() - 1).segment()).isEqualTo(1);
        assertThat(tr.gaps()).hasSize(1);
        assertThat(tr.gaps().get(0).reason()).isEqualTo("TRACKING_LOST");
        assertThat(tr.trackedMeters()).as("no distance across the loss").isCloseTo(10.0, org.assertj.core.api.Assertions.within(1e-9));

        Assessment ok = Reconnaissance.assess(poses, events, null, 20, false, false, REQ);
        assertThat(ok.verdict()).isEqualTo("READY");
        assertThat(ok.trackingLosses()).isEqualTo(1);
        assertThat(codes(ok)).contains("TRACKING_INTERRUPTED");

        // Same walk, but tracking was lost for longer than it was available.
        List<PoseEvidence> sparse = new ArrayList<>(walk("webxr", "s1", 0, 4, 0.0));
        sparse.addAll(walk("webxr", "s1", 14, 4, 0.0));
        Assessment bad = Reconnaissance.assess(sparse, List.of(new TrackingEvent(5, "LOST")), null, 20, false, false, REQ);
        assertThat(bad.verdict()).isEqualTo("INCOMPLETE");
        assertThat(codes(bad)).contains("TRACKING_UNRELIABLE");
    }

    @Test
    void aGapBetweenTrackedSamplesIsATrackingGapEvenWithoutAnEvent() {
        List<PoseEvidence> poses = new ArrayList<>(walk("webxr", "s1", 0, 5, null));
        poses.addAll(walk("webxr", "s1", 8, 5, null));
        Trajectory tr = Reconnaissance.trajectory(poses, List.of(), null, REQ);
        assertThat(tr.gaps()).extracting(Reconnaissance.Gap::reason).containsExactly("TRACKING_GAP");
    }

    @Test
    void positionsFromAnotherArSessionAreNotPlacedOnThePlan() {
        List<PoseEvidence> poses = new ArrayList<>(walk("webxr", "old", 0, 10, 0.0));
        poses.addAll(walk("webxr", "new", 20, 3, 0.0));
        Trajectory latest = Reconnaissance.trajectory(poses, List.of(), null, REQ);
        assertThat(latest.frameId()).as("without a scene frame, the latest session's").isEqualTo("new");
        assertThat(latest.excludedOtherFrame()).isEqualTo(41);

        Assessment a = Reconnaissance.assess(poses, List.of(), "old", 20, false, false, REQ);
        assertThat(a.frameId()).isEqualTo("old");
        assertThat(a.verdict()).isEqualTo("READY");
        assertThat(codes(a)).contains("FRAME_MISMATCH");
        assertThat(a.excludedOtherFrame()).isEqualTo(13);
    }

    @Test
    void manualMarksAreInterpolatedAndNeedAMeasuredOutline() {
        List<PoseEvidence> marks = List.of(
            new PoseEvidence(0, 1, 1, null, "manual", null),
            new PoseEvidence(10, 4, 1, null, "manual", null),
            new PoseEvidence(20, 4, 4, null, "manual", null));
        Trajectory tr = Reconnaissance.trajectory(marks, List.of(), null, REQ);
        assertThat(tr.pieces()).extracting(Reconnaissance.PathPiece::kind).containsOnly("INTERPOLATED");
        assertThat(tr.interpolatedMeters()).isEqualTo(6.0);

        Assessment noOutline = Reconnaissance.assess(marks, List.of(), null, 0, false, false, REQ);
        assertThat(noOutline.verdict()).isEqualTo("INCOMPLETE");
        assertThat(noOutline.metric()).isFalse();
        assertThat(codes(noOutline)).contains("MANUAL_NEEDS_MEASURED_OUTLINE", "INTERPOLATED_PATH");

        Assessment measured = Reconnaissance.assess(marks, List.of(), null, 0, true, false, REQ);
        assertThat(measured.verdict()).isEqualTo("READY");
        assertThat(measured.positionBasis()).isEqualTo("MANUAL");
        assertThat(measured.metric()).isTrue();
    }

    @Test
    void aChangeOfSourceIsNeverJoinedAndUnsupportedSourcesAreIgnored() {
        List<PoseEvidence> poses = new ArrayList<>(walk("operator-trajectory", null, 0, 10, null));
        poses.add(new PoseEvidence(10.1, 11.2, 2, null, "manual", null));
        poses.add(new PoseEvidence(10.2, 11.3, 2, null, "video", null));
        Trajectory tr = Reconnaissance.trajectory(poses, List.of(), null, REQ);
        assertThat(tr.samples()).hasSize(42);
        assertThat(tr.gaps()).extracting(Reconnaissance.Gap::reason).containsExactly("SOURCE_CHANGED");
        Assessment a = Reconnaissance.assess(poses, List.of(), null, 0, true, false, REQ);
        assertThat(a.positionBasis()).isEqualTo("MIXED");
        assertThat(codes(a)).contains("IMPORTED_SCALE_ASSUMED");
    }
}
