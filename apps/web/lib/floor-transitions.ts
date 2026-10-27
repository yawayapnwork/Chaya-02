// Floors and floor transitions as the clients present and re-check them (docs/navigation.md, "Floors"). Pure.

import type { FloorConnection, RouteResponse } from "./navigation-api.ts";

export interface FloorLike {
  id: string;
  level: number;
  name: string;
  elevation?: { elevationM: number; source: string } | null;
}

/** "First (level 1 · +4.20 m, surveyed)", or "(level 1 · elevation not recorded)": an elevation is shown only when one was
 * recorded, with where it came from; none is ever estimated. */
export function floorLabel(f: FloorLike): string {
  const e = f.elevation;
  if (!e) return `${f.name} (level ${f.level} · elevation not recorded)`;
  const sign = e.elevationM >= 0 ? "+" : "−";
  const how = e.source === "SURVEYED" ? "surveyed" : "measured on site";
  return `${f.name} (level ${f.level} · ${sign}${Math.abs(e.elevationM).toFixed(2)} m, ${how})`;
}

export interface TransitionCheck {
  usable: boolean;
  /** One line per floor transition of the route that can no longer be used as planned. */
  problems: string[];
  /** The connections those problems are about. */
  connectionIds: string[];
}

export const STEP_FREE_MIN_CLEARANCE_M = 0.9;
export const STEP_FREE_MAX_SLOPE_DEG = 4.76;

/**
 * Whether every floor transition a route was planned over can still be used as it was: the connection still exists, is in
 * service, still runs in the route's direction, and -- for a step-free route -- is still verified accessible with passing
 * clearance and slope. A route whose transition fails this is recalculated; it is never followed onto a closed lift.
 */
export function transitionsStillUsable(route: Pick<RouteResponse, "floorTransitions" | "accessibilityProfile">,
                                       connections: readonly FloorConnection[]): TransitionCheck {
  const problems: string[] = [];
  const connectionIds = new Set<string>();
  for (const t of route.floorTransitions) {
    const before = problems.length;
    const c = connections.find((x) => x.id === t.connectionId);
    const what = t.connectorType.toLowerCase();
    if (!c) {
      problems.push(`the ${what} on the route was removed`);
      connectionIds.add(t.connectionId);
      continue;
    }
    if (c.status !== "IN_SERVICE") problems.push(`the ${what} on the route is out of service`);
    const forward = c.fromFloorId === t.fromFloorId && c.toFloorId === t.toFloorId;
    const backward = c.fromFloorId === t.toFloorId && c.toFloorId === t.fromFloorId;
    if (!forward && !(backward && c.bidirectional)) problems.push(`the ${what} on the route no longer runs in this direction`);
    if (route.accessibilityProfile === "STEP_FREE") {
      if (c.connectorType === "STAIRS" || !c.accessibilityVerifiedAt) {
        problems.push(`the ${what} on the route is no longer verified as accessible`);
      } else {
        if (c.minClearanceM === null || c.minClearanceM < STEP_FREE_MIN_CLEARANCE_M) {
          problems.push(`the ${what} on the route does not meet the ${STEP_FREE_MIN_CLEARANCE_M.toFixed(2)} m step-free clearance`);
        }
        if (c.connectorType === "RAMP" && (c.maxSlopeDeg === null || c.maxSlopeDeg > STEP_FREE_MAX_SLOPE_DEG)) {
          problems.push(`the ${what} on the route exceeds the ${STEP_FREE_MAX_SLOPE_DEG}° step-free slope limit`);
        }
      }
    }
    if (problems.length > before) connectionIds.add(t.connectionId);
  }
  return { usable: problems.length === 0, problems, connectionIds: [...connectionIds] };
}

/** The floors a route visits, in order, without repeats in a row. */
export function routeFloors(route: Pick<RouteResponse, "waypoints">): string[] {
  const out: string[] = [];
  for (const w of route.waypoints) if (out[out.length - 1] !== w.floorId) out.push(w.floorId);
  return out;
}
