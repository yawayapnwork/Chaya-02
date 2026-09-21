package dev.chaya.api.planning;

/** One tracked device position of the reconnaissance lap. yawRadians may be null when the heading was not recorded. */
public record TrajectorySample(double t, double x, double y, Double yawRadians) {}
