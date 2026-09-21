package dev.chaya.api.planning;

import dev.chaya.api.planning.geometry.Geometry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Step 1: turns a raw tracked path into a clean, evenly spaced sequence of poses.
 *
 * <ol>
 *   <li>drop samples with non-finite values;</li>
 *   <li>order by time (stable) and drop samples whose time does not advance;</li>
 *   <li>drop tracking glitches: samples that imply a speed above the configured maximum;</li>
 *   <li>drop samples closer than half a pose spacing to the previous kept one (stationary jitter);</li>
 *   <li>resample by arc length at exactly poseSpacing with linear interpolation;</li>
 *   <li>headings: if every kept sample has one, interpolate along the shortest arc; otherwise use the direction of travel.</li>
 * </ol>
 */
public final class TrajectoryNormalizer {

    public record Pose(double x, double y, double yaw) {}

    public record Result(List<Pose> poses, int inputSamples, int droppedNonFinite, int droppedOutOfOrder, int droppedSpeedSpikes,
                         int droppedStationary, double lengthMeters, String yawSource) {}

    private TrajectoryNormalizer() {}

    public static Result normalize(List<TrajectorySample> input, PlannerConfig cfg) {
        int nonFinite = 0;
        int outOfOrder = 0;
        int spikes = 0;
        int stationary = 0;
        List<TrajectorySample> finite = new ArrayList<>();
        for (TrajectorySample s : input) {
            boolean ok = Double.isFinite(s.t()) && Double.isFinite(s.x()) && Double.isFinite(s.y())
                && (s.yawRadians() == null || Double.isFinite(s.yawRadians()));
            if (ok) {
                finite.add(s);
            } else {
                nonFinite++;
            }
        }
        finite.sort(Comparator.comparingDouble(TrajectorySample::t)); // stable: equal times keep input order

        List<TrajectorySample> kept = new ArrayList<>();
        for (TrajectorySample s : finite) {
            if (kept.isEmpty()) {
                kept.add(s);
                continue;
            }
            TrajectorySample prev = kept.get(kept.size() - 1);
            double dt = s.t() - prev.t();
            double dist = Math.hypot(s.x() - prev.x(), s.y() - prev.y());
            if (dt <= 0) {
                outOfOrder++;
            } else if (dist / dt > cfg.maxSpeedMetersPerSecond()) {
                spikes++;
            } else if (dist < cfg.poseSpacing() / 2) {
                stationary++;
            } else {
                kept.add(s);
            }
        }

        boolean observedYaw = !kept.isEmpty() && kept.stream().allMatch(s -> s.yawRadians() != null);
        List<Pose> poses = new ArrayList<>();
        double length = 0;
        if (kept.size() == 1) {
            TrajectorySample s = kept.get(0);
            poses.add(new Pose(s.x(), s.y(), observedYaw ? s.yawRadians() : 0));
        } else if (kept.size() > 1) {
            // Unwrap headings so interpolation follows the shortest arc.
            double[] yaw = new double[kept.size()];
            for (int i = 0; i < kept.size(); i++) {
                if (observedYaw) {
                    double raw = kept.get(i).yawRadians();
                    yaw[i] = i == 0 ? raw : yaw[i - 1] + Geometry.wrapAngle(raw - yaw[i - 1]);
                }
            }
            double carry = 0; // distance already travelled since the last emitted pose
            poses.add(pose(kept.get(0), kept.get(1), 0, observedYaw, yaw, 0));
            for (int i = 0; i + 1 < kept.size(); i++) {
                TrajectorySample a = kept.get(i);
                TrajectorySample b = kept.get(i + 1);
                double seg = Math.hypot(b.x() - a.x(), b.y() - a.y());
                length += seg;
                double pos = cfg.poseSpacing() - carry;
                while (pos <= seg + 1e-9) {
                    poses.add(pose(a, b, seg == 0 ? 0 : pos / seg, observedYaw, yaw, i));
                    pos += cfg.poseSpacing();
                }
                carry = seg - (pos - cfg.poseSpacing());
            }
            TrajectorySample last = kept.get(kept.size() - 1);
            Pose tail = poses.get(poses.size() - 1);
            if (Math.hypot(last.x() - tail.x(), last.y() - tail.y()) > 1e-6) { // always end exactly where the lap ended
                poses.add(new Pose(last.x(), last.y(), observedYaw ? Geometry.wrapAngle(yaw[yaw.length - 1]) : tail.yaw()));
            }
        }
        return new Result(List.copyOf(poses), input.size(), nonFinite, outOfOrder, spikes, stationary, length,
            observedYaw ? "observed" : "derived-from-motion");
    }

    private static Pose pose(TrajectorySample a, TrajectorySample b, double f, boolean observedYaw, double[] yaw, int i) {
        double x = a.x() + (b.x() - a.x()) * f;
        double y = a.y() + (b.y() - a.y()) * f;
        double heading = observedYaw
            ? Geometry.wrapAngle(yaw[i] + (yaw[i + 1] - yaw[i]) * f)
            : Math.atan2(b.y() - a.y(), b.x() - a.x());
        return new Pose(x, y, heading);
    }
}
