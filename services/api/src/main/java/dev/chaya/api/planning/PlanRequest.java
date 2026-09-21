package dev.chaya.api.planning;

import java.util.List;

public record PlanRequest(Scene scene, List<TrajectorySample> trajectory, List<ObservedRegion> observedRegions,
                          List<CandidateViewpoint> candidates, PlannerConfig config) {

    public PlanRequest {
        observedRegions = observedRegions == null ? List.of() : List.copyOf(observedRegions);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        trajectory = trajectory == null ? List.of() : List.copyOf(trajectory);
        config = config == null ? PlannerConfig.defaults() : config;
    }
}
