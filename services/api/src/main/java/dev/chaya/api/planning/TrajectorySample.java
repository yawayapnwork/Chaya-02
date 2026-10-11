package dev.chaya.api.planning;

/**
 * One tracked device position of the reconnaissance lap. yawRadians may be null when the heading was not recorded.
 *
 * @param segment samples with different segment numbers are never joined: a change of segment marks a tracking loss
 *                (or a restarted tracking session) between them, so nothing is interpolated or credited across it
 */
public record TrajectorySample(double t, double x, double y, Double yawRadians, int segment) {

    public TrajectorySample(double t, double x, double y, Double yawRadians) {
        this(t, x, y, yawRadians, 0);
    }
}
