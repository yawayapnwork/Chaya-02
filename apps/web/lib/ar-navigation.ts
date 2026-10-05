// The navigation rules of the Android AR session that sit between localization and drawing (docs/ar.md, "Android:
// WebXR"). Pure, so every rule is unit-tested without a device; components/ArWorkspace.tsx only wires WebXR to them.
//
//   session request   arSessionInit: image tracking and world anchors are required; a hit test is never requested.
//                     A surface under a ray identifies no marker, so it can never localize (review AR-1).
//   route check       routeLegFor: a route leg is drawn only when the server routed that floor on the same scan version
//                     and coordinate frame the session was localized in (routingSources). Anything else is a
//                     VERSION_MISMATCH / FRAME_MISMATCH and nothing is drawn.
//   drift             markerDrift: while localized, a registered marker seen again is mapped through the current
//                     transform and compared with its registered pose. Beyond DRIFT_LIMIT_* the transform is dropped
//                     (the session relocalizes from that marker); within it, the marker refreshes the transform.
//   between fixes     blendedTransform: a refreshed transform replaces the old one over BLEND_MS for drawing only.
//                     Progress always uses the latest server answer. Between fixes the platform's own visual-inertial
//                     tracking (the viewer pose, and the world anchor's map corrections) carries the device; nothing
//                     here predicts motion.
//   floor handoff     floorHandoff: near the end of this floor's leg (a TRANSITION), the session leaves the floor. The
//                     transform is never carried to the next floor: each floor has its own canonical frame, so the next
//                     floor is localized from its own markers.

import { type Pose, compose, interpolate } from "./ar-anchor-math.ts";
import type { Localization } from "./ar-relocalization.ts";
import type { XrTrackedImageInit } from "./ar-marker-tracking.ts";
import { routeLegOnFloor } from "./ar-route.ts";
import type { Vec3 } from "./coordinate-frame.ts";
import type { RouteResponse, RouteWaypoint } from "./navigation-api.ts";

export const AR_REQUIRED_FEATURES: readonly string[] = ["image-tracking", "anchors"];
export const AR_OPTIONAL_FEATURES: readonly string[] = ["dom-overlay"];

/** The immersive-ar session request: marker detection (image tracking) and world anchors, nothing that places content
 * on a hit surface. */
export function arSessionInit(images: XrTrackedImageInit[], overlayRoot: Element | null): Record<string, unknown> {
  return {
    requiredFeatures: [...AR_REQUIRED_FEATURES],
    optionalFeatures: [...AR_OPTIONAL_FEATURES],
    ...(overlayRoot ? { domOverlay: { root: overlayRoot } } : {}),
    trackedImages: images,
  };
}

// ---- route vs localization ------------------------------------------------------------------------------------------

export type RouteLegCheck =
  | { ok: true; points: Vec3[]; waypoints: RouteWaypoint[] }
  | { ok: false; problem: "NO_LEG_ON_FLOOR" | "ROUTE_SOURCE_MISSING" | "VERSION_MISMATCH" | "FRAME_MISMATCH"; detail: string };

/** This floor's leg of `route`, if it may be drawn with `localization`: the server must have routed the floor on the scan
 * version and in the coordinate frame the transform was solved in. A route of another version or frame is in other
 * coordinates, and drawn with this transform it would be misplaced, so it is refused, never drawn. */
export function routeLegFor(route: RouteResponse, localization: Localization): RouteLegCheck {
  const leg = routeLegOnFloor(route, localization.floorId);
  if (leg.points.length === 0) {
    return { ok: false, problem: "NO_LEG_ON_FLOOR", detail: `the route does not pass through floor ${localization.floorId}` };
  }
  const source = (route.routingSources ?? []).find((s) => s.floorId === localization.floorId);
  if (!source) {
    return { ok: false, problem: "ROUTE_SOURCE_MISSING", detail: `the route does not say what floor ${localization.floorId} was routed on` };
  }
  if (source.scanVersionId !== localization.scanVersionId) {
    return {
      ok: false, problem: "VERSION_MISMATCH",
      detail: `floor ${localization.floorId} was routed on scan version ${source.scanVersionId ?? "(none)"}, but the session is ` +
        `localized in ${localization.scanVersionId}`,
    };
  }
  if (source.coordinateFrameId !== localization.coordinateFrameId) {
    return {
      ok: false, problem: "FRAME_MISMATCH",
      detail: `floor ${localization.floorId} was routed in coordinate frame ${source.coordinateFrameId ?? "(none)"}, but the ` +
        `session is localized in ${localization.coordinateFrameId}`,
    };
  }
  return { ok: true, points: leg.points, waypoints: leg.waypoints };
}

// ---- drift ----------------------------------------------------------------------------------------------------------

/** Beyond these, a marker seen again disagrees with the transform by more than a re-solve should smooth over. */
export const DRIFT_LIMIT_METERS = 0.3;
export const DRIFT_LIMIT_DEGREES = 8;

/** How far `observedPose` (a registered marker's pose in the AR world frame, now) lands from its registered canonical
 * pose when mapped through `deviceToVenue`. Zero when the transform still explains what the camera sees. */
export function markerDrift(deviceToVenue: Pose, observedPose: Pose, digitalPose: Pose): { translationMeters: number; rotationDegrees: number } {
  const predicted = compose(deviceToVenue, observedPose);
  const translationMeters = Math.hypot(predicted.x - digitalPose.x, predicted.y - digitalPose.y, predicted.z - digitalPose.z);
  const np = Math.hypot(predicted.qx, predicted.qy, predicted.qz, predicted.qw) || 1;
  const nd = Math.hypot(digitalPose.qx, digitalPose.qy, digitalPose.qz, digitalPose.qw) || 1;
  const dot = Math.abs((predicted.qx * digitalPose.qx + predicted.qy * digitalPose.qy + predicted.qz * digitalPose.qz
    + predicted.qw * digitalPose.qw) / (np * nd));
  const rotationDegrees = (2 * Math.acos(Math.min(1, dot)) * 180) / Math.PI;
  return { translationMeters, rotationDegrees };
}

export function driftExceeded(d: { translationMeters: number; rotationDegrees: number }): boolean {
  return d.translationMeters > DRIFT_LIMIT_METERS || d.rotationDegrees > DRIFT_LIMIT_DEGREES;
}

/** How long after a solve a marker seen again triggers a re-solve while localized. */
export const REFRESH_INTERVAL_MS = 5000;

export function refreshDue(localization: Localization | null, nowMs: number, pending: boolean): boolean {
  return localization !== null && !pending && nowMs - localization.solvedAtMs >= REFRESH_INTERVAL_MS;
}

// ---- between fixes --------------------------------------------------------------------------------------------------

export const BLEND_MS = 600;

/** The transform to draw with: `target` (the latest server answer, world-anchor corrected), reached from `from` over
 * BLEND_MS after a refresh, so a re-solve does not make the route jump. For drawing only. */
export function blendedTransform(blend: { from: Pose; startedAtMs: number } | null, target: Pose, nowMs: number): Pose {
  if (!blend) return target;
  return interpolate(blend.from, target, (nowMs - blend.startedAtMs) / BLEND_MS);
}

// ---- floor handoff --------------------------------------------------------------------------------------------------

/** How close to the end of this floor's leg (a TRANSITION) the device must be to leave the floor. */
export const HANDOFF_RADIUS_METERS = 2.0;

/** The floor transition the device has reached on `floorId`, or null: this floor's leg ends in a TRANSITION, the device
 * is within HANDOFF_RADIUS_METERS of the leg's end along the route, and the route continues on `toFloorId`. */
export function floorHandoff(
  route: RouteResponse, floorId: string, remainingMeters: number | null,
): { toFloorId: string; connectorType: string | null } | null {
  const start = route.waypoints.findIndex((w) => w.floorId === floorId);
  if (start < 0 || remainingMeters === null) return null;
  let end = start;
  while (end + 1 < route.waypoints.length && route.waypoints[end + 1].floorId === floorId) end++;
  if (route.waypoints[end].kind !== "TRANSITION" || end + 1 >= route.waypoints.length) return null;
  if (remainingMeters > HANDOFF_RADIUS_METERS) return null;
  const toFloorId = route.waypoints[end + 1].floorId;
  const transition = route.floorTransitions.find((t) => t.fromFloorId === floorId && t.toFloorId === toFloorId);
  return { toFloorId, connectorType: transition?.connectorType ?? null };
}

/** The floors a route visits, in order (the markers of each are tracked for the whole session). */
export function floorsOf(route: Pick<RouteResponse, "waypoints">): string[] {
  const out: string[] = [];
  for (const w of route.waypoints) if (out[out.length - 1] !== w.floorId) out.push(w.floorId);
  return out;
}
