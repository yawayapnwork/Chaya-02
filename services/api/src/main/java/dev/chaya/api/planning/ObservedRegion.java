package dev.chaya.api.planning;

import dev.chaya.api.planning.geometry.Polygon;

/**
 * Coverage the device already reports (for example from its depth or mesh tracking): every target cell inside
 * the polygon is credited "quality" (0..1) of the observation needed, with sufficient view diversity assumed.
 */
public record ObservedRegion(Polygon polygon, double quality) {}
