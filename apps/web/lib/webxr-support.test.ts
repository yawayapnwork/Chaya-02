import assert from "node:assert/strict";
import { test } from "node:test";
import { anchorSupport, classifySessionError, detectArEnvironment, trackableAnchors } from "./webxr-support.ts";

const IMAGE_TRACKING = { XRImageTrackingResult: function XRImageTrackingResult() {} };
const FULL = { ...IMAGE_TRACKING, XRAnchor: function XRAnchor() {} };
const supported = { xr: { isSessionSupported: async () => true } };

test("no navigator.xr (desktop Chrome, most dev machines) is WEBXR_UNAVAILABLE, never a simulated AR view", async () => {
  const r = await detectArEnvironment({}, {});
  assert.equal(r.problem, "WEBXR_UNAVAILABLE");
});

test("navigator.xr without immersive-ar is IMMERSIVE_AR_UNSUPPORTED", async () => {
  const r = await detectArEnvironment({ xr: { isSessionSupported: async () => false } }, IMAGE_TRACKING);
  assert.equal(r.problem, "IMMERSIVE_AR_UNSUPPORTED");
});

test("a throwing capability check is reported, not thrown", async () => {
  const r = await detectArEnvironment({ xr: { isSessionSupported: async () => { throw new Error("boom"); } } }, IMAGE_TRACKING);
  assert.equal(r.problem, "CAPABILITY_CHECK_FAILED");
  assert.equal(r.detail, "boom");
});

test("immersive-ar without the image-tracking interfaces is IMAGE_TRACKING_UNSUPPORTED (stock Chrome without the incubation flag)", async () => {
  const r = await detectArEnvironment(supported, {});
  assert.equal(r.problem, "IMAGE_TRACKING_UNSUPPORTED");
  assert.equal(r.imageTrackingExposed, false);
});

test("image tracking without WebXR anchors is ANCHORS_UNSUPPORTED (the required world-anchor capability)", async () => {
  const r = await detectArEnvironment(supported, IMAGE_TRACKING);
  assert.equal(r.problem, "ANCHORS_UNSUPPORTED");
  assert.equal(r.anchorsExposed, false);
});

test("a camera permission already denied is CAMERA_PERMISSION_DENIED", async () => {
  const r = await detectArEnvironment({ ...supported, permissions: { query: async () => ({ state: "denied" }) } }, FULL);
  assert.equal(r.problem, "CAMERA_PERMISSION_DENIED");
});

test("everything present, and a Permissions API that does not know 'camera', is ready", async () => {
  const r = await detectArEnvironment({ ...supported, permissions: { query: async () => { throw new TypeError("unknown name"); } } }, FULL);
  assert.equal(r.problem, null);
  assert.equal(r.imageTrackingExposed, true);
  assert.equal(r.anchorsExposed, true);
});

test("session request failures map to named states", () => {
  const err = (name: string) => Object.assign(new Error(name), { name });
  assert.equal(classifySessionError(err("NotAllowedError")).problem, "CAMERA_PERMISSION_DENIED");
  assert.equal(classifySessionError(err("NotSupportedError")).problem, "REQUIRED_FEATURE_UNSUPPORTED");
  assert.equal(classifySessionError(err("InvalidStateError")).problem, "SESSION_FAILED");
});

test("only calibrated IMAGE_TARGET anchors with a printed size are detectable; fiducials are never", () => {
  const base = { calibrationStatus: "CALIBRATED" as const, markerSizeMeters: 0.3, scanVersionId: "sv-1", poseId: "pose-1" };
  assert.equal(anchorSupport({ ...base, markerType: "IMAGE_TARGET" }), "TRACKABLE");
  for (const t of ["APRILTAG", "ARUCO_MARKER", "QR_CODE"] as const) {
    assert.equal(anchorSupport({ ...base, markerType: t }), "FIDUCIAL_DETECTION_UNSUPPORTED");
  }
  assert.equal(anchorSupport({ ...base, markerType: "IMAGE_TARGET", calibrationStatus: "STALE" }), "NOT_CALIBRATED");
  assert.equal(anchorSupport({ ...base, markerType: "IMAGE_TARGET", markerSizeMeters: null }), "MISSING_PRINTED_SIZE");
  const anchors = [{ ...base, markerType: "APRILTAG" as const }, { ...base, markerType: "IMAGE_TARGET" as const }];
  assert.equal(trackableAnchors(anchors).length, 1);
});

test("an anchor whose pose was never entered against a scan version is not registered for tracking", () => {
  const base = { markerType: "IMAGE_TARGET" as const, calibrationStatus: "CALIBRATED" as const, markerSizeMeters: 0.3 };
  assert.equal(anchorSupport({ ...base, scanVersionId: null, poseId: null }), "UNVERSIONED");
  assert.equal(anchorSupport({ ...base, scanVersionId: "sv-1", poseId: null }), "UNVERSIONED");
  assert.equal(anchorSupport({ ...base }), "UNVERSIONED");
  assert.equal(anchorSupport({ ...base, scanVersionId: "sv-1", poseId: "pose-1" }), "TRACKABLE");
});
