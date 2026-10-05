// Marker observations from WebXR image tracking (docs/ar.md, "Android: WebXR"). This is the only marker detection the
// client performs, and every value comes from the platform: which registered image (its index), its pose in the AR
// world frame, the frame's timestamp, and the platform's tracking state for it. Nothing is inferred from a hit test,
// and nothing is synthesized.
//
// The WebXR Marker Tracking API is an incubation and not in @types/webxr, so its shapes are declared here, narrowly:
//   requestSession("immersive-ar", { requiredFeatures: ["image-tracking"], trackedImages: [{ image, widthInMeters }] })
//   XRSession.getTrackedImageScores(): Promise<("untrackable" | "trackable")[]>
//   XRFrame.getImageTrackingResults(): XRImageTrackingResult[]
//     { index, imageSpace, trackingState: "tracked" | "emulated", measuredWidthInMeters }
// "emulated" means the platform is extrapolating an image it is not currently seeing; it is not a measurement.

import type { Pose } from "./ar-anchor-math.ts";

export interface XrTrackedImageInit {
  image: ImageBitmap;
  widthInMeters: number;
}

export interface XrImageTrackingResultLike {
  readonly index: number;
  readonly imageSpace: unknown;
  readonly trackingState: "tracked" | "emulated";
  readonly measuredWidthInMeters: number;
}

/** One registered marker the session tracks: `index` is its position in the session's trackedImages. */
export interface TrackedMarker {
  index: number;
  anchorId: string;
  /** The floor the anchor is registered on: a session tracks every floor's markers, and uses only the current floor's. */
  floorId?: string;
  /** The scan version the anchor's pose was entered against. */
  scanVersionId?: string;
  /** The anchor's registered canonical pose (its marker's centre), for the drift check (lib/ar-navigation.ts). */
  digitalPose?: Pose;
  markerIdentifier: string;
  widthInMeters: number;
}

/** A detection of a registered marker. `observedPose` is the marker's pose in the AR world frame (the session's "local"
 * reference space: metres, gravity-aligned, +Y up), which is what POST .../anchors/relocalize expects. */
export interface MarkerObservation {
  anchorId: string;
  floorId?: string;
  markerType: "IMAGE_TARGET";
  markerIdentifier: string;
  observedPose: Pose;
  timestampMs: number;
  trackingState: "tracked" | "emulated";
  measuredWidthMeters: number;
  registeredWidthMeters: number;
}

/** Turns one frame's image-tracking results into observations of registered markers. `poseOf` resolves an image space
 * to its pose in the AR world frame (XRFrame.getPose(imageSpace, localSpace)); a null pose is no observation. A result
 * whose index is not one of `markers` is ignored: it cannot be attributed to a registered anchor. */
export function observeImages(
  results: readonly XrImageTrackingResultLike[],
  markers: readonly TrackedMarker[],
  poseOf: (space: unknown) => Pose | null,
  timestampMs: number,
): MarkerObservation[] {
  const byIndex = new Map(markers.map((m) => [m.index, m]));
  const out: MarkerObservation[] = [];
  for (const r of results) {
    const marker = byIndex.get(r.index);
    if (!marker) continue;
    const pose = poseOf(r.imageSpace);
    if (!pose) continue;
    out.push({
      anchorId: marker.anchorId,
      ...(marker.floorId === undefined ? {} : { floorId: marker.floorId }),
      markerType: "IMAGE_TARGET",
      markerIdentifier: marker.markerIdentifier,
      observedPose: pose,
      timestampMs,
      trackingState: r.trackingState,
      measuredWidthMeters: r.measuredWidthInMeters,
      registeredWidthMeters: marker.widthInMeters,
    });
  }
  return out;
}

/** How far the platform's measured width may differ from the registered printed width. A larger difference means the
 * wrong image, a mis-printed size, or a bad detection, and the observation would put the device in the wrong place. */
export const WIDTH_TOLERANCE = 0.2;

export function relocalizationEligibility(obs: MarkerObservation): { eligible: boolean; reason: string | null } {
  if (obs.trackingState !== "tracked") {
    return { eligible: false, reason: `${obs.markerIdentifier}: the platform is extrapolating it (emulated), not seeing it` };
  }
  if (!(obs.measuredWidthMeters > 0)) {
    return { eligible: false, reason: `${obs.markerIdentifier}: no measured width` };
  }
  const ratio = obs.measuredWidthMeters / obs.registeredWidthMeters;
  if (Math.abs(ratio - 1) > WIDTH_TOLERANCE) {
    return {
      eligible: false,
      reason: `${obs.markerIdentifier}: measured ${obs.measuredWidthMeters.toFixed(3)} m wide, registered ` +
        `${obs.registeredWidthMeters.toFixed(3)} m; check the printed size`,
    };
  }
  return { eligible: true, reason: null };
}

/** XR rigid transform -> Pose. */
export function poseFromXrTransform(t: {
  position: { x: number; y: number; z: number };
  orientation: { x: number; y: number; z: number; w: number };
}): Pose {
  return { x: t.position.x, y: t.position.y, z: t.position.z, qx: t.orientation.x, qy: t.orientation.y, qz: t.orientation.z, qw: t.orientation.w };
}
