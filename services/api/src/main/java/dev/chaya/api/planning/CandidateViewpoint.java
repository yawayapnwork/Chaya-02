package dev.chaya.api.planning;

/** A viewpoint supplied by the caller (for example a spot the operator marked). It is validated like any other. */
public record CandidateViewpoint(double x, double y, String label) {}
