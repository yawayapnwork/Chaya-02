package dev.chaya.api.planning.api;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.capture.CaptureService;
import dev.chaya.api.planning.CapturePathPlanner;
import dev.chaya.api.planning.PlanRequest;
import dev.chaya.api.planning.PlanResult;
import dev.chaya.api.planning.PlanningException;
import dev.chaya.api.planning.api.RoutePlanDtos.RoutePlanRequest;
import dev.chaya.api.security.Actor;
import dev.chaya.api.web.ApiException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the deterministic capture-path planner for a capture that is still being captured. The planner is CPU-bound
 * and finishes in well under a second for a room-sized scene; concurrency is capped so it cannot starve the API.
 */
@Service
public class RoutePlanService {

    private static final int MAX_CONCURRENT_PLANS = 4;

    private final CaptureService captures;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final CapturePathPlanner planner = new CapturePathPlanner();
    private final Semaphore permits = new Semaphore(MAX_CONCURRENT_PLANS);

    public RoutePlanService(CaptureService captures, AuditService audit, TransactionTemplate tx) {
        this.captures = captures;
        this.audit = audit;
        this.tx = tx;
    }

    public PlanResult plan(Actor actor, UUID venueId, UUID captureId, RoutePlanRequest body) {
        var capture = captures.get(actor, venueId, captureId); // venue-guarded; 404 for anything not visible to the caller
        if (!capture.status().acceptsMedia()) {
            throw new ApiException(HttpStatus.CONFLICT, "CAPTURE_NOT_PLANNABLE",
                "capture is " + capture.status() + "; a route can only be planned while it is CREATED or UPLOADING");
        }
        PlanResult result;
        if (!permits.tryAcquire()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLANNER_BUSY", "the route planner is busy; retry in a moment");
        }
        try {
            PlanRequest request = RoutePlanDtos.toDomain(body);
            result = planner.plan(request);
        } catch (PlanningException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.getMessage());
        } finally {
            permits.release();
        }
        PlanResult finalResult = result;
        tx.executeWithoutResult(s -> audit.success(actor, venueId, "capture.route_plan", "capture_session", captureId, Map.of(
            "waypoints", finalResult.waypoints().size(),
            "baselineCoveragePercent", finalResult.baseline().coveragePercent(),
            "plannedCoveragePercent", finalResult.planned().coveragePercent(),
            "routeMeters", finalResult.estimatedDistanceMeters())));
        return result;
    }
}
