package dev.chaya.api.planning;

import java.util.List;

/**
 * @param priorityRegions areas known to be insufficiently observed; they raise the importance of the cells inside them
 */
public record PlanRequest(Scene scene, List<TrajectorySample> trajectory, List<ObservedRegion> observedRegions,
                          List<CandidateViewpoint> candidates, PlannerConfig config, List<PriorityRegion> priorityRegions) {

    public PlanRequest {
        observedRegions = observedRegions == null ? List.of() : List.copyOf(observedRegions);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        trajectory = trajectory == null ? List.of() : List.copyOf(trajectory);
        config = config == null ? PlannerConfig.defaults() : config;
        priorityRegions = priorityRegions == null ? List.of() : List.copyOf(priorityRegions);
    }

    public PlanRequest(Scene scene, List<TrajectorySample> trajectory, List<ObservedRegion> observedRegions,
                       List<CandidateViewpoint> candidates, PlannerConfig config) {
        this(scene, trajectory, observedRegions, candidates, config, List.of());
    }

    public PlanRequest withTrajectory(List<TrajectorySample> newTrajectory) {
        return new PlanRequest(scene, newTrajectory, observedRegions, candidates, config, priorityRegions);
    }
}
