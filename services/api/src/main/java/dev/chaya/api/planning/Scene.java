package dev.chaya.api.planning;

import dev.chaya.api.planning.geometry.Polygon;
import dev.chaya.api.planning.geometry.Segment;
import java.util.List;

/**
 * The estimated geometry of the scan area, plan view.
 *
 * @param areas     estimated room bounds; their union is the floor to be scanned. Cells outside are solid boundary
 * @param walls     interior partitions (block walking and sight). A doorway is simply a gap between two wall segments
 * @param obstacles furniture and columns: block walking and sight
 * @param noGoZones regions the operator must not stand in (stairs, restricted areas); they do not block sight
 * @param doorways  optional doorway annotations; they raise the importance of the cells around them
 */
public record Scene(List<Polygon> areas, List<Segment> walls, List<Polygon> obstacles, List<Polygon> noGoZones,
                    List<Doorway> doorways) {

    public Scene {
        areas = List.copyOf(areas);
        walls = walls == null ? List.of() : List.copyOf(walls);
        obstacles = obstacles == null ? List.of() : List.copyOf(obstacles);
        noGoZones = noGoZones == null ? List.of() : List.copyOf(noGoZones);
        doorways = doorways == null ? List.of() : List.copyOf(doorways);
    }

    /** @param passageRadians direction of travel through the doorway, or null if unknown */
    public record Doorway(double x, double y, double width, Double passageRadians) {}
}
