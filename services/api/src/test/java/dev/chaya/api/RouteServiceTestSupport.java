package dev.chaya.api;

import dev.chaya.api.navigation.NavigationDtos.Waypoint;
import java.util.ArrayList;
import java.util.List;

/** Geometry helpers shared by the routing tests. */
final class RouteServiceTestSupport {

    private RouteServiceTestSupport() {}

    /** The 3-D length of a waypoint polyline, metres. */
    static double polyline(List<Waypoint> waypoints) {
        double total = 0;
        for (int i = 1; i < waypoints.size(); i++) {
            Waypoint a = waypoints.get(i - 1);
            Waypoint b = waypoints.get(i);
            total += Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
        }
        return total;
    }

    /** Points every 1/200 of each segment of the polyline, in plan view (x, y). */
    static List<double[]> samples(List<Waypoint> waypoints) {
        List<double[]> out = new ArrayList<>();
        for (int i = 1; i < waypoints.size(); i++) {
            Waypoint a = waypoints.get(i - 1);
            Waypoint b = waypoints.get(i);
            if (!a.floorId().equals(b.floorId())) {
                continue; // a floor connection, not walked geometry on either floor
            }
            for (int k = 0; k <= 200; k++) {
                double t = k / 200.0;
                out.add(new double[]{a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t});
            }
        }
        return out;
    }
}
