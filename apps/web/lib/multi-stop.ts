// A route through several destinations, planned segment by segment so that changing one destination recomputes only the
// segments it touches (docs/navigation.md, "Multi-stop"). Pure apart from the injected fetch, so it is unit-tested.
//
// The rules mirror RouteService#routeMultiStop exactly: stops are visited in the order given; a stop that cannot be
// reached is reported with the server's failure code and skipped, and the next segment starts from the last stop that
// was reached. Each segment is one POST /navigation/routes from the previous point to the next stop.
//
// Segment results are cached under a key naming everything a segment depends on: the scene (floor + scan version), the
// accessibility profile, the obstacles reported, where it starts and which stop it ends at. Changing a stop only changes
// the keys of the segments into and out of it, so every other segment is reused. A cached segment is never mixed with a
// segment routed on another graph of the same floor: if their routingSources disagree (the floor was republished
// meanwhile), the cache is dropped and the whole route is planned again.

import type { RouteResponse, RouteWaypoint, RoutingSource, FloorTransition } from "./navigation-api.ts";

/** A point a segment can start from: the route's start, or a stop that was reached. */
export interface SegmentPoint {
  key: string;
  floorId: string;
  position: [number, number, number];
}

export interface StopRef {
  id: string;
  floorId: string;
  position: [number, number, number];
}

export type SegmentOutcome = { ok: true; route: RouteResponse } | { ok: false; code: string; message: string; retryable: boolean };

export interface PlannedStop {
  id: string;
  status: "REACHED" | "UNREACHABLE";
  code: string | null;
  message: string | null;
}

export interface MultiStopPlan {
  stops: PlannedStop[];
  /** Reached stops' segments, in route order. */
  segments: { from: string; to: string; route: RouteResponse }[];
  /** The segments joined into one continuous route (null when no stop was reached). */
  route: RouteResponse | null;
  /** How many segments were requested from the server for this plan (the rest came from the cache). */
  fetched: number;
  /** A failure that is not about a stop (an expired session, the network): the plan is incomplete and must be retried. */
  interrupted: { code: string; message: string } | null;
}

export function segmentKey(scope: string, profile: string, obstaclesKey: string, from: string, to: string): string {
  return [scope, profile, obstaclesKey, from, to].join("|");
}

/** Failure codes that describe the network or the session, not whether a stop can be reached. */
const INTERRUPTING = new Set(["SESSION_EXPIRED", "NETWORK_UNAVAILABLE"]);

export async function planStops(args: {
  scope: string;
  profile: string;
  obstaclesKey?: string;
  start: SegmentPoint;
  stops: StopRef[];
  cache: Map<string, SegmentOutcome>;
  fetchSegment: (from: SegmentPoint, to: StopRef) => Promise<SegmentOutcome>;
}): Promise<MultiStopPlan> {
  const obstaclesKey = args.obstaclesKey ?? "";
  for (let attempt = 0; attempt < 2; attempt++) {
    let fetched = 0;
    let here = args.start;
    const stops: PlannedStop[] = [];
    const segments: MultiStopPlan["segments"] = [];
    let interrupted: MultiStopPlan["interrupted"] = null;
    for (const stop of args.stops) {
      const key = segmentKey(args.scope, args.profile, obstaclesKey, here.key, stop.id);
      let outcome = args.cache.get(key);
      if (!outcome) {
        outcome = await args.fetchSegment(here, stop);
        fetched++;
        if (outcome.ok || (!outcome.retryable && !INTERRUPTING.has(outcome.code))) args.cache.set(key, outcome);
      }
      if (outcome.ok) {
        stops.push({ id: stop.id, status: "REACHED", code: null, message: null });
        segments.push({ from: here.key, to: stop.id, route: outcome.route });
        here = { key: `POI:${stop.id}`, floorId: stop.floorId, position: stop.position };
      } else if (INTERRUPTING.has(outcome.code) || outcome.retryable) {
        interrupted = { code: outcome.code, message: outcome.message };
        stops.push({ id: stop.id, status: "UNREACHABLE", code: outcome.code, message: outcome.message });
        break;
      } else {
        stops.push({ id: stop.id, status: "UNREACHABLE", code: outcome.code, message: outcome.message });
      }
    }
    if (interrupted) {
      // Stops after the interruption were not tried: say so rather than calling them unreachable.
      for (const stop of args.stops.slice(stops.length)) stops.push({ id: stop.id, status: "UNREACHABLE", code: "NOT_TRIED", message: null });
    }
    const routes = segments.map((s) => s.route);
    if (attempt === 0 && !consistentSources(routes)) {
      args.cache.clear(); // a floor was republished between segments: plan everything on what it publishes now
      continue;
    }
    return { stops, segments, route: routes.length ? joinSegments(routes) : null, fetched, interrupted };
  }
  throw new Error("unreachable: a fresh plan is always consistent or returned");
}

/** Whether every segment routed each floor on the same graph, scan version and frame. */
export function consistentSources(routes: RouteResponse[]): boolean {
  const byFloor = new Map<string, RoutingSource>();
  for (const r of routes) {
    for (const s of r.routingSources ?? []) {
      const seen = byFloor.get(s.floorId);
      if (seen && (seen.graphId !== s.graphId || seen.scanVersionId !== s.scanVersionId || seen.coordinateFrameId !== s.coordinateFrameId)) {
        return false;
      }
      byFloor.set(s.floorId, s);
    }
  }
  return true;
}

/** Segments joined as RouteService#routeMultiStop joins them: one polyline, each intermediate destination a STOP, the
 * point shared by consecutive segments listed once; distances and durations summed; transitions and sources kept. */
export function joinSegments(routes: RouteResponse[]): RouteResponse {
  const waypoints: RouteWaypoint[] = [];
  const transitions: FloorTransition[] = [];
  const sources: RoutingSource[] = [];
  const constraints: string[] = [];
  routes.forEach((r, s) => {
    const last = s === routes.length - 1;
    r.waypoints.forEach((w, i) => {
      if (s > 0 && i === 0) return;
      waypoints.push(i === r.waypoints.length - 1 && !last ? { ...w, kind: "STOP" } : w);
    });
    transitions.push(...r.floorTransitions);
    for (const src of r.routingSources ?? []) {
      if (!sources.some((x) => JSON.stringify(x) === JSON.stringify(src))) sources.push(src);
    }
    for (const c of r.accessibilityConstraintsApplied) if (!constraints.includes(c)) constraints.push(c);
  });
  return {
    waypoints,
    distanceMeters: routes.reduce((t, r) => t + r.distanceMeters, 0),
    estimatedDurationSeconds: routes.reduce((t, r) => t + r.estimatedDurationSeconds, 0),
    floorTransitions: transitions,
    accessibilityProfile: routes[0].accessibilityProfile,
    accessibilityConstraintsApplied: constraints,
    routingSources: sources,
    rejectedObstacles: routes.flatMap((r) => r.rejectedObstacles ?? []),
  };
}

/** Moves the stop at `index` by `delta` places (the user reordering destinations). */
export function moveStop<T>(stops: readonly T[], index: number, delta: number): T[] {
  const to = index + delta;
  if (index < 0 || index >= stops.length || to < 0 || to >= stops.length) return [...stops];
  const out = [...stops];
  const [item] = out.splice(index, 1);
  out.splice(to, 0, item);
  return out;
}
