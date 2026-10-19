// When an obstacle the device observed should change the route (docs/navigation.md, "Dynamic obstacles"). Pure.
//
// The server routes around obstacles a client reports (blockedRegions), for that request only. This module decides
// what to report and when to ask again:
//   - only observations from a real source on the device are ever reported; nothing here invents one. A client without
//     an obstacle source reports none (obstacleSourceStatus says so, so the UI never claims live detection);
//   - an observation older than OBSTACLE_MAX_AGE_MS is dropped (the server rejects it too);
//   - a reroute is asked for only when a new or moved obstacle touches the path still ahead (grown by the walking body's
//     radius), never for one beside it or behind the traveller, and never more often than REROUTE_MIN_INTERVAL_MS;
//   - an obstacle that only shifted a little (SIGNIFICANT_SHIFT_METERS) or kept its footprint is the same obstacle;
//   - when every obstacle the route was planned around is gone, the route is planned again without it (restoring the
//     normal route), once it has been gone for CLEAR_HOLD_MS, so a person stepping aside for a moment does not flip it;
//   - when a reroute found no route (NO_ROUTE: the obstacle blocks the only way, or the only step-free way), the same
//     obstacles never ask again: the current route is kept, and the UI says why it was not changed.
//
// The observations come from obstacle-tracker.ts (camera depth, confirmed over several frames); this module does not
// care how they were made.

import type { Vec3 } from "./coordinate-frame.ts";
import type { BlockedRegion } from "./navigation-api.ts";

export interface ObstacleObservation {
  floorId: string;
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
  observedAtMs: number;
  /** The coordinate frame the device was localized in when it observed this. */
  coordinateFrameId: string | null;
  /** What it is, how sure the sensing was and how far its placement may be off (obstacle-sensing.ts). The footprint
   * already includes the uncertainty. Absent for observations from elsewhere. */
  category?: "PERSON" | "UNCLASSIFIED";
  confidence?: number;
  uncertaintyMeters?: number;
}

/** Matches the server's chaya.navigation.obstacle-max-age-seconds. */
export const OBSTACLE_MAX_AGE_MS = 120_000;
export const REROUTE_MIN_INTERVAL_MS = 5_000;
export const SIGNIFICANT_SHIFT_METERS = 0.3;
/** The walking body's radius (the server's chaya.navigation.agent-radius-m). */
export const PATH_BUFFER_METERS = 0.35;
/** How long the obstacles a route avoids must be gone before the normal route is restored. */
export const CLEAR_HOLD_MS = 5_000;

export function freshObstacles(observations: readonly ObstacleObservation[], nowMs: number): ObstacleObservation[] {
  return observations.filter((o) => nowMs - o.observedAtMs <= OBSTACLE_MAX_AGE_MS && o.observedAtMs <= nowMs + 10_000);
}

export function toBlockedRegions(observations: readonly ObstacleObservation[]): BlockedRegion[] {
  return observations.map((o) => ({
    floorId: o.floorId, minX: o.minX, minY: o.minY, maxX: o.maxX, maxY: o.maxY,
    observedAt: new Date(o.observedAtMs).toISOString(), coordinateFrameId: o.coordinateFrameId,
  }));
}

/** Whether two observations are the same obstacle: same floor, and no edge of the footprint moved by more than
 * SIGNIFICANT_SHIFT_METERS. */
export function sameObstacle(a: ObstacleObservation, b: ObstacleObservation): boolean {
  return a.floorId === b.floorId && Math.abs(a.minX - b.minX) <= SIGNIFICANT_SHIFT_METERS && Math.abs(a.maxX - b.maxX) <= SIGNIFICANT_SHIFT_METERS
    && Math.abs(a.minY - b.minY) <= SIGNIFICANT_SHIFT_METERS && Math.abs(a.maxY - b.maxY) <= SIGNIFICANT_SHIFT_METERS;
}

/** Whether the horizontal segment a-b passes within `grow` of the box. */
function segmentTouchesBox(a: Vec3, b: Vec3, o: ObstacleObservation, grow: number): boolean {
  const minX = o.minX - grow, minY = o.minY - grow, maxX = o.maxX + grow, maxY = o.maxY + grow;
  const dx = b[0] - a[0], dy = b[1] - a[1];
  const p = [-dx, dx, -dy, dy];
  const q = [a[0] - minX, maxX - a[0], a[1] - minY, maxY - a[1]];
  let t0 = 0, t1 = 1;
  for (let i = 0; i < 4; i++) {
    if (p[i] === 0) {
      if (q[i] < 0) return false;
    } else {
      const t = q[i] / p[i];
      if (p[i] < 0) t0 = Math.max(t0, t);
      else t1 = Math.min(t1, t);
    }
  }
  return t0 <= t1;
}

export function touchesPath(path: readonly Vec3[], o: ObstacleObservation): boolean {
  if (path.length === 1) return segmentTouchesBox(path[0], path[0], o, PATH_BUFFER_METERS);
  for (let i = 1; i < path.length; i++) if (segmentTouchesBox(path[i - 1], path[i], o, PATH_BUFFER_METERS)) return true;
  return false;
}

export type RerouteDecision =
  | { reroute: true; reason: "OBSTACLE_ON_PATH" | "OBSTACLE_CLEARED"; obstacles: ObstacleObservation[] }
  | { reroute: false; reason: "NOTHING_NEW_ON_PATH" | "TOO_SOON" | "NO_OBSTACLE_SOURCE" | "NO_ALTERNATIVE"; obstacles: ObstacleObservation[] };

/**
 * Whether to ask the server for a new route now.
 * @param remainingPath the part of this floor's route still ahead of the traveller (canonical metres)
 * @param routedAround the observations the current route was planned with
 * @param observed every observation the device's obstacle source holds now (null: the device has no obstacle source)
 */
export function rerouteDecision(args: {
  floorId: string;
  remainingPath: readonly Vec3[];
  routedAround: readonly ObstacleObservation[];
  observed: readonly ObstacleObservation[] | null;
  nowMs: number;
  lastRerouteMs: number | null;
  /** The obstacles the last reroute was refused for (NO_ROUTE): not asked about again. */
  failedAround?: readonly ObstacleObservation[];
  /** When the route's avoided obstacles were first found all gone (null: not all gone). The caller keeps it with
   * clearedSince(). */
  clearedSinceMs?: number | null;
}): RerouteDecision {
  if (args.observed === null) return { reroute: false, reason: "NO_OBSTACLE_SOURCE", obstacles: [] };
  const tooSoon = args.lastRerouteMs !== null && args.nowMs - args.lastRerouteMs < REROUTE_MIN_INTERVAL_MS;
  const failed = args.failedAround ?? [];
  // An obstacle whose (uncertainty-grown) footprint holds the traveller is beside them, not ahead: the server has no
  // route from inside a reported box, so it is never sent.
  const at = args.remainingPath[0];
  const holdsTraveller = (o: ObstacleObservation) => at !== undefined && at[0] >= o.minX && at[0] <= o.maxX && at[1] >= o.minY && at[1] <= o.maxY;
  const fresh = freshObstacles(args.observed, args.nowMs).filter((o) => !holdsTraveller(o));
  const ahead = fresh.filter((o) => o.floorId === args.floorId && touchesPath(args.remainingPath, o)
    && !args.routedAround.some((r) => sameObstacle(r, o)));
  const onPath = ahead.filter((o) => !failed.some((f) => sameObstacle(f, o)));
  if (onPath.length > 0) {
    return tooSoon ? { reroute: false, reason: "TOO_SOON", obstacles: fresh } : { reroute: true, reason: "OBSTACLE_ON_PATH", obstacles: fresh };
  }
  if (ahead.length > 0) return { reroute: false, reason: "NO_ALTERNATIVE", obstacles: fresh };
  if (args.routedAround.length > 0 && args.clearedSinceMs != null && args.nowMs - args.clearedSinceMs >= CLEAR_HOLD_MS) {
    return tooSoon ? { reroute: false, reason: "TOO_SOON", obstacles: fresh } : { reroute: true, reason: "OBSTACLE_CLEARED", obstacles: fresh };
  }
  return { reroute: false, reason: "NOTHING_NEW_ON_PATH", obstacles: fresh };
}

/** Since when none of the obstacles the route avoids is observed any more (null while one still is, or none was). */
export function clearedSince(routedAround: readonly ObstacleObservation[], observed: readonly ObstacleObservation[] | null,
                             previous: number | null, nowMs: number): number | null {
  if (routedAround.length === 0 || observed === null) return null;
  const fresh = freshObstacles(observed, nowMs);
  const stillThere = routedAround.some((r) => fresh.some((o) => sameObstacle(r, o)));
  return stillThere ? null : previous ?? nowMs;
}

/** What the UI may say about obstacle handling: only a device that actually produces observations has live detection. */
export function obstacleSourceStatus(observed: readonly ObstacleObservation[] | null): "LIVE" | "UNAVAILABLE" {
  return observed === null ? "UNAVAILABLE" : "LIVE";
}
