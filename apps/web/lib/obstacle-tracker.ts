// Obstacles over time (docs/navigation.md, "Camera obstacle sensing"). Pure: every step takes the previous tracks and one
// processed frame's observations and returns the next tracks.
//
//   association   an observation joins the nearest track of a compatible category on the same floor and frame within
//                 ASSOCIATION_METERS (plus both uncertainties); a person and an UNCLASSIFIED depth cluster at the same
//                 place are one obstacle, a person. So repeated sightings, and the two detectors, never add duplicates.
//   confirmation  a track is reported only after CONFIRM_HITS sightings: one frame's detection is not an obstacle.
//   motion        a person moving faster than MOVING_SPEED is not routed around (it will be elsewhere by the time the
//                 traveller gets there); the UI warns about it instead. A standing person blocks like an object does.
//   occlusion     a track that was not detected again is kept while its place is hidden behind something nearer or out
//                 of view (visibilityAt); it is dropped once its place is measured clear CLEAR_AFTER_MISSES times.
//   expiry        a track not seen for its category's TTL is dropped (people move on; an object is checked again sooner
//                 than the server's 120 s limit). A change of floor or coordinate frame drops every track.

import type { Vec3 } from "./coordinate-frame.ts";
import type { ObstacleObservation } from "./obstacle-reroute.ts";
import type { LocalizedObstacle, ObstacleCategory, Visibility } from "./obstacle-sensing.ts";

export const ASSOCIATION_METERS = 0.75;
export const CONFIRM_HITS = 2;
export const MOVING_SPEED_MPS = 0.4;
export const CLEAR_AFTER_MISSES = 3;
export const TTL_MS: Record<ObstacleCategory, number> = { PERSON: 10_000, UNCLASSIFIED: 30_000 };
/** Velocity is smoothed: this share of the newest estimate. */
const VELOCITY_GAIN = 0.5;

export type TrackStatus = "TENTATIVE" | "MOVING" | "STATIONARY";

export interface ObstacleTrack {
  id: number;
  category: ObstacleCategory;
  floorId: string;
  coordinateFrameId: string | null;
  last: LocalizedObstacle;
  firstSeenMs: number;
  lastSeenMs: number;
  hits: number;
  clearMisses: number;
  /** Canonical m/s; null until two sightings at least 0.2 s apart. */
  velocity: [number, number] | null;
  status: TrackStatus;
}

export interface TrackerState {
  tracks: ObstacleTrack[];
  nextId: number;
  floorId: string | null;
  coordinateFrameId: string | null;
}

export const initialTracker = (): TrackerState => ({ tracks: [], nextId: 1, floorId: null, coordinateFrameId: null });

const compatible = (a: ObstacleCategory, b: ObstacleCategory) => a === b || a === "UNCLASSIFIED" || b === "UNCLASSIFIED";
const dist2d = (a: Vec3, b: Vec3) => Math.hypot(a[0] - b[0], a[1] - b[1]);

function statusOf(t: ObstacleTrack): TrackStatus {
  if (t.hits < CONFIRM_HITS) return "TENTATIVE";
  const speed = t.velocity ? Math.hypot(t.velocity[0], t.velocity[1]) : 0;
  return t.category === "PERSON" && speed > MOVING_SPEED_MPS ? "MOVING" : "STATIONARY";
}

/**
 * One processed frame.
 * @param visibility what the frame's depth says about a place (obstacle-sensing.ts visibilityAt), for tracks not seen
 */
export function updateTracks(state: TrackerState, observations: readonly LocalizedObstacle[], frame: {
  nowMs: number; floorId: string; coordinateFrameId: string | null; visibility: (p: Vec3) => Visibility;
}): TrackerState {
  let tracks = state.tracks;
  if (state.floorId !== frame.floorId || state.coordinateFrameId !== frame.coordinateFrameId) tracks = [];
  let nextId = state.nextId;
  const matched = new Set<number>();
  const updated = new Map<number, ObstacleTrack>();
  // Most confident observations claim tracks first.
  for (const o of [...observations].sort((a, b) => b.confidence - a.confidence)) {
    if (o.floorId !== frame.floorId || o.coordinateFrameId !== frame.coordinateFrameId) continue;
    let best: ObstacleTrack | null = null, bestD = Infinity;
    for (const t of tracks) {
      if (matched.has(t.id) || !compatible(t.category, o.category)) continue;
      const d = dist2d(t.last.center, o.center);
      if (d <= ASSOCIATION_METERS + t.last.uncertaintyMeters + o.uncertaintyMeters && d < bestD) { best = t; bestD = d; }
    }
    if (!best) {
      // A second observation of something this frame already added (two detectors on one person) is the same one.
      const dup = [...updated.values()].find((t) => t.lastSeenMs === frame.nowMs && compatible(t.category, o.category)
        && dist2d(t.last.center, o.center) <= ASSOCIATION_METERS);
      if (dup) {
        if (o.category === "PERSON") updated.set(dup.id, { ...dup, category: "PERSON", last: o, status: statusOf({ ...dup, category: "PERSON" }) });
        continue;
      }
      const t: ObstacleTrack = {
        id: nextId++, category: o.category, floorId: o.floorId, coordinateFrameId: o.coordinateFrameId, last: o,
        firstSeenMs: frame.nowMs, lastSeenMs: frame.nowMs, hits: 1, clearMisses: 0, velocity: null, status: "TENTATIVE",
      };
      updated.set(t.id, t);
      continue;
    }
    matched.add(best.id);
    const dt = (frame.nowMs - best.lastSeenMs) / 1000;
    let velocity = best.velocity;
    if (dt >= 0.2) {
      const v: [number, number] = [(o.center[0] - best.last.center[0]) / dt, (o.center[1] - best.last.center[1]) / dt];
      velocity = velocity ? [velocity[0] + VELOCITY_GAIN * (v[0] - velocity[0]), velocity[1] + VELOCITY_GAIN * (v[1] - velocity[1])] : v;
    }
    const category: ObstacleCategory = best.category === "PERSON" || o.category === "PERSON" ? "PERSON" : "UNCLASSIFIED";
    const next: ObstacleTrack = { ...best, category, last: o, lastSeenMs: frame.nowMs, hits: best.hits + 1, clearMisses: 0, velocity };
    updated.set(next.id, { ...next, status: statusOf(next) });
  }
  const out: ObstacleTrack[] = [];
  for (const t of tracks) {
    const u = updated.get(t.id);
    if (u) { out.push(u); updated.delete(t.id); continue; }
    if (frame.nowMs - t.lastSeenMs > TTL_MS[t.category]) continue;
    const seen = frame.visibility(t.last.center);
    // Seen clear: count it; still occupied (the detector missed it) or hidden: keep it as it was.
    const clearMisses = seen === "CLEAR" ? t.clearMisses + 1 : t.clearMisses;
    if (clearMisses >= CLEAR_AFTER_MISSES) continue;
    out.push({ ...t, clearMisses });
  }
  out.push(...updated.values());
  return { tracks: out, nextId, floorId: frame.floorId, coordinateFrameId: frame.coordinateFrameId };
}

/** Drops tracks past their TTL without a new frame (sensing paused: tracking lost, not localized). */
export function expireTracks(state: TrackerState, nowMs: number): TrackerState {
  const tracks = state.tracks.filter((t) => nowMs - t.lastSeenMs <= TTL_MS[t.category]);
  return tracks.length === state.tracks.length ? state : { ...state, tracks };
}

/** The confirmed, not-moving obstacles, as the reroute rules (and the server) take them. observedAt is the last sighting. */
export function blockingObservations(state: TrackerState): ObstacleObservation[] {
  return state.tracks.filter((t) => t.status === "STATIONARY").map((t) => ({
    floorId: t.floorId, minX: t.last.minX, minY: t.last.minY, maxX: t.last.maxX, maxY: t.last.maxY,
    observedAtMs: t.lastSeenMs, coordinateFrameId: t.coordinateFrameId,
    category: t.category, confidence: t.last.confidence, uncertaintyMeters: t.last.uncertaintyMeters,
  }));
}

/** Confirmed people moving across the area: warned about, not routed around. */
export function movingPeople(state: TrackerState): ObstacleTrack[] {
  return state.tracks.filter((t) => t.status === "MOVING");
}
