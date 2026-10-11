import assert from "node:assert/strict";
import { test } from "node:test";
import { MAX_IMPORT_SAMPLES, isVideoFile, parseTrajectory } from "./trajectory-import.ts";

const frame = () => "import-1";
const NOW = 2_000_000;

test("a video file is refused with the reason, whatever its contents", () => {
  assert.ok(isVideoFile("walk.MP4", ""));
  assert.ok(isVideoFile("x.bin", "video/quicktime"));
  const r = parseTrajectory("walk.mp4", "video/mp4", "{}", NOW, frame);
  assert.equal(r.ok, false);
  assert.match(!r.ok ? r.errors[0] : "", /no camera positions/);
});

test("JSON: samples become operator-trajectory positions in the file's frame, timed from startedAtMs", () => {
  const r = parseTrajectory("lap.json", "application/json", JSON.stringify({
    units: "m", frameId: "total-station-7", startedAtMs: 1_000,
    samples: [{ t: 0, x: 0, y: 0, yawDegrees: 90 }, { t: 0.5, x: 0.5, y: 0 }, { t: 1, x: 1, y: 0 }],
    surfaces: [{ t: 0.5, originX: 0.5, originY: 0, hitX: 0.5, hitY: 3, kind: "WALL", normalDegrees: -90 }],
  }), NOW, frame);
  assert.ok(r.ok);
  if (!r.ok) return;
  assert.equal(r.value.frameId, "total-station-7");
  assert.deepEqual(r.value.samples[0], { capturedAtMs: 1_000, x: 0, y: 0, yawDegrees: 90, source: "operator-trajectory",
    frameId: "total-station-7", frameAlignment: "NATIVE" });
  assert.equal(r.value.samples[2].capturedAtMs, 2_000);
  assert.equal(r.value.pathLengthMeters, 1);
  assert.equal(r.value.surfaces[0].capturedAtMs, 1_500);
  assert.equal(r.value.surfaces[0].source, "operator-trajectory");
  assert.deepEqual(r.value.warnings, []);
});

test("JSON: units must be stated as metres, values finite, and time increasing", () => {
  const noUnits = parseTrajectory("a.json", "", JSON.stringify({ samples: [{ t: 0, x: 0, y: 0 }] }), NOW, frame);
  assert.ok(!noUnits.ok && noUnits.errors[0].includes("units"));
  const feet = parseTrajectory("a.json", "", JSON.stringify({ units: "ft", samples: [{ t: 0, x: 0, y: 0 }] }), NOW, frame);
  assert.ok(!feet.ok);
  const bad = parseTrajectory("a.json", "", JSON.stringify({ units: "m", samples: [{ t: 0, x: 0, y: "1" }, { t: 1, x: 0, y: 0 }, { t: 1, x: 1, y: 0 }] }), NOW, frame);
  assert.ok(!bad.ok);
  if (!bad.ok) {
    assert.ok(bad.errors.some((e) => e.includes("sample 1")));
    assert.ok(bad.errors.some((e) => e.includes("time does not increase")));
  }
  assert.ok(!parseTrajectory("a.json", "", "{nope", NOW, frame).ok);
  const many = { units: "m", samples: Array.from({ length: MAX_IMPORT_SAMPLES + 1 }, (_, i) => ({ t: i, x: 0, y: 0 })) };
  assert.ok(!parseTrajectory("a.json", "", JSON.stringify(many), NOW, frame).ok);
});

test("CSV: a units line and a t,x,y header; without startedAtMs the walk is placed as just ended, and that is stated", () => {
  const r = parseTrajectory("lap.csv", "text/csv", "# units: m\nt,x,y,yawDegrees\n0,0,0,0\n1,1,0,\n2,1,1,90\n", NOW, frame);
  assert.ok(r.ok);
  if (!r.ok) return;
  assert.equal(r.value.samples.length, 3);
  assert.equal(r.value.samples[1].yawDegrees, null);
  assert.equal(r.value.samples[2].capturedAtMs, NOW);
  assert.equal(r.value.frameId, "import-1");
  assert.match(r.value.warnings[0], /just ended/);
  const missing = parseTrajectory("lap.csv", "", "t,x,y\n0,0,0\n", NOW, frame);
  assert.ok(!missing.ok && missing.errors[0].includes("# units: m"));
  const header = parseTrajectory("lap.csv", "", "# units: m\ntime,east,north\n0,0,0\n", NOW, frame);
  assert.ok(!header.ok && header.errors[0].includes("header"));
});
