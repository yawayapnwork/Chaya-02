import assert from "node:assert/strict";
import { test } from "node:test";
import {
  measuredFacts,
  outcomeText,
  type Room,
  roomState,
  type Stitch,
  stitchProblem,
  summarizeStitch,
  viewerHref,
} from "./stitch-view.ts";

const version = (n: number, status: string, extra: Partial<Room["latestVersion"] & object> = {}) => ({
  id: `v${n}`, versionNumber: n, status, pipelineRunId: `run-${n}`, runStatus: status === "FINALIZED" ? "SUCCEEDED" : "RUNNING",
  coordinateFrameId: status === "FINALIZED" ? "f" : null, finalizedAt: null, createdAt: "2026-10-18T10:00:00Z", awaitingCalibration: false,
  ...extra,
});

const room = (over: Partial<Room>): Room => ({
  id: "r1", floorId: "f1", name: "Kitchen", kind: "ROOM", createdAt: "x", latestVersion: null, latestFinalizedVersion: null,
  latestCaptureId: null, latestCaptureStatus: null, ...over,
});

test("a room is ready to stitch only once a version is finalized, and says what to do before that", () => {
  assert.equal(roomState(room({})).state, "NOT_CAPTURED");
  assert.equal(roomState(room({ latestCaptureId: "c", latestCaptureStatus: "UPLOADING" })).label, "Capture uploading");
  assert.equal(roomState(room({ latestVersion: version(1, "DRAFT") })).state, "PROCESSING");
  const awaiting = roomState(room({ latestVersion: version(1, "DRAFT", { runStatus: "SUCCEEDED", awaitingCalibration: true }) }));
  assert.equal(awaiting.state, "AWAITING_CALIBRATION");
  assert.match(awaiting.label, /calibrate/);
  assert.equal(roomState(room({ latestVersion: version(1, "DRAFT", { runStatus: "FAILED" }) })).tone, "error");
  const ready = roomState(room({ latestVersion: version(2, "FINALIZED"), latestFinalizedVersion: version(2, "FINALIZED") }));
  assert.deepEqual(ready, { state: "READY", label: "Ready: v2", tone: "success" });
});

test("a finalized room stays ready while a newer capture of it is processing or fails", () => {
  const newer = roomState(room({ latestVersion: version(3, "DRAFT"), latestFinalizedVersion: version(2, "FINALIZED") }));
  assert.equal(newer.state, "READY");
  assert.equal(newer.label, "Ready: v2; newest capture processing");
  const failed = roomState(room({ latestVersion: version(3, "DRAFT", { runStatus: "FAILED" }), latestFinalizedVersion: version(2, "FINALIZED") }));
  assert.equal(failed.state, "READY");
  assert.equal(failed.tone, "warning");
});

test("a stitch needs two finalized rooms and an anchor among them", () => {
  const a = room({ id: "a", name: "A", latestFinalizedVersion: version(1, "FINALIZED") });
  const b = room({ id: "b", name: "B", latestFinalizedVersion: version(2, "FINALIZED") });
  const c = room({ id: "c", name: "C" });
  assert.match(stitchProblem([a, b], ["a"], "a")!, /at least two/);
  assert.match(stitchProblem([a, b, c], ["a", "c"], "a")!, /Not finalized yet: C/);
  assert.match(stitchProblem([a, b], ["a", "b"], null)!, /anchor/);
  assert.match(stitchProblem([a, b], ["a", "b"], "c")!, /anchor/);
  assert.equal(stitchProblem([a, b], ["a", "b"], "b"), null);
});

const run = (status: string, over: Record<string, unknown> = {}) => ({
  id: "run", status, failureStage: null, failureCode: null, failureMessage: null, retryable: false,
  stages: [{ stage: "ROOM_REGISTRATION", state: "SUCCEEDED" }, { stage: "ROOM_STITCH", state: "RUNNING" },
    { stage: "PLANE_FITTING", state: "PENDING" }],
  ...over,
}) as Stitch["run"];

const stitch = (over: Partial<Stitch>): Stitch => ({
  id: "s", floorId: "f1", versionNumber: 4, status: "DRAFT", current: false, captureId: "c", scanId: "sc", run: run("RUNNING"),
  anchorRoomId: "a", rooms: [], pairs: [], registration: null, stitch: null, connectivity: null, createdAt: "x", finalizedAt: null,
  createdBy: null, ...over,
});

test("a running stitch shows its progress and the stage it is on", () => {
  const s = summarizeStitch(stitch({}));
  assert.equal(s.badge, "Stitching");
  assert.equal(s.progress, "1 of 3 stages");
  assert.equal(s.headline, "Now: room stitch");
  assert.equal(s.viewable, false);
});

test("a stitch is shown as published only while the floor publishes it", () => {
  assert.equal(summarizeStitch(stitch({ status: "FINALIZED", current: true, run: run("SUCCEEDED") })).badge, "Published");
  const old = summarizeStitch(stitch({ status: "FINALIZED", current: false, run: run("SUCCEEDED") }));
  assert.equal(old.badge, "Superseded");
  assert.equal(old.viewable, true);
  const refused = summarizeStitch(stitch({ status: "DRAFT", run: run("SUCCEEDED", { failureMessage: "the anchor was recalibrated" }) }));
  assert.equal(refused.badge, "Not published");
  assert.equal(refused.viewable, false);
});

test("a rejected registration names every room it could not place, with the reason, and offers no retry", () => {
  const s = summarizeStitch(stitch({
    run: run("FAILED", { failureStage: "ROOM_REGISTRATION", failureCode: "REGISTRATION_REJECTED", retryable: false }),
    rooms: [
      { roomId: "a", name: "A", roomVersionId: "va", roomVersionNumber: 1, coordinateFrameId: "fa", anchor: true, registered: true, reason: null },
      { roomId: "b", name: "B", roomVersionId: "vb", roomVersionNumber: 2, coordinateFrameId: "fb", anchor: false, registered: false,
        reason: "no accepted registration links it to the fixed rooms" },
    ],
  }));
  assert.equal(s.badge, "Rejected");
  assert.match(s.headline, /not every room could be placed/);
  assert.deepEqual(s.unregistered, [{ name: "B", reason: "no accepted registration links it to the fixed rooms" }]);
  assert.equal(s.canRetry, false);
  const transient = summarizeStitch(stitch({ run: run("FAILED", { failureStage: "ROOM_STITCH", failureCode: "WORKER_LOST", retryable: true }) }));
  assert.equal(transient.badge, "Failed");
  assert.equal(transient.canRetry, true);
});

test("rooms placed by a single registration are named as unconfirmed, the anchor never", () => {
  const s = summarizeStitch(stitch({
    status: "FINALIZED", current: true, run: run("SUCCEEDED"), allowUnconfirmedRooms: true,
    rooms: [
      { roomId: "a", name: "A", roomVersionId: "va", roomVersionNumber: 1, coordinateFrameId: "fa", anchor: true, registered: true,
        reason: null, loopConfirmed: true },
      { roomId: "b", name: "B", roomVersionId: "vb", roomVersionNumber: 1, coordinateFrameId: "fb", anchor: false, registered: true,
        reason: null, loopConfirmed: false },
    ],
  }));
  assert.deepEqual(s.unconfirmed, ["B"]);
  assert.deepEqual(summarizeStitch(stitch({})).unconfirmed, []);
});

test("pair outcomes are explained in words", () => {
  assert.equal(outcomeText("REGISTERED"), "registered");
  assert.match(outcomeText("FREE_SPACE_CONFLICT"), /empty space/);
  assert.match(outcomeText("SCALE_INCONSISTENT"), /scale/);
  assert.equal(outcomeText("SOMETHING_NEW"), "something new");
});

test("only measured facts are shown, and nothing when nothing was measured", () => {
  assert.deepEqual(measuredFacts(stitch({})), []);
  const facts = measuredFacts(stitch({
    stitch: { duplicate_fraction_after: 0.0035, pairs: [{ pieces: ["A", "B"], offset_m: 0.004 }], colour_seams_visible: [["A", "B"]] },
    connectivity: { connected: true, step_free_connected: false, clear_widths: [{ rooms: ["A", "B"], clear_width_m: 0.7, accessible_width: false }] },
  }));
  assert.deepEqual(facts, [
    "Doubled surfaces left after merging: 0.35 % of Gaussians",
    "Overlap A / B: 0.4 cm residual offset",
    "Visible colour difference between 1 pair(s) of rooms (exposure); geometry is unaffected",
    "Navigation: every room can be reached from every other",
    "Step-free navigation does not reach every room",
    "1 pair(s) of rooms are joined only by a way narrower than the accessible width",
  ]);
});

test("the viewer link opens the version's run", () => {
  assert.equal(viewerHref("v1", "f1", "run-9"), "/viewer?venue=v1&floor=f1&run=run-9");
});
