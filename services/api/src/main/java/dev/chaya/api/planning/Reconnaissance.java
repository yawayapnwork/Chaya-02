package dev.chaya.api.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The input of a reconnaissance walk, what it must contain to be planned from, and how its positions become planner
 * trajectory segments. Pure and deterministic. docs/route-planning.md section 12 and docs/capture-hud.md describe it.
 *
 * <p><b>What counts as a position.</b> Only these sources are accepted, because only they give a plan-view position in
 * metres while the walk happens:
 * <ul>
 *   <li>{@code webxr}, {@code arkit}: the device platform's own world tracking (ARCore through WebXR, ARKit). Metric and
 *       gravity-aligned, in a frame that exists only for one AR session ({@code frameId}).</li>
 *   <li>{@code operator-trajectory}: a trajectory the operator imports from another tracker. Its metric scale is the
 *       operator's statement, recorded as an assumption.</li>
 *   <li>{@code manual}: the operator marking their spot on a to-scale plan. Metric only through a measured outline, and
 *       nothing is known about the path between two marks (it is interpolated, and shown as such).</li>
 * </ul>
 * A video file is not a position source: it carries no camera poses, and structure-from-motion poses recovered from it
 * have no metric scale and exist only after processing. {@link #VIDEO_ONLY} is the explanation reported for it.
 */
public final class Reconnaissance {

    public static final Set<String> TRACKED_SOURCES = Set.of("webxr", "arkit");
    public static final String IMPORTED_SOURCE = "operator-trajectory";
    public static final String MANUAL_SOURCE = "manual";
    public static final Set<String> SOURCES = Set.of("webxr", "arkit", IMPORTED_SOURCE, MANUAL_SOURCE);

    public static final String VIDEO_ONLY = "Uploaded video is not a reconnaissance input: a video file carries no camera poses, "
        + "and poses recovered from video alone (structure from motion) have no metric scale and exist only after processing, not "
        + "during the walk. Record the walk with AR tracking, mark positions on a measured plan, or import a tracked trajectory.";

    /** One stored position. {@code t} is seconds; {@code frameId} is null for legacy samples (assumed in the scene frame). */
    public record PoseEvidence(double t, double x, double y, Double yawRadians, String source, String frameId) {}

    /** A tracking state change the client reported: TRACKING, LIMITED, LOST, SESSION_STARTED, SESSION_ENDED, INTERRUPTED, ... */
    public record TrackingEvent(double t, String state) {}

    /**
     * @param minDurationSeconds  shortest usable walk
     * @param minSamples          fewest usable tracked/imported samples
     * @param minManualMarks      fewest manual marks
     * @param minPathMeters       shortest walked path
     * @param maxLostShare        largest share of the walk's duration spent with tracking lost
     * @param trackedGapSeconds   two tracked samples further apart than this are not joined (a tracking gap)
     * @param manualJoinSeconds   two manual marks at most this far apart are joined by a straight (interpolated) line
     * @param minWallObservations wall observations needed before bounds estimation is attempted
     */
    public record Requirements(double minDurationSeconds, int minSamples, int minManualMarks, double minPathMeters, double maxLostShare,
                               double trackedGapSeconds, double manualJoinSeconds, int minWallObservations) {

        public static Requirements defaults() {
            return new Requirements(8, 10, 3, 3.0, 0.3, 1.5, 60, 12);
        }
    }

    /** How one stretch between two consecutive used samples is known: TRACKED, IMPORTED or INTERPOLATED (manual). */
    public record PathPiece(double x0, double y0, double x1, double y1, String kind) {}

    /** A period with no usable position: a reported loss, or a gap between tracked samples. */
    public record Gap(double fromT, double toT, String reason) {}

    /**
     * The planner input built from the evidence.
     *
     * @param samples   in time order, with a new segment number after every gap, frame change or change of source class
     * @param pieces    how each joined stretch is known, for display
     * @param excludedOtherFrame samples recorded in another tracking frame than the one used (not placeable on this plan)
     */
    public record Trajectory(List<TrajectorySample> samples, List<PathPiece> pieces, List<Gap> gaps, String frameId,
                             int excludedOtherFrame, double trackedMeters, double interpolatedMeters, double importedMeters) {}

    public record Issue(String code, String severity, String message) {}

    /**
     * @param verdict       READY, INCOMPLETE (more walking or evidence is needed), NOT_STARTED, or UNSUPPORTED
     * @param positionBasis TRACKED, IMPORTED, MANUAL, MIXED or NONE
     * @param metric        whether positions are in metres: true for tracked sources, true for imported ones (operator's
     *                      statement), true for manual marks only on a measured outline
     * @param boundsEstimable whether there is enough evidence to attempt RoomBoundsEstimator (tracked or imported positions and
     *                      enough wall observations)
     */
    public record Assessment(String verdict, String positionBasis, boolean metric, int samples, double durationSeconds,
                             double pathLengthMeters, int segments, double headingShare, int trackingLosses, double lostSeconds,
                             double longestGapSeconds, String frameId, int excludedOtherFrame, int wallObservations,
                             boolean boundsEstimable, List<Issue> issues, Requirements requirements) {}

    private Reconnaissance() {}

    static String sourceClass(String source) {
        if (source == null || MANUAL_SOURCE.equals(source)) {
            return "MANUAL";
        }
        if (TRACKED_SOURCES.contains(source)) {
            return "TRACKED";
        }
        return IMPORTED_SOURCE.equals(source) ? "IMPORTED" : "UNSUPPORTED";
    }

    /**
     * Builds planner segments from stored positions. Uses only samples in {@code frameId} (null samples count as in it);
     * if {@code frameId} is null, the frame of the most recent non-null-frame sample is used.
     */
    public static Trajectory trajectory(List<PoseEvidence> poses, List<TrackingEvent> events, String frameId, Requirements req) {
        List<PoseEvidence> sorted = new ArrayList<>();
        for (PoseEvidence p : poses) {
            if (Double.isFinite(p.t()) && Double.isFinite(p.x()) && Double.isFinite(p.y()) && !"UNSUPPORTED".equals(sourceClass(p.source()))) {
                sorted.add(p);
            }
        }
        sorted.sort(Comparator.comparingDouble(PoseEvidence::t));
        String frame = frameId;
        if (frame == null) {
            for (int i = sorted.size() - 1; i >= 0 && frame == null; i--) {
                frame = sorted.get(i).frameId();
            }
        }
        List<TrackingEvent> losses = new ArrayList<>();
        for (TrackingEvent e : events) {
            if (Set.of("LOST", "LIMITED", "SESSION_ENDED", "INTERRUPTED").contains(e.state())) {
                losses.add(e);
            }
        }
        List<TrajectorySample> out = new ArrayList<>();
        List<PathPiece> pieces = new ArrayList<>();
        List<Gap> gaps = new ArrayList<>();
        int excluded = 0, segment = 0;
        double tracked = 0, interpolated = 0, imported = 0;
        PoseEvidence prev = null;
        for (PoseEvidence p : sorted) {
            if (p.frameId() != null && !Objects.equals(p.frameId(), frame)) {
                excluded++;
                continue;
            }
            if (prev != null) {
                String cls = sourceClass(p.source()), prevCls = sourceClass(prev.source());
                double dt = p.t() - prev.t();
                String breakReason = null;
                if (!cls.equals(prevCls)) {
                    breakReason = "SOURCE_CHANGED";
                } else if (lossBetween(losses, prev.t(), p.t())) {
                    breakReason = "TRACKING_LOST";
                } else if (cls.equals("MANUAL") ? dt > req.manualJoinSeconds() : dt > req.trackedGapSeconds()) {
                    breakReason = cls.equals("MANUAL") ? "MARKS_TOO_FAR_APART" : "TRACKING_GAP";
                }
                if (breakReason != null) {
                    segment++;
                    gaps.add(new Gap(prev.t(), p.t(), breakReason));
                } else {
                    double d = Math.hypot(p.x() - prev.x(), p.y() - prev.y());
                    String kind = cls.equals("MANUAL") ? "INTERPOLATED" : cls;
                    pieces.add(new PathPiece(prev.x(), prev.y(), p.x(), p.y(), kind));
                    switch (kind) {
                        case "INTERPOLATED" -> interpolated += d;
                        case "IMPORTED" -> imported += d;
                        default -> tracked += d;
                    }
                }
            }
            out.add(new TrajectorySample(p.t(), p.x(), p.y(), p.yawRadians(), segment));
            prev = p;
        }
        return new Trajectory(List.copyOf(out), List.copyOf(pieces), List.copyOf(gaps), frame, excluded, round(tracked),
            round(interpolated), round(imported));
    }

    private static boolean lossBetween(List<TrackingEvent> losses, double t0, double t1) {
        for (TrackingEvent e : losses) {
            if (e.t() > t0 && e.t() < t1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a walk can be planned from.
     *
     * @param measuredOutline whether the scene is an operator-measured outline (needed for manual marks to be metric)
     * @param hasVideoMedia   whether the capture holds video files (reported if they are the only evidence)
     */
    public static Assessment assess(List<PoseEvidence> poses, List<TrackingEvent> events, String frameId, int wallObservations,
                                    boolean measuredOutline, boolean hasVideoMedia, Requirements req) {
        Trajectory tr = trajectory(poses, events, frameId, req);
        List<TrajectorySample> s = tr.samples();
        List<Issue> issues = new ArrayList<>();
        if (s.isEmpty()) {
            if (hasVideoMedia) {
                issues.add(new Issue("VIDEO_ONLY", "BLOCKING", VIDEO_ONLY));
            }
            if (tr.excludedOtherFrame() > 0) {
                issues.add(new Issue("FRAME_MISMATCH", "BLOCKING", tr.excludedOtherFrame() + " position(s) were recorded in another tracking "
                    + "session's frame and cannot be placed on this plan; re-align or restart the walk"));
            } else {
                issues.add(new Issue("NO_POSITIONS", "BLOCKING", "no position has been recorded: start AR tracking and walk the room, mark "
                    + "your position on a measured plan, or import a tracked trajectory"));
            }
            return new Assessment("NOT_STARTED", "NONE", false, 0, 0, 0, 0, 0, 0, 0, 0, tr.frameId(), tr.excludedOtherFrame(),
                wallObservations, false, List.copyOf(issues), req);
        }
        boolean anyTracked = false, anyImported = false, anyManual = false;
        int withYaw = 0;
        for (PoseEvidence p : poses) {
            if (p.frameId() != null && !Objects.equals(p.frameId(), tr.frameId())) {
                continue;
            }
            switch (sourceClass(p.source())) {
                case "TRACKED" -> anyTracked = true;
                case "IMPORTED" -> anyImported = true;
                case "MANUAL" -> anyManual = true;
                default -> { }
            }
        }
        for (TrajectorySample t : s) {
            if (t.yawRadians() != null) withYaw++;
        }
        String basis = (anyTracked ? 1 : 0) + (anyImported ? 1 : 0) + (anyManual ? 1 : 0) > 1 ? "MIXED"
            : anyTracked ? "TRACKED" : anyImported ? "IMPORTED" : "MANUAL";
        boolean metric = !anyManual || measuredOutline;
        double duration = s.get(s.size() - 1).t() - s.get(0).t();
        double length = tr.trackedMeters() + tr.interpolatedMeters() + tr.importedMeters();
        double lost = 0, longest = 0;
        int losses = 0;
        for (Gap g : tr.gaps()) {
            if (!g.reason().equals("SOURCE_CHANGED")) {
                double d = g.toT() - g.fromT();
                lost += d;
                longest = Math.max(longest, d);
                if (g.reason().startsWith("TRACKING")) losses++;
            }
        }
        boolean blocking = false;
        if (anyManual && !measuredOutline) {
            blocking = true;
            issues.add(new Issue("MANUAL_NEEDS_MEASURED_OUTLINE", "BLOCKING", "manual position marks are only metric on a measured, to-scale "
                + "outline; enter the measured room outline first"));
        }
        if (anyManual) {
            long marks = poses.stream().filter(p -> "MANUAL".equals(sourceClass(p.source()))
                && (p.frameId() == null || Objects.equals(p.frameId(), tr.frameId()))).count();
            if (marks < req.minManualMarks()) {
                blocking = true;
                issues.add(new Issue("TOO_FEW_MARKS", "BLOCKING", marks + " position mark(s); mark at least " + req.minManualMarks()
                    + " spots as you walk"));
            }
            issues.add(new Issue("INTERPOLATED_PATH", "INFO", fmt(tr.interpolatedMeters()) + " m of the path between manual marks is a "
                + "straight-line assumption, not a measurement"));
        } else {
            if (s.size() < req.minSamples()) {
                blocking = true;
                issues.add(new Issue("TOO_FEW_SAMPLES", "BLOCKING", s.size() + " usable position(s); at least " + req.minSamples()
                    + " are needed"));
            }
            if (duration < req.minDurationSeconds()) {
                blocking = true;
                issues.add(new Issue("WALK_TOO_SHORT", "BLOCKING", "the walk lasted " + fmt(duration) + " s; walk for at least "
                    + fmt(req.minDurationSeconds()) + " s"));
            }
        }
        if (length < req.minPathMeters()) {
            blocking = true;
            issues.add(new Issue("PATH_TOO_SHORT", "BLOCKING", "the walked path is " + fmt(length) + " m; walk at least "
                + fmt(req.minPathMeters()) + " m through the room"));
        }
        if (duration > 0 && lost / duration > req.maxLostShare()) {
            blocking = true;
            issues.add(new Issue("TRACKING_UNRELIABLE", "BLOCKING", "positions were unavailable for " + fmt(lost) + " of " + fmt(duration)
                + " s (" + losses + " tracking loss(es)); move more slowly and keep textured surfaces in view"));
        } else if (losses > 0) {
            issues.add(new Issue("TRACKING_INTERRUPTED", "WARNING", losses + " tracking loss(es), longest " + fmt(longest) + " s; nothing is "
                + "credited across them"));
        }
        if (tr.excludedOtherFrame() > 0) {
            issues.add(new Issue("FRAME_MISMATCH", "WARNING", tr.excludedOtherFrame() + " position(s) were recorded in another tracking "
                + "session's frame and are ignored; re-align to use them"));
        }
        if (withYaw < s.size()) {
            issues.add(new Issue("HEADINGS_MISSING", "INFO", (s.size() - withYaw) + " of " + s.size() + " positions have no heading; "
                + "coverage uses the direction of travel instead"));
        }
        if (anyImported) {
            issues.add(new Issue("IMPORTED_SCALE_ASSUMED", "INFO", "imported positions are taken to be metres, as the operator states; "
                + "their accuracy is not verified"));
        }
        boolean estimable = (anyTracked || anyImported) && wallObservations >= req.minWallObservations();
        if (!measuredOutline && !estimable) {
            issues.add(new Issue("BOUNDS_NEED_EVIDENCE", anyManual ? "BLOCKING" : "WARNING", wallObservations + " wall observation(s): the "
                + "room bounds cannot be estimated until at least " + req.minWallObservations() + " wall points are observed during a "
                + "tracked walk (point the camera at each wall), or a measured outline is entered"));
        }
        return new Assessment(blocking ? "INCOMPLETE" : "READY", basis, metric, s.size(), round(duration), round(length),
            s.isEmpty() ? 0 : s.get(s.size() - 1).segment() + 1, round((double) withYaw / s.size()), losses, round(lost), round(longest),
            tr.frameId(), tr.excludedOtherFrame(), wallObservations, estimable, List.copyOf(issues), req);
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
