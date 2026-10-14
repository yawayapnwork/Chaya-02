// Turn-by-turn presentation of a route from POST /navigation/routes (or /routes/multi-stop), and the rules that follow a
// traveller along it: where they are on the route, how far is left, and when they have really arrived. Pure, so every
// rule is unit-tested without a device; components only wire positions into it.
//
// Distances are calibrated geometry only: the 3-D length of the route's own polyline in canonical metres (the frame the
// server routed in), plus the walked length venue staff registered for each stairs or ramp connection. Two floors'
// coordinates are never compared: the step from a TRANSITION on one floor to the next floor's first waypoint is not a
// distance, it is the connection.
//
// Arrival is never declared from one lucky projection: the device must be localized on the destination's floor, on the
// final stretch of the route (not on an earlier pass near the same spot), within ARRIVAL_RADIUS_METERS of the destination
// both along the route and in a straight line, and stay so for ARRIVAL_DWELL_MS.

import type { Vec3 } from "./coordinate-frame.ts";
import type { FloorTransition, RouteResponse, RouteWaypoint } from "./navigation-api.ts";

export type InstructionKind =
  | "DEPART"
  | "SLIGHT_LEFT"
  | "TURN_LEFT"
  | "SHARP_LEFT"
  | "SLIGHT_RIGHT"
  | "TURN_RIGHT"
  | "SHARP_RIGHT"
  | "TAKE_CONNECTION"
  | "STOP"
  | "ARRIVE";

export interface Instruction {
  kind: InstructionKind;
  text: string;
  /** Index into route.waypoints where the instruction applies. */
  waypointIndex: number;
  floorId: string;
  /** Distance along the route from the start to this instruction, metres. */
  atMeters: number;
  /** Distance from this instruction to the next one (0 for the last), metres. */
  toNextMeters: number;
  /** For TAKE_CONNECTION: the connector and the floor it leads to. */
  connectorType?: FloorTransition["connectorType"];
  toFloorId?: string;
}

/** Turns gentler than this are not announced: string-pulled paths bend slightly at portal corners. */
export const MIN_TURN_DEGREES = 20;
const SLIGHT_MAX_DEGREES = 45;
const SHARP_MIN_DEGREES = 135;
/** Segments shorter than this carry no reliable direction. */
const MIN_SEGMENT_METERS = 0.05;

const dist3 = (a: RouteWaypoint, b: RouteWaypoint) => Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);

/** Cumulative distance along the route at each waypoint: same-floor steps by their 3-D length, a floor change by the
 * registered length of the connection crossed there (0 for an elevator, or when the route does not name it). */
export function cumulativeMeters(route: Pick<RouteResponse, "waypoints" | "floorTransitions">): number[] {
  const out: number[] = [];
  let total = 0;
  let crossing = 0;
  route.waypoints.forEach((w, i) => {
    if (i > 0) {
      const prev = route.waypoints[i - 1];
      if (prev.floorId === w.floorId) total += dist3(prev, w);
      else total += route.floorTransitions[crossing++]?.distanceMeters ?? 0;
    }
    out.push(total);
  });
  return out;
}

/** Signed horizontal turn from direction (a->b) to (b->c), degrees: positive is left (counter-clockwise seen from +Z,
 * the canonical up axis), negative is right. null when either segment is too short to have a direction. */
export function turnDegrees(a: RouteWaypoint, b: RouteWaypoint, c: RouteWaypoint): number | null {
  const ux = b.x - a.x, uy = b.y - a.y, vx = c.x - b.x, vy = c.y - b.y;
  if (Math.hypot(ux, uy) < MIN_SEGMENT_METERS || Math.hypot(vx, vy) < MIN_SEGMENT_METERS) return null;
  return (Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy) * 180) / Math.PI;
}

function turnKind(deg: number): InstructionKind | null {
  const a = Math.abs(deg);
  if (a < MIN_TURN_DEGREES) return null;
  const side = deg > 0 ? "LEFT" : "RIGHT";
  if (a < SLIGHT_MAX_DEGREES) return `SLIGHT_${side}` as InstructionKind;
  if (a >= SHARP_MIN_DEGREES) return `SHARP_${side}` as InstructionKind;
  return `TURN_${side}` as InstructionKind;
}

const TURN_TEXT: Record<string, string> = {
  SLIGHT_LEFT: "Bear left", TURN_LEFT: "Turn left", SHARP_LEFT: "Turn sharply left",
  SLIGHT_RIGHT: "Bear right", TURN_RIGHT: "Turn right", SHARP_RIGHT: "Turn sharply right",
};

const CONNECTOR_TEXT: Record<FloorTransition["connectorType"], string> = { ELEVATOR: "lift", STAIRS: "stairs", RAMP: "ramp" };

export interface InstructionNames {
  floorName?: (floorId: string) => string;
  /** The name of the n-th stop (0-based, in route order) of a multi-stop route, and of the final destination. */
  stopName?: (stopIndex: number) => string | null;
}

/** The route as instructions: depart, every announced turn, every floor connection, every intermediate stop, arrival. */
export function routeInstructions(route: Pick<RouteResponse, "waypoints" | "floorTransitions">, names: InstructionNames = {}): Instruction[] {
  const w = route.waypoints;
  if (w.length < 2) return [];
  const at = cumulativeMeters(route);
  const floorName = names.floorName ?? ((id: string) => id);
  const out: Omit<Instruction, "toNextMeters">[] = [];
  out.push({ kind: "DEPART", text: "Start along the highlighted route", waypointIndex: 0, floorId: w[0].floorId, atMeters: 0 });
  let crossing = 0;
  let stop = 0;
  for (let i = 1; i < w.length; i++) {
    const p = w[i];
    if (p.kind === "TRANSITION" && i + 1 < w.length && w[i + 1].floorId !== p.floorId) {
      const t = route.floorTransitions[crossing++];
      const connector = t ? CONNECTOR_TEXT[t.connectorType] : "connection";
      out.push({
        kind: "TAKE_CONNECTION", text: `Take the ${connector} to ${floorName(w[i + 1].floorId)}`, waypointIndex: i, floorId: p.floorId,
        atMeters: at[i], connectorType: t?.connectorType, toFloorId: w[i + 1].floorId,
      });
      continue;
    }
    if (p.kind === "STOP") {
      const name = names.stopName?.(stop) ?? null;
      stop++;
      out.push({ kind: "STOP", text: name ? `Arrive at stop: ${name}` : "Arrive at the next stop", waypointIndex: i, floorId: p.floorId, atMeters: at[i] });
      continue;
    }
    if (p.kind === "DESTINATION" || i === w.length - 1) {
      const name = names.stopName?.(stop) ?? null;
      out.push({ kind: "ARRIVE", text: name ? `Arrive at ${name}` : "Arrive at your destination", waypointIndex: i, floorId: p.floorId, atMeters: at[i] });
      continue;
    }
    // A turn at an inner vertex of one floor's polyline.
    if (w[i - 1].floorId !== p.floorId || w[i + 1].floorId !== p.floorId) continue;
    const deg = turnDegrees(w[i - 1], p, w[i + 1]);
    const kind = deg === null ? null : turnKind(deg);
    if (kind) out.push({ kind, text: TURN_TEXT[kind], waypointIndex: i, floorId: p.floorId, atMeters: at[i] });
  }
  return out.map((ins, k) => ({ ...ins, toNextMeters: k + 1 < out.length ? out[k + 1].atMeters - ins.atMeters : 0 }));
}

// ---- following the route ------------------------------------------------------------------------------------------

export interface RoutePosition {
  /** Index of the route segment (waypoints[segment] -> waypoints[segment + 1]) the device is on. */
  segment: number;
  /** Fraction along that segment. */
  t: number;
  /** Distance along the route from the start, metres (cumulativeMeters). */
  alongMeters: number;
  /** Distance still to go to the end of the route, metres. */
  remainingMeters: number;
  /** Horizontal distance from the device to the route, metres. */
  offRouteMeters: number;
}

/** Beyond this distance from the route the traveller is re-acquired anywhere on it; nearer, only from where they were. */
export const REACQUIRE_OFF_ROUTE_METERS = 3;
/** How far along the route ahead of the last position the next one is looked for (several seconds of walking). */
export const LOOKAHEAD_METERS = 5;

/**
 * Where the device is on the route: the nearest point of the route's segments on `floorId`, measured in plan view (the
 * device is held above the floor), with distances from the route's own 3-D lengths. Progress is continuous: unless the
 * device is far off the route, only segments from the previous one up to LOOKAHEAD_METERS further along are considered,
 * so a route that passes the same spot twice (out and back, or a later stop near an earlier one) is neither jumped ahead
 * on nor walked back.
 */
export function locateOnRoute(
  route: Pick<RouteResponse, "waypoints" | "floorTransitions">, floorId: string, device: Vec3, previous: RoutePosition | null,
): RoutePosition | null {
  const w = route.waypoints;
  const at = cumulativeMeters(route);
  const total = at[at.length - 1] ?? 0;
  const search = (from: number, untilMeters: number) => {
    let best: { seg: number; t: number; d: number } | null = null;
    for (let i = Math.max(0, from); i + 1 < w.length && at[i] <= untilMeters; i++) {
      const a = w[i], b = w[i + 1];
      if (a.floorId !== floorId || b.floorId !== floorId) continue;
      const dx = b.x - a.x, dy = b.y - a.y;
      const len2 = dx * dx + dy * dy;
      const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((device[0] - a.x) * dx + (device[1] - a.y) * dy) / len2));
      const d = Math.hypot(a.x + t * dx - device[0], a.y + t * dy - device[1]);
      if (!best || d < best.d - 1e-9) best = { seg: i, t, d };
    }
    return best;
  };
  let best = previous ? search(previous.segment, previous.alongMeters + LOOKAHEAD_METERS) : search(0, LOOKAHEAD_METERS);
  if (!best || best.d > REACQUIRE_OFF_ROUTE_METERS) {
    const anywhere = search(0, Infinity);
    if (anywhere && (!best || anywhere.d < best.d)) best = anywhere;
  }
  if (!best) return null;
  const along = at[best.seg] + best.t * (at[best.seg + 1] - at[best.seg]);
  return { segment: best.seg, t: best.t, alongMeters: along, remainingMeters: Math.max(0, total - along), offRouteMeters: best.d };
}

/** The instruction coming up: the first one at or ahead of the traveller's position along the route. */
export function nextInstruction(instructions: Instruction[], position: Pick<RoutePosition, "alongMeters"> | null): Instruction | null {
  if (instructions.length === 0) return null;
  if (!position) return instructions[0];
  return instructions.find((i) => i.kind !== "DEPART" && i.atMeters >= position.alongMeters - 0.5) ?? instructions[instructions.length - 1];
}

// ---- arrival --------------------------------------------------------------------------------------------------------

export const ARRIVAL_RADIUS_METERS = 1.5;
export const ARRIVAL_DWELL_MS = 2000;

export interface ArrivalState {
  /** The waypoint index being arrived at: the next STOP, or the DESTINATION. */
  targetIndex: number;
  /** Since when every arrival condition has held without a break; null when one does not hold now. */
  candidateSinceMs: number | null;
  /** STOP waypoint indices already reached, in order. */
  reachedStops: number[];
  /** The whole route is complete. */
  arrived: boolean;
}

/** The next STOP or the DESTINATION after `afterIndex`. */
function targetAfter(waypoints: RouteWaypoint[], afterIndex: number): number {
  for (let i = afterIndex + 1; i < waypoints.length; i++) if (waypoints[i].kind === "STOP") return i;
  return waypoints.length - 1;
}

export function initialArrival(route: Pick<RouteResponse, "waypoints">): ArrivalState {
  return { targetIndex: targetAfter(route.waypoints, 0), candidateSinceMs: null, reachedStops: [], arrived: false };
}

/**
 * One tracking update. `position` is this frame's locateOnRoute (null when not localized, or not on the target's floor).
 * The target counts as reached only when, continuously for ARRIVAL_DWELL_MS: the device is localized on the target's floor;
 * its position on the route is on the segment that ends at the target (so an earlier pass nearby does not count); it is
 * within ARRIVAL_RADIUS_METERS of the target both along the route and in a straight horizontal line. Reaching a STOP moves
 * on to the next target; reaching the DESTINATION completes the route, which then stays complete.
 */
export function updateArrival(
  state: ArrivalState, route: Pick<RouteResponse, "waypoints" | "floorTransitions">, floorId: string | null, device: Vec3 | null,
  position: RoutePosition | null, nowMs: number,
): ArrivalState {
  if (state.arrived) return state;
  const w = route.waypoints;
  const target = w[state.targetIndex];
  const at = cumulativeMeters(route);
  const holds = target !== undefined && device !== null && position !== null && floorId === target.floorId
    && position.segment === state.targetIndex - 1
    && at[state.targetIndex] - position.alongMeters <= ARRIVAL_RADIUS_METERS
    && Math.hypot(device[0] - target.x, device[1] - target.y) <= ARRIVAL_RADIUS_METERS;
  if (!holds) return state.candidateSinceMs === null ? state : { ...state, candidateSinceMs: null };
  const since = state.candidateSinceMs ?? nowMs;
  if (nowMs - since < ARRIVAL_DWELL_MS) return state.candidateSinceMs === null ? { ...state, candidateSinceMs: since } : state;
  if (target.kind === "STOP") {
    return { targetIndex: targetAfter(w, state.targetIndex), candidateSinceMs: null, reachedStops: [...state.reachedStops, state.targetIndex], arrived: false };
  }
  return { ...state, candidateSinceMs: null, arrived: true };
}
