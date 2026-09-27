// Route geometry for AR: the transform chain between the device and the venue, and route progress. Pure.
//
// Frames (docs/ar.md, "Frames"):
//   camera/device frame  the device camera's own frame (the XR viewer): x right, y up, -z forward
//   AR world frame       the session's "local" reference space: metres, gravity-aligned, +Y up, origin where the session
//                        started. The platform gives the camera's pose in it every frame (XRFrame.getViewerPose), and
//                        each tracked marker's pose (XRFrame.getPose(imageSpace, local)).
//   canonical venue      Chaya's canonical frame: metres, +Z up (docs/coordinate-frames.md). Routes, POIs and anchors live
//                        here.
//
//   camera -> AR world:      the viewer pose (platform-measured, per frame)
//   AR world -> canonical:   deviceToVenue, solved by the server from marker observations (digitalPose o observedPose^-1).
//                            It absorbs the +Y-up/+Z-up axis change (lib/ar-frame-boundary.ts checks it keeps gravity).
//   canonical -> AR world:   its inverse, which is how route points get into the scene the renderer draws.
//
// World anchor (WebXR XRAnchor): the platform keeps refining its map, and may shift where a physical spot sits in the
// "local" space. deviceToVenue was solved against the AR world as it was at that moment. A world anchor created at the
// observed marker's pose then carries that correction: if the anchor was at A0 when the transform was solved and is at
// At now, the physical point that was at w is now at At * A0^-1 * w, so the current transform is
// deviceToVenue * A0 * At^-1 (anchorCorrectedDeviceToVenue). The anchor identifies nothing; the marker does that.

import { type Pose, compose, invert } from "./ar-anchor-math.ts";
import { devicePointToVenue, venuePointToDevice } from "./ar-frame-boundary.ts";
import type { Vec3 } from "./coordinate-frame.ts";
import type { RouteResponse, RouteWaypoint } from "./navigation-api.ts";

const pointPose = (p: Vec3): Pose => ({ x: p[0], y: p[1], z: p[2], qx: 0, qy: 0, qz: 0, qw: 1 });

/** A point given in the camera's own frame, in the AR world frame. */
export function cameraPointToWorld(viewerPose: Pose, p: Vec3): Vec3 {
  const w = compose(viewerPose, pointPose(p));
  return [w.x, w.y, w.z];
}

/** A point in the AR world frame, in the camera's own frame. */
export function worldPointToCamera(viewerPose: Pose, p: Vec3): Vec3 {
  const c = compose(invert(viewerPose), pointPose(p));
  return [c.x, c.y, c.z];
}

/** camera frame -> AR world -> canonical venue. */
export function cameraPointToVenue(viewerPose: Pose, deviceToVenue: Pose, p: Vec3): Vec3 {
  return devicePointToVenue(deviceToVenue, cameraPointToWorld(viewerPose, p));
}

/** Where the device (the camera centre) is in canonical venue metres. */
export function devicePositionInVenue(viewerPose: Pose, deviceToVenue: Pose): Vec3 {
  return cameraPointToVenue(viewerPose, deviceToVenue, [0, 0, 0]);
}

/** The AR-world -> canonical transform now, corrected by how far the platform has moved the world anchor since the
 * transform was solved. With no correction (anchorNow == anchorAtSolve) it is deviceToVenue itself. */
export function anchorCorrectedDeviceToVenue(deviceToVenue: Pose, anchorAtSolve: Pose, anchorNow: Pose): Pose {
  return compose(deviceToVenue, compose(anchorAtSolve, invert(anchorNow)));
}

/** The canonical -> AR world pose: the renderer places the route (built in canonical coordinates) with it. Applying it
 * to a canonical point gives the same result as routeToArWorld. */
export function venueToArWorld(deviceToVenue: Pose): Pose {
  return invert(deviceToVenue);
}

/** Canonical route points -> AR world frame. */
export function routeToArWorld(points: readonly Vec3[], deviceToVenue: Pose): Vec3[] {
  return points.map((p) => venuePointToDevice(deviceToVenue, p));
}

/** The part of a route on `floorId`: from its first waypoint on that floor through the last consecutive one (a TRANSITION
 * to another floor or the DESTINATION). */
export function routeLegOnFloor(route: Pick<RouteResponse, "waypoints">, floorId: string): { points: Vec3[]; waypoints: RouteWaypoint[] } {
  const start = route.waypoints.findIndex((w) => w.floorId === floorId);
  if (start < 0) return { points: [], waypoints: [] };
  let end = start;
  while (end + 1 < route.waypoints.length && route.waypoints[end + 1].floorId === floorId) end++;
  const waypoints = route.waypoints.slice(start, end + 1);
  return { points: waypoints.map((w) => [w.x, w.y, w.z] as Vec3), waypoints };
}

export interface RouteProgress {
  /** Index of the next route vertex ahead of the device. */
  nextIndex: number;
  /** Distance still to walk along the route from the device's projection onto it, metres. */
  remainingMeters: number;
  /** Horizontal distance from the device to the route, metres. */
  offRouteMeters: number;
  /** Canonical device position this was computed from. */
  from: Vec3;
}

function horizontal(a: Vec3, b: Vec3): number {
  return Math.hypot(a[0] - b[0], a[1] - b[1]);
}

/** Projects the device's canonical position onto the route polyline, in the horizontal plane (canonical x, y: the route
 * runs over the floor, the camera is ~1.5 m above it). */
export function progressAlong(points: readonly Vec3[], device: Vec3): RouteProgress | null {
  if (points.length === 0) return null;
  if (points.length === 1) return { nextIndex: 0, remainingMeters: horizontal(points[0], device), offRouteMeters: horizontal(points[0], device), from: device };
  let best = { dist: Infinity, seg: 0, t: 0 };
  for (let i = 0; i + 1 < points.length; i++) {
    const [ax, ay] = points[i];
    const [bx, by] = points[i + 1];
    const dx = bx - ax, dy = by - ay;
    const len2 = dx * dx + dy * dy;
    const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((device[0] - ax) * dx + (device[1] - ay) * dy) / len2));
    const d = Math.hypot(ax + t * dx - device[0], ay + t * dy - device[1]);
    if (d < best.dist) best = { dist: d, seg: i, t };
  }
  let remaining = (1 - best.t) * horizontal(points[best.seg], points[best.seg + 1]);
  for (let i = best.seg + 1; i + 1 < points.length; i++) remaining += horizontal(points[i], points[i + 1]);
  const nextIndex = best.t >= 1 ? Math.min(best.seg + 2, points.length - 1) : best.seg + 1;
  return { nextIndex, remainingMeters: remaining, offRouteMeters: best.dist, from: device };
}

/** Route progress for this frame: recomputed only while localized; otherwise the previous progress, unchanged. */
export function advanceProgress(previous: RouteProgress | null, localized: boolean, points: readonly Vec3[], device: Vec3 | null): RouteProgress | null {
  if (!localized || device === null) return previous;
  return progressAlong(points, device);
}
