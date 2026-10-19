"use client";

// WebXR depth -> obstacle-sensing.ts inputs (docs/navigation.md, "Camera obstacle sensing", Android). Device-only.
//
// The source is the WebXR Depth Sensing module ("depth-sensing", CPU-optimized), which Chrome for Android serves from the
// ARCore Depth API on devices that support it. The session asks for it as an OPTIONAL feature: a device without it
// still navigates, and the overlay says obstacle detection is unavailable. No camera image is read, kept or sent: only
// depth values at a sparse grid of points are sampled in the frame callback and dropped after the frame.
//
// NOT DEVICE-VALIDATED: this file can only run inside an immersive-ar session with depth on an ARCore Depth device. It is
// type-checked and built; its pure parts (unprojection, projection) are unit-tested in obstacle-sensing.test.ts. Assumed
// from the WebXR Depth Sensing spec, to be confirmed on a device (docs/ar-android-validation.md): getDepthInMeters takes
// normalized view coordinates with the origin at the top left, and returns distance along the view's -Z axis.

import { poseFromXrTransform } from "./ar-marker-tracking.ts";
import type { Pose } from "./ar-anchor-math.ts";
import type { Vec3 } from "./coordinate-frame.ts";
import { type DepthFrame, type DepthSample, invert4, projectNormalized, unprojectNormalized } from "./obstacle-sensing.ts";

interface CpuDepthInformation {
  width: number;
  height: number;
  getDepthInMeters(x: number, y: number): number;
}
type DepthFrameApi = XRFrame & { getDepthInformation?: (view: XRView) => CpuDepthInformation | null | undefined };
type DepthSessionApi = XRSession & { depthUsage?: string };

export type WebXrDepth =
  | { ok: true; depth: DepthFrame; camera: Pose; depthAlong: (cameraPoint: Vec3) => number | null }
  | { ok: false; problem: string };

/** Whether this session can give CPU depth at all (the browser granted the optional feature). */
export function depthAvailability(session: XRSession): { available: true } | { available: false; reason: string } {
  if (!(session.enabledFeatures ?? []).includes("depth-sensing")) {
    return { available: false, reason: "this device or browser provides no depth (ARCore Depth API / WebXR depth-sensing)" };
  }
  const usage = (session as DepthSessionApi).depthUsage;
  if (usage !== undefined && usage !== "cpu-optimized") {
    return { available: false, reason: `the browser granted ${usage} depth, which cannot be read on the CPU` };
  }
  return { available: true };
}

/** Samples a cols x rows grid of this frame's depth (the first view). */
export function sampleWebXrDepth(frame: XRFrame, viewer: XRViewerPose, timeMs: number, cols: number, rows: number): WebXrDepth {
  const view = viewer.views[0];
  if (!view) return { ok: false, problem: "the frame has no view" };
  let info: CpuDepthInformation | null | undefined;
  try {
    info = (frame as DepthFrameApi).getDepthInformation?.(view);
  } catch (e) {
    return { ok: false, problem: `depth could not be read: ${e instanceof Error ? e.message : String(e)}` };
  }
  if (!info) return { ok: false, problem: "no depth for this frame (the platform has not produced depth yet)" };
  const inverse = invert4(view.projectionMatrix);
  if (!inverse) return { ok: false, problem: "the view's projection cannot be inverted" };
  const samples: DepthSample[] = [];
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      const u = (c + 0.5) / cols, v = (r + 0.5) / rows;
      const d = info.getDepthInMeters(u, v);
      const point = unprojectNormalized(u, v, d, inverse);
      // ARCore's CPU depth has no per-pixel confidence in WebXR: a zero depth is "no measurement".
      if (point) samples.push({ u, v, point, confidence: null });
    }
  }
  const depthInfo = info;
  const projection = view.projectionMatrix;
  return {
    ok: true,
    depth: { source: "ARCORE_DEPTH", frameTimeMs: timeMs, samples },
    camera: poseFromXrTransform(view.transform),
    depthAlong: (p) => {
      const at = projectNormalized(p, projection);
      if (!at) return null;
      const d = depthInfo.getDepthInMeters(at.u, at.v);
      if (!(d > 0) || !(p[2] < 0)) return null;
      return d * Math.hypot(p[0], p[1], p[2]) / -p[2]; // distance along -Z -> range along the ray
    },
  };
}
