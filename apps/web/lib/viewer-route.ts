// How the viewer presents a route from POST /api/v1/navigation/routes. Pure, so it is unit-tested.
//
// The route is drawn on the splat only where it is provably in the splat's frame: the waypoints of the floor on screen,
// and only when the API says that floor's leg was routed in the same canonical frame the reconstruction is placed with
// (RoutingSource.coordinateFrameId). Distance and duration are shown only as the API reported them, and only when they
// describe a real path. A failure is reported by its API code, never papered over with a straight line.

import { routeLegOnFloor } from "./ar-route.ts";
import type { RouteResponse, RouteWaypoint } from "./navigation-api.ts";

export type RouteProfile = "STANDARD" | "STEP_FREE";

/** What can be drawn of a route on one floor's reconstruction. */
export interface RouteOverlay {
  /** This floor's leg, in order; null when nothing may be drawn on this reconstruction. */
  waypoints: RouteWaypoint[] | null;
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
  if (leg.length === 0 || !scene.frameId) return { waypoints: null, mismatch: null, otherFloorIds };
  // An API that names what the leg was routed on must name this scene's version and frame (as lib/ar-navigation's
  // routeLegFor requires of an AR session). A response without routingSources predates them; the service then routed
  // in the floor's current frame, which is also what a non-version reconstruction is shown in.
  const source = route.routingSources?.find((s) => s.floorId === scene.floorId);
  let mismatch: RouteOverlay["mismatch"] = null;
  if (source && scene.scanVersionId && source.scanVersionId && source.scanVersionId !== scene.scanVersionId) mismatch = "VERSION_MISMATCH";
  else if (source?.coordinateFrameId && source.coordinateFrameId !== scene.frameId) mismatch = "FRAME_MISMATCH";
  return { waypoints: !mismatch && leg.length >= 2 ? leg : null, mismatch, otherFloorIds };
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

export type RouteFailureKind = "unreachable" | "no-accessible-route" | "no-floor-connection" | "not-routable" | "other";

export interface RouteFailure {
  kind: RouteFailureKind;
  message: string;
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
    case "METRIC_CALIBRATION_REQUIRED":
      return { kind: "not-routable", message: "The start or destination floor is not calibrated in metres, so no route can be planned." };
    case "VERSION_MISMATCH":
      return { kind: "not-routable", message: "The destination belongs to a different scan version than the one being routed on." };
    default:
      return { kind: "other", message: detail };
  }
}
