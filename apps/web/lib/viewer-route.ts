// How the viewer presents a route from POST /api/v1/navigation/routes. Pure, so it is unit-tested.
//
// The route is drawn on the splat only where it is provably in the splat's frame: the waypoints of the floor on screen,
// and only when the API says that floor's leg was routed in the same canonical frame the reconstruction is placed with
// (RoutingSource.coordinateFrameId). Distance and duration are shown only as the API reported them, and only when they
// describe a real path. A failure is reported by its API code, never papered over with a straight line.

import { classifyArFailure } from "./ar-errors.ts";
import { routeLegOnFloor } from "./ar-route.ts";
import type { RouteResponse, RouteWaypoint } from "./navigation-api.ts";

export type RouteProfile = "STANDARD" | "STEP_FREE";

/** What can be drawn of a route on one floor's reconstruction. */
export interface RouteOverlay {
  /** This floor's leg, in order; null when nothing may be drawn on this reconstruction. */
  waypoints: RouteWaypoint[] | null;
  /** Every stretch of the route on this floor, in order (a multi-stop route can leave the floor and come back); empty
   * when nothing may be drawn. */
  runs: RouteWaypoint[][];
  /** The floor's leg was routed on another scan version or in another frame than the reconstruction is shown in. */
  mismatch: "VERSION_MISMATCH" | "FRAME_MISMATCH" | null;
  /** Floors other than this one the route passes through, in route order. */
  otherFloorIds: string[];
}

/** The scene on screen: its floor, the canonical frame its splat is placed with (null: not calibrated), and the
 * finalized scan version it is (null: a reconstruction that is not one). */
export interface RouteScene {
  floorId: string;
  frameId: string | null;
  scanVersionId: string | null;
}

export function routeOverlay(route: RouteResponse, scene: RouteScene): RouteOverlay {
  const otherFloorIds: string[] = [];
  for (const w of route.waypoints) if (w.floorId !== scene.floorId && !otherFloorIds.includes(w.floorId)) otherFloorIds.push(w.floorId);
  const leg = routeLegOnFloor(route, scene.floorId).waypoints;
  // Without a frame the reconstruction is not in canonical metres: there is nothing to draw a canonical route on.
  if (leg.length === 0 || !scene.frameId) return { waypoints: null, runs: [], mismatch: null, otherFloorIds };
  // An API that names what the leg was routed on must name this scene's version and frame (as lib/ar-navigation's
  // routeLegFor requires of an AR session). A response without routingSources predates them; the service then routed
  // in the floor's current frame, which is also what a non-version reconstruction is shown in.
  const source = route.routingSources?.find((s) => s.floorId === scene.floorId);
  let mismatch: RouteOverlay["mismatch"] = null;
  if (source && scene.scanVersionId && source.scanVersionId && source.scanVersionId !== scene.scanVersionId) mismatch = "VERSION_MISMATCH";
  else if (source?.coordinateFrameId && source.coordinateFrameId !== scene.frameId) mismatch = "FRAME_MISMATCH";
  return { waypoints: !mismatch && leg.length >= 2 ? leg : null, runs: mismatch ? [] : runsOnFloor(route, scene.floorId), mismatch, otherFloorIds };
}

/** Every maximal run of consecutive waypoints on `floorId` with at least two points. */
export function runsOnFloor(route: Pick<RouteResponse, "waypoints">, floorId: string): RouteWaypoint[][] {
  const runs: RouteWaypoint[][] = [];
  let run: RouteWaypoint[] = [];
  for (const w of route.waypoints) {
    if (w.floorId === floorId) run.push(w);
    else {
      if (run.length >= 2) runs.push(run);
      run = [];
    }
  }
  if (run.length >= 2) runs.push(run);
  return runs;
}

export interface RouteSummary {
  /** Walked length in metres, when the route has a real path (at least two waypoints) and the API reported a finite one. */
  distanceMeters: number | null;
  /** The API's estimate (its assumed walking speed plus connector times), when finite. */
  durationSeconds: number | null;
}

export function routeSummary(route: RouteResponse): RouteSummary {
  const hasPath = route.waypoints.length >= 2;
  const ok = (v: number) => hasPath && Number.isFinite(v) && v >= 0;
  return {
    distanceMeters: ok(route.distanceMeters) ? route.distanceMeters : null,
    durationSeconds: ok(route.estimatedDurationSeconds) ? route.estimatedDurationSeconds : null,
  };
}

export function formatDistance(m: number): string {
  return m < 1000 ? `${m.toFixed(m < 10 ? 1 : 0)} m` : `${(m / 1000).toFixed(2)} km`;
}

export function formatDuration(s: number): string {
  if (s < 60) return `${Math.max(1, Math.round(s))} s`;
  return `${Math.round(s / 60)} min`;
}

export type RouteFailureKind =
  | "unreachable" | "no-accessible-route" | "no-floor-connection" | "not-routable" | "bad-endpoint" | "session-expired" | "network"
  | "other";

export interface RouteFailure {
  kind: RouteFailureKind;
  message: string;
  /** Whether asking again later can succeed without the user changing anything (a network failure). */
  retryable?: boolean;
}

/** A failed route request as what the visitor is told: an expired sign-in or viewer link and a lost connection first
 * (lib/ar-errors decides those from the error's shape), then the API's own failure code. */
export function classifyRouteError(error: unknown, profile: RouteProfile, online = true): RouteFailure & { code: string; detail: string } {
  const c = classifyArFailure(error, online);
  if (c.failure === "SESSION_EXPIRED") {
    return { kind: "session-expired", code: "SESSION_EXPIRED", detail: c.detail, retryable: false,
      message: "Your sign-in or viewer link has expired. Sign in again (or reopen the link) to plan routes." };
  }
  if (c.failure === "NETWORK_UNAVAILABLE") {
    return { kind: "network", code: "NETWORK_UNAVAILABLE", detail: c.detail, retryable: true,
      message: "The route could not be requested: no connection to the server. Retry when you are back online." };
  }
  const status = typeof error === "object" && error !== null && "status" in error ? Number((error as { status: unknown }).status) : NaN;
  return { ...describeRouteFailure(c.code, c.detail, profile), code: c.code, detail: c.detail, retryable: status >= 500 };
}

/** An API error code (RouteService's failure states, docs/navigation.md) as what the visitor is told. */
export function describeRouteFailure(code: string, detail: string, profile: RouteProfile): RouteFailure {
  switch (code) {
    case "NO_ROUTE":
      return { kind: "unreachable", message: "The destination cannot be reached from the start: the walkable area does not connect them." };
    case "NO_ACCESSIBLE_ROUTE":
      return { kind: "no-accessible-route", message: "No step-free route exists: every path includes a slope or clearance that is not measured as accessible." };
    case "FLOOR_CONNECTION_UNAVAILABLE":
      return {
        kind: "no-floor-connection",
        message: profile === "STEP_FREE"
          ? "The destination is on another floor and no step-free connection (lift or ramp) joins the floors."
          : "The destination is on another floor and no registered stairs, lift or ramp joins the floors.",
      };
    case "NAVMESH_NOT_READY":
      return { kind: "not-routable", message: "This floor has no navigation mesh for this route yet, so no route can be planned." };
    case "NOT_CALIBRATED":
    case "METRIC_CALIBRATION_REQUIRED":
      return { kind: "not-routable", message: "The start or destination floor is not calibrated in metres, so no route can be planned." };
    case "METRIC_EVIDENCE_INSUFFICIENT":
      return { kind: "not-routable", message: "The floor was calibrated from hand-entered points rather than measured observations; metric routing cannot be advertised until calibrated from marked observations." };
    case "VERSION_MISMATCH":
      return { kind: "not-routable", message: "The destination belongs to a different scan version than the one being routed on." };
    case "START_OUT_OF_BOUNDS":
    case "START_OFF_NAVMESH":
      return { kind: "bad-endpoint", message: "The starting point is not on the walkable area mapped for this floor." };
    case "DESTINATION_OUT_OF_BOUNDS":
    case "DESTINATION_OFF_NAVMESH":
      return { kind: "bad-endpoint", message: "The destination is not on the walkable area mapped for its floor." };
    case "NO_REACHABLE_STOP":
      return { kind: "unreachable", message: "None of the selected destinations can be reached from the start." };
    case "POI_NOT_FOUND":
      return { kind: "unreachable", message: "This destination no longer exists." };
    default:
      return { kind: "other", message: detail };
  }
}
