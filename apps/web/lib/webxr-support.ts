// Capability detection for the Android WebXR AR client (docs/ar.md, "Android: WebXR"). Every check reads a real,
// present browser API or a real session outcome. Nothing here falls back to a simulated AR view, and no capability is
// assumed. Each missing piece is an explicit, named state the UI shows.
//
// What WebXR on Android Chrome (ARCore devices) actually offers, and what this client uses:
//   * immersive-ar sessions and the "local" reference space: shipped. Used: the AR world frame.
//   * hit-test: shipped. NOT used for localization. A hit test finds a real surface (a plane) under a ray; it says
//     nothing about which registered marker is where.
//   * anchors (XRAnchor): shipped. World anchors keep a point stable as the platform refines its map. They do not
//     identify anything, so they are not marker detection. Used (required): after a relocalization, a world anchor is
//     created at the observed marker's pose, and the route is kept attached to it, so platform map corrections move
//     the route with the room. Losing that anchor is a tracking loss.
//   * dom-overlay: shipped. Used, optionally, for the in-session status UI.
//   * image-tracking (WebXR Marker Tracking incubation): Chrome exposes it only with chrome://flags/#webxr-incubations
//     enabled. It reports, per registered image, its index, pose, tracking state and measured width. This is the only
//     marker detection WebXR provides, and the only one this client uses (IMAGE_TARGET anchors).
//   * AprilTag / ArUco / QR fiducials: no WebXR API detects them. Anchors of those types are reported as
//     FIDUCIAL_DETECTION_UNSUPPORTED on this client; nothing pretends to detect them.

import type { Anchor } from "./ar-api.ts";

export type ArProblem =
  | "WEBXR_UNAVAILABLE"
  | "IMMERSIVE_AR_UNSUPPORTED"
  | "CAPABILITY_CHECK_FAILED"
  | "IMAGE_TRACKING_UNSUPPORTED"
  | "ANCHORS_UNSUPPORTED"
  | "REQUIRED_FEATURE_UNSUPPORTED"
  | "CAMERA_PERMISSION_DENIED"
  | "NO_DETECTABLE_ANCHORS"
  | "NO_TRACKABLE_MARKERS"
  | "SESSION_FAILED";

export const PROBLEM_TEXT: Record<ArProblem, string> = {
  WEBXR_UNAVAILABLE: "This browser does not expose WebXR (navigator.xr). AR needs Chrome on an ARCore-capable Android device, over HTTPS.",
  IMMERSIVE_AR_UNSUPPORTED: "WebXR is present, but this device or browser reports immersive AR sessions as unsupported.",
  CAPABILITY_CHECK_FAILED: "The browser failed while reporting its WebXR capabilities.",
  IMAGE_TRACKING_UNSUPPORTED:
    "This browser does not provide WebXR image tracking, the only marker detection WebXR offers. On Chrome for Android it " +
    "is an incubation: enable chrome://flags/#webxr-incubations and restart Chrome. Without it this device cannot identify a " +
    "registered marker, so it cannot be localized in the venue.",
  ANCHORS_UNSUPPORTED:
    "This browser does not provide WebXR anchors (XRAnchor), which keep the route fixed in the room as the device refines " +
    "its map. Chrome for Android on an ARCore device provides them.",
  REQUIRED_FEATURE_UNSUPPORTED:
    "The device refused a required WebXR feature (image-tracking or anchors) when the AR session was requested. The " +
    "browser does not say which one. On Chrome for Android, check chrome://flags/#webxr-incubations is enabled.",
  CAMERA_PERMISSION_DENIED: "Camera access was denied. AR needs the camera; allow it for this site in Chrome's site settings and retry.",
  NO_DETECTABLE_ANCHORS:
    "This floor has no calibrated IMAGE_TARGET anchor with a printed size, the only marker type this device can detect.",
  NO_TRACKABLE_MARKERS: "The platform rated every registered marker image untrackable (not enough visual texture).",
  SESSION_FAILED: "The AR session could not be started.",
};

export interface EnvironmentReport {
  /** null when AR can be attempted; otherwise why not. */
  problem: ArProblem | null;
  detail: string;
  /** The WebXR image-tracking interfaces are exposed (a session request still has to confirm the feature). */
  imageTrackingExposed: boolean;
  /** The WebXR anchors interface (XRAnchor) is exposed. */
  anchorsExposed: boolean;
}

interface XrSystemLike {
  isSessionSupported(mode: string): Promise<boolean>;
}

interface NavigatorLike {
  xr?: XrSystemLike;
  permissions?: { query(descriptor: { name: string }): Promise<{ state: string }> };
}

/** Pre-session checks, from the real navigator/window. `nav` and `win` are injectable for tests. */
export async function detectArEnvironment(
  nav: NavigatorLike | undefined = (globalThis as { navigator?: NavigatorLike }).navigator,
  win: Record<string, unknown> = globalThis as unknown as Record<string, unknown>,
): Promise<EnvironmentReport> {
  const imageTrackingExposed = typeof win.XRImageTrackingResult === "function";
  const anchorsExposed = typeof win.XRAnchor === "function";
  const report = (problem: ArProblem | null, detail: string = problem ? PROBLEM_TEXT[problem] : ""): EnvironmentReport =>
    ({ problem, detail, imageTrackingExposed, anchorsExposed });
  const xr = nav?.xr;
  if (!xr) return report("WEBXR_UNAVAILABLE");
  try {
    if (!(await xr.isSessionSupported("immersive-ar"))) return report("IMMERSIVE_AR_UNSUPPORTED");
  } catch (e) {
    return report("CAPABILITY_CHECK_FAILED", e instanceof Error ? e.message : String(e));
  }
  if (!imageTrackingExposed) return report("IMAGE_TRACKING_UNSUPPORTED");
  if (!anchorsExposed) return report("ANCHORS_UNSUPPORTED");
  try {
    const camera = await nav?.permissions?.query({ name: "camera" });
    if (camera?.state === "denied") return report("CAMERA_PERMISSION_DENIED");
  } catch {
    // The Permissions API may not know "camera"; the session request is the authoritative check.
  }
  return report(null);
}

/** Maps a failed requestSession() to a named state. WebXR rejects with NotAllowedError when camera/XR permission is
 * refused, and NotSupportedError when a required feature ("image-tracking" or "anchors") is unavailable, without saying
 * which one. */
export function classifySessionError(error: unknown): { problem: ArProblem; detail: string } {
  const name = (error as { name?: string } | null)?.name;
  const message = error instanceof Error ? error.message : String(error);
  if (name === "NotAllowedError" || name === "SecurityError") {
    return { problem: "CAMERA_PERMISSION_DENIED", detail: `${PROBLEM_TEXT.CAMERA_PERMISSION_DENIED} (${message})` };
  }
  if (name === "NotSupportedError") {
    return { problem: "REQUIRED_FEATURE_UNSUPPORTED", detail: `${PROBLEM_TEXT.REQUIRED_FEATURE_UNSUPPORTED} (${message})` };
  }
  return { problem: "SESSION_FAILED", detail: `${PROBLEM_TEXT.SESSION_FAILED} ${message}` };
}

export type AnchorSupport = "TRACKABLE" | "FIDUCIAL_DETECTION_UNSUPPORTED" | "MISSING_PRINTED_SIZE" | "NOT_CALIBRATED" | "UNVERSIONED";

/** What this client can do with each registered anchor. Only calibrated IMAGE_TARGET anchors with a printed size can be
 * detected; QR/ArUco/AprilTag have no WebXR detector and are never treated as detected. */
export function anchorSupport(
  anchor: Pick<Anchor, "markerType" | "calibrationStatus" | "markerSizeMeters"> & Partial<Pick<Anchor, "scanVersionId" | "poseId">>,
): AnchorSupport {
  if (anchor.markerType !== "IMAGE_TARGET") return "FIDUCIAL_DETECTION_UNSUPPORTED";
  if (anchor.calibrationStatus !== "CALIBRATED") return "NOT_CALIBRATED";
  if (!(anchor.markerSizeMeters && anchor.markerSizeMeters > 0)) return "MISSING_PRINTED_SIZE";
  // A pose never entered against a scan version cannot be tied to the reconstruction shown; the server refuses it
  // (ANCHOR_UNVERSIONED), so it is not registered for tracking at all.
  if (!anchor.scanVersionId || !anchor.poseId) return "UNVERSIONED";
  return "TRACKABLE";
}

export function trackableAnchors<
  T extends Pick<Anchor, "markerType" | "calibrationStatus" | "markerSizeMeters"> & Partial<Pick<Anchor, "scanVersionId" | "poseId">>,
>(anchors: T[]): T[] {
  return anchors.filter((a) => anchorSupport(a) === "TRACKABLE");
}
