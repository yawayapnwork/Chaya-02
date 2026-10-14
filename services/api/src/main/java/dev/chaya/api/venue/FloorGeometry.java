package dev.chaya.api.venue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Consistency rules between what staff record about floors and connections (relative elevation, a connector's walked
 * length and slope) and between those records and geometry measured in a shared survey datum. Pure, so every rule is
 * unit-tested (FloorGeometryTest). Nothing here estimates or fills in a value: it only compares values that exist.
 *
 * <p>Tolerances: a recorded elevation and a stair's or ramp's registered length are on-site measurements; the comparison
 * allows {@link #LENGTH_TOLERANCE_M} for tape error. Landings compared in a shared datum allow
 * {@link #ELEVATION_TOLERANCE_M} vertically (a landing POI is placed on the floor surface by hand) and
 * {@link #ELEVATOR_ALIGNMENT_M} horizontally between an elevator's two landings (a car moves vertically; its two landing
 * POIs are each placed at the door, which may differ by about the car's depth).
 */
public final class FloorGeometry {

    public static final double LENGTH_TOLERANCE_M = 0.10;
    public static final double SLOPE_TOLERANCE_DEG = 0.5;
    public static final double ELEVATION_TOLERANCE_M = 0.30;
    public static final double ELEVATOR_ALIGNMENT_M = 2.5;
    /** Steeper than this is a ladder, not stairs: recorded as a warning, not refused. */
    public static final double MAX_PLAUSIBLE_STAIR_DEG = 50;

    private FloorGeometry() {}

    public enum Severity { OK, WARNING, ERROR }

    public record Finding(String code, Severity severity, String detail) {}

    /** A floor as the rules see it. elevationM null: not recorded. */
    public record FloorFacts(String id, int level, Double elevationM) {}

    /** Higher levels must be higher up: for every pair of floors with recorded elevations, the order of their levels and
     * of their elevations agree (strictly). */
    public static List<Finding> elevationOrder(List<FloorFacts> floors) {
        List<Finding> out = new ArrayList<>();
        List<FloorFacts> known = floors.stream().filter(f -> f.elevationM() != null).toList();
        for (int i = 0; i < known.size(); i++) {
            for (int j = 0; j < known.size(); j++) {
                FloorFacts a = known.get(i);
                FloorFacts b = known.get(j);
                if (a.level() < b.level() && a.elevationM() >= b.elevationM()) {
                    out.add(new Finding("FLOOR_ELEVATION_INCONSISTENT", Severity.ERROR, String.format(Locale.ROOT,
                        "level %d is recorded at %.2f m, not below level %d at %.2f m", a.level(), a.elevationM(), b.level(),
                        b.elevationM())));
                }
            }
        }
        return out;
    }

    /**
     * A walked connector against the recorded elevations of its floors: the rise cannot exceed the walked length, and a
     * ramp's registered slope must be at least the slope its rise and length imply. Elevators, and floors without a
     * recorded elevation, have nothing to check.
     */
    public static List<Finding> connector(String type, Double lengthM, Double maxSlopeDeg, Double fromElevationM, Double toElevationM) {
        List<Finding> out = new ArrayList<>();
        if (fromElevationM == null || toElevationM == null || lengthM == null || "ELEVATOR".equals(type)) {
            return out;
        }
        double rise = Math.abs(toElevationM - fromElevationM);
        if (rise > lengthM + LENGTH_TOLERANCE_M) {
            out.add(new Finding("CONNECTOR_SHORTER_THAN_RISE", Severity.ERROR, String.format(Locale.ROOT,
                "the floors are recorded %.2f m apart vertically, but the %s's walked length is only %.2f m", rise,
                type.toLowerCase(Locale.ROOT), lengthM)));
            return out;
        }
        double implied = Math.toDegrees(Math.asin(Math.min(1.0, rise / lengthM)));
        if ("RAMP".equals(type) && maxSlopeDeg != null && maxSlopeDeg + SLOPE_TOLERANCE_DEG < implied) {
            out.add(new Finding("RAMP_SLOPE_INCONSISTENT", Severity.ERROR, String.format(Locale.ROOT,
                "a %.2f m ramp rising %.2f m averages %.1f degrees, steeper than its registered maximum of %.1f degrees",
                lengthM, rise, implied, maxSlopeDeg)));
        }
        if ("STAIRS".equals(type) && implied > MAX_PLAUSIBLE_STAIR_DEG) {
            out.add(new Finding("STAIR_GEOMETRY_IMPLAUSIBLE", Severity.WARNING, String.format(Locale.ROOT,
                "a %.2f m flight rising %.2f m would be %.1f degrees steep; check the walked length and the elevations",
                lengthM, rise, implied)));
        }
        return out;
    }

    /**
     * A connector's two landing POIs, each in its floor's current frame, when both frames are calibrated to the same
     * surveyed venue datum (VENUE_CONTROL_POINTS): then their coordinates are directly comparable.
     * <ul>
     *   <li>the vertical separation of the landings against the recorded elevations;</li>
     *   <li>an elevator's landings are vertically above each other;</li>
     *   <li>a walked connector's landings are no farther apart in a straight line than its walked length.</li>
     * </ul>
     * The measured vertical separation is reported (OK) either way, as evidence; it is never stored as an elevation.
     */
    public static List<Finding> landingsInSharedDatum(String type, Double lengthM, double[] from, double[] to,
                                                      Double fromElevationM, Double toElevationM) {
        List<Finding> out = new ArrayList<>();
        double dz = to[2] - from[2];
        double dxy = Math.hypot(to[0] - from[0], to[1] - from[1]);
        out.add(new Finding("LANDINGS_MEASURED", Severity.OK, String.format(Locale.ROOT,
            "in the shared survey datum the landings are %.2f m apart horizontally and %.2f m vertically", dxy, dz)));
        if (fromElevationM != null && toElevationM != null) {
            double recorded = toElevationM - fromElevationM;
            if (Math.abs(dz - recorded) > ELEVATION_TOLERANCE_M) {
                out.add(new Finding("ELEVATION_DISAGREES_WITH_SURVEY", Severity.ERROR, String.format(Locale.ROOT,
                    "the floors' recorded elevations differ by %.2f m, but their surveyed frames put the landings %.2f m apart",
                    recorded, dz)));
            }
        }
        if ("ELEVATOR".equals(type) && dxy > ELEVATOR_ALIGNMENT_M) {
            out.add(new Finding("LANDINGS_NOT_VERTICALLY_ALIGNED", Severity.ERROR, String.format(Locale.ROOT,
                "an elevator's landings are %.2f m apart horizontally in the shared datum (more than %.1f m): one floor's "
                    + "calibration, or a landing's placement, is wrong", dxy, ELEVATOR_ALIGNMENT_M)));
        }
        if (!"ELEVATOR".equals(type) && lengthM != null && Math.hypot(dxy, dz) > lengthM + ELEVATION_TOLERANCE_M) {
            out.add(new Finding("LANDINGS_FARTHER_THAN_WALKED_LENGTH", Severity.ERROR, String.format(Locale.ROOT,
                "the landings are %.2f m apart in a straight line, more than the registered walked length of %.2f m",
                Math.hypot(dxy, dz), lengthM)));
        }
        return out;
    }

    public static boolean hasErrors(List<Finding> findings) {
        return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
    }

    public static String describe(List<Finding> findings) {
        return String.join("; ", findings.stream().filter(f -> f.severity() == Severity.ERROR).map(Finding::detail).toList());
    }
}
