package dev.chaya.api.planning;

import dev.chaya.api.planning.geometry.Point;
import dev.chaya.api.planning.geometry.Polygon;
import dev.chaya.api.planning.geometry.Segment;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic synthetic geometry used ONLY to test and benchmark the planning algorithm. These are hand-drawn
 * plan views with known answers, not application data and not a simulation of anything the product does.
 * Coordinates are metres. Each recon lap is a ~10 m walk at 1 m/s (about ten seconds), sampled at 2 Hz.
 */
public final class PlannerFixtures {

    private PlannerFixtures() {}

    public static Polygon rect(double x0, double y0, double x1, double y1) {
        return new Polygon(List.of(new Point(x0, y0), new Point(x1, y0), new Point(x1, y1), new Point(x0, y1)));
    }

    public static Segment wall(double x0, double y0, double x1, double y1) {
        return new Segment(new Point(x0, y0), new Point(x1, y1));
    }

    /** Walks the polyline at the given speed, one sample every half second, with headings derived from motion. */
    public static List<TrajectorySample> lap(double speed, double... xy) {
        List<TrajectorySample> out = new ArrayList<>();
        double t = 0;
        out.add(new TrajectorySample(0, xy[0], xy[1], null));
        double carry = 0;
        for (int i = 0; i + 3 < xy.length; i += 2) {
            double ax = xy[i], ay = xy[i + 1], bx = xy[i + 2], by = xy[i + 3];
            double seg = Math.hypot(bx - ax, by - ay);
            double step = speed * 0.5;
            double pos = step - carry;
            while (pos <= seg + 1e-9) {
                double f = pos / seg;
                t += 0.5;
                out.add(new TrajectorySample(t, ax + (bx - ax) * f, ay + (by - ay) * f, null));
                pos += step;
            }
            carry = seg - (pos - step);
        }
        return out;
    }

    public static PlanRequest request(Scene scene, List<TrajectorySample> lap) {
        return new PlanRequest(scene, lap, List.of(), List.of(), PlannerConfig.defaults());
    }

    public static PlanRequest request(Scene scene, List<TrajectorySample> lap, PlannerConfig cfg) {
        return new PlanRequest(scene, lap, List.of(), List.of(), cfg);
    }

    // ---- scenes ---------------------------------------------------------------------------------------------------

    /** 8 x 6 m empty rectangular room; the lap covers the south-east part only. */
    public static PlanRequest rectangularRoom() {
        Scene scene = new Scene(List.of(rect(0, 0, 8, 6)), List.of(), List.of(), List.of(), List.of());
        return request(scene, lap(1.0, 1.5, 1.5, 6.5, 1.5, 6.5, 4.5, 4.5, 4.5));
    }

    /** A 6 x 5 m room with a 1 m doorway in its east wall leading to a 4 x 2 m corridor; the lap stays in the room. */
    public static PlanRequest roomWithDoorway() {
        Scene scene = new Scene(
            List.of(rect(0, 0, 6, 5), rect(6, 1.5, 10, 3.5)),
            List.of(wall(6, 0, 6, 2.0), wall(6, 3.0, 6, 5)),
            List.of(), List.of(),
            List.of(new Scene.Doorway(6, 2.5, 1.0, 0.0)));
        return request(scene, lap(1.0, 1.5, 1.5, 4.0, 1.5, 4.0, 3.5, 1.5, 3.5, 1.5, 4.5));
    }

    /** An 8 x 6 m room with a 1 x 3 m partition in the middle; the lap stays on the west side of it. */
    public static PlanRequest roomWithOcclusion() {
        Scene scene = new Scene(List.of(rect(0, 0, 8, 6)), List.of(), List.of(rect(3.5, 1.5, 4.5, 4.5)), List.of(), List.of());
        return request(scene, lap(1.0, 1.5, 1.0, 2.5, 1.0, 2.5, 5.0, 1.0, 5.0));
    }

    /** Three 4 x 4 m rooms in a row joined by 1 m doorways; the lap stays in the first room. */
    public static PlanRequest multipleRooms() {
        Scene scene = new Scene(
            List.of(rect(0, 0, 4, 4), rect(4, 0, 8, 4), rect(8, 0, 12, 4)),
            List.of(wall(4, 0, 4, 1.5), wall(4, 2.5, 4, 4), wall(8, 0, 8, 1.5), wall(8, 2.5, 8, 4)),
            List.of(), List.of(),
            List.of(new Scene.Doorway(4, 2.0, 1.0, 0.0), new Scene.Doorway(8, 2.0, 1.0, 0.0)));
        return request(scene, lap(1.0, 1.0, 1.0, 3.0, 1.0, 3.0, 3.0, 1.0, 3.0, 1.0, 1.5));
    }

    /**
     * A 10 x 6 m room with a central obstacle and a no-go zone, next to a sealed 3 x 3 m room that has no doorway:
     * its floor can never be reached on foot.
     */
    public static PlanRequest roomWithSealedNeighbour() {
        Scene scene = new Scene(
            List.of(rect(0, 0, 10, 6), rect(10, 0, 13, 3)),
            List.of(wall(10, 0, 10, 3)), // solid wall, no doorway
            List.of(rect(4, 2, 6, 4)),
            List.of(rect(0.5, 4.5, 2, 5.5)),
            List.of());
        return request(scene, lap(1.0, 1.5, 1.5, 8.5, 1.5, 8.5, 4.5, 7.0, 4.5));
    }
}
