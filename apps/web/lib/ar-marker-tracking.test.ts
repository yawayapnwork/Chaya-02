import assert from "node:assert/strict";
import { test } from "node:test";
import { type TrackedMarker, observeImages, poseFromXrTransform, relocalizationEligibility } from "./ar-marker-tracking.ts";

const MARKERS: TrackedMarker[] = [
  { index: 0, anchorId: "a-entrance", markerIdentifier: "entrance", widthInMeters: 0.3 },
  { index: 1, anchorId: "a-stairs", markerIdentifier: "stairs", widthInMeters: 0.5 },
];
const POSE = { x: 1, y: 0.2, z: -2, qx: 0, qy: 0, qz: 0, qw: 1 };

test("an image-tracking result becomes an observation of exactly the anchor registered at that index", () => {
  const obs = observeImages(
    [{ index: 1, imageSpace: "space-1", trackingState: "tracked", measuredWidthInMeters: 0.49 }],
    MARKERS, (space) => (space === "space-1" ? POSE : null), 5000,
  );
  assert.equal(obs.length, 1);
  assert.deepEqual(obs[0], {
    anchorId: "a-stairs", markerType: "IMAGE_TARGET", markerIdentifier: "stairs", observedPose: POSE, timestampMs: 5000,
    trackingState: "tracked", measuredWidthMeters: 0.49, registeredWidthMeters: 0.5,
  });
});

test("a result for an unregistered index, or without a pose, is no observation", () => {
  const obs = observeImages(
    [{ index: 7, imageSpace: "x", trackingState: "tracked", measuredWidthInMeters: 0.3 },
      { index: 0, imageSpace: "no-pose", trackingState: "tracked", measuredWidthInMeters: 0.3 }],
    MARKERS, () => null, 1,
  );
  assert.deepEqual(obs, []);
});

test("an emulated (extrapolated) marker is not a measurement and cannot relocalize", () => {
  const [o] = observeImages([{ index: 0, imageSpace: "s", trackingState: "emulated", measuredWidthInMeters: 0.3 }], MARKERS, () => POSE, 1);
  const e = relocalizationEligibility(o);
  assert.equal(e.eligible, false);
  assert.match(e.reason!, /emulated/);
});

test("a measured width that disagrees with the printed size is rejected", () => {
  const [ok] = observeImages([{ index: 0, imageSpace: "s", trackingState: "tracked", measuredWidthInMeters: 0.33 }], MARKERS, () => POSE, 1);
  assert.equal(relocalizationEligibility(ok).eligible, true);
  const [bad] = observeImages([{ index: 0, imageSpace: "s", trackingState: "tracked", measuredWidthInMeters: 0.6 }], MARKERS, () => POSE, 1);
  assert.equal(relocalizationEligibility(bad).eligible, false);
  assert.match(relocalizationEligibility(bad).reason!, /printed size/);
});

test("poseFromXrTransform copies position and orientation", () => {
  assert.deepEqual(poseFromXrTransform({ position: { x: 1, y: 2, z: 3 }, orientation: { x: 0, y: 0, z: 0.6, w: 0.8 } }),
    { x: 1, y: 2, z: 3, qx: 0, qy: 0, qz: 0.6, qw: 0.8 });
});
