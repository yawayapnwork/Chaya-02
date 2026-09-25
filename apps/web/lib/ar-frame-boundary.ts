// The boundary between an AR device's tracking frame and the Chaya canonical venue frame (docs/coordinate-frames.md,
// "AR boundary"). Mirrors dev.chaya.api.ar.ArDeviceFrame.
//
// WebXR ("local" reference space) and ARKit report poses in a right-handed, metre-scaled, gravity-aligned frame with
// +Y up. The canonical frame is right-handed, metres, +Z up. The fixed rotation between the two conventions is +90
// degrees about X: device (x, y, z) -> canonical axes (x, -z, y). A relocalization transform maps device coordinates
// to canonical ones; since both frames are gravity-aligned and metric it must send device +Y to canonical +Z, which
// gravityTiltDegrees measures. Poses sent to the server stay in the device's own convention; the server applies the
// boundary, so the conversion is written in exactly one place per side.

import { type Quaternion, type Vec3, conjugate, rotate } from "./coordinate-frame.ts";
import type { Pose } from "./ar-anchor-math.ts";

export const DEVICE_FRAME_CONVENTION = "DEVICE_Y_UP_RIGHT_HANDED_METRES";

export const DEVICE_TO_CANONICAL_AXES: Quaternion = { w: Math.SQRT1_2, x: Math.SQRT1_2, y: 0, z: 0 };

export function deviceAxesToCanonical(v: Vec3): Vec3 {
  return rotate(DEVICE_TO_CANONICAL_AXES, v);
}

export function canonicalAxesToDevice(v: Vec3): Vec3 {
  return rotate(conjugate(DEVICE_TO_CANONICAL_AXES), v);
}

/** Canonical venue point -> device tracking coordinates, given the server's device-to-venue transform (rigid). */
export function venuePointToDevice(deviceToVenue: Pose, p: Vec3): Vec3 {
  const inv = conjugate({ w: deviceToVenue.qw, x: deviceToVenue.qx, y: deviceToVenue.qy, z: deviceToVenue.qz });
  return rotate(inv, [p[0] - deviceToVenue.x, p[1] - deviceToVenue.y, p[2] - deviceToVenue.z]);
}

/** Device tracking point -> canonical venue metres. */
export function devicePointToVenue(deviceToVenue: Pose, p: Vec3): Vec3 {
  const r = rotate({ w: deviceToVenue.qw, x: deviceToVenue.qx, y: deviceToVenue.qy, z: deviceToVenue.qz }, p);
  return [r[0] + deviceToVenue.x, r[1] + deviceToVenue.y, r[2] + deviceToVenue.z];
}

/** Angle, in degrees, between where the transform sends the device's up (+Y) and canonical +Z. */
export function gravityTiltDegrees(deviceToVenue: Pose): number {
  const up = rotate({ w: deviceToVenue.qw, x: deviceToVenue.qx, y: deviceToVenue.qy, z: deviceToVenue.qz }, [0, 1, 0]);
  return (Math.acos(Math.max(-1, Math.min(1, up[2] / Math.hypot(...up)))) * 180) / Math.PI;
}
