import assert from "node:assert/strict";
import { test } from "node:test";
import {
  type MeasurementDraft,
  type ObservableMedia,
  calibrationBadge,
  captureStageBadge,
  checkMeasurementDraft,
  clickToPixel,
  measurementBody,
} from "./calibration.ts";

// Synthetic drafts: ids and pixel positions are made up to exercise the rules. They are not measurements.
const media: ObservableMedia[] = [
  { id: "img1", kind: "IMAGE", status: "ACCEPTED", pixelWidth: 4032, pixelHeight: 3024 },
  { id: "img2", kind: "IMAGE", status: "ACCEPTED", pixelWidth: 4032, pixelHeight: 3024 },
  { id: "vid", kind: "VIDEO", status: "ACCEPTED", pixelWidth: null, pixelHeight: null },
  { id: "bad", kind: "IMAGE", status: "REJECTED", pixelWidth: null, pixelHeight: null },
];

function distance(overrides: Partial<MeasurementDraft> = {}): MeasurementDraft {
  return {
    kind: "DISTANCE",
    label: "door width",
    method: "TAPE",
    unit: "cm",
    value: 91.5,
    uncertainty: 0.5,
    datum: "",
    venue: null,
    observations: [
      { point: "A", mediaId: "img1", frameTimeSeconds: null, u: 100, v: 200 },
      { point: "B", mediaId: "img1", frameTimeSeconds: null, u: 900, v: 210 },
      { point: "A", mediaId: "vid", frameTimeSeconds: 3.5, u: 50, v: 60 },
      { point: "B", mediaId: "vid", frameTimeSeconds: 3.5, u: 700, v: 65 },
    ],
    ...overrides,
  };
}

test("a distance with two views per end passes and is sent with its unit, not converted", () => {
  assert.deepEqual(checkMeasurementDraft(distance(), media), []);
  const body = measurementBody(distance());
  assert.equal(body.unit, "cm");
  assert.equal(body.value, 91.5);
  assert.equal(body.venue, null);
});

test("missing values, implausible values and too few observations are reported", () => {
  assert.match(checkMeasurementDraft(distance({ value: null }), media).join(" "), /measured length/);
  // 91.5 m entered where cm was meant is still plausible; 9150 m is not.
  assert.match(checkMeasurementDraft(distance({ value: 9150, unit: "m" }), media).join(" "), /between 0.05 m and 200 m/);
  const oneView = distance({ observations: distance().observations.slice(0, 2) });
  assert.match(checkMeasurementDraft(oneView, media).join(" "), /Point A is marked in 1 view/);
  // Two marks in the same video frame are one view.
  const sameFrame = distance({
    observations: [
      { point: "A", mediaId: "vid", frameTimeSeconds: 1, u: 1, v: 1 },
      { point: "A", mediaId: "vid", frameTimeSeconds: 1, u: 2, v: 2 },
      { point: "B", mediaId: "img1", frameTimeSeconds: null, u: 5, v: 5 },
      { point: "B", mediaId: "img2", frameTimeSeconds: null, u: 5, v: 5 },
    ],
  });
  assert.match(checkMeasurementDraft(sameFrame, media).join(" "), /Point A is marked in 1 view/);
});

test("observations must be in accepted media, inside the image, with a frame time for video", () => {
  const obs = distance().observations;
  assert.match(checkMeasurementDraft(distance({ observations: [...obs, { point: "A", mediaId: "bad", frameTimeSeconds: null, u: 1, v: 1 }] }), media).join(" "), /accepted image or video/);
  assert.match(checkMeasurementDraft(distance({ observations: [...obs, { point: "A", mediaId: "img2", frameTimeSeconds: null, u: 4032, v: 1 }] }), media).join(" "), /outside the 4032x3024 image/);
  assert.match(checkMeasurementDraft(distance({ observations: [...obs, { point: "B", mediaId: "vid", frameTimeSeconds: null, u: 1, v: 1 }] }), media).join(" "), /time \(seconds\)/);
});

test("a control point needs venue coordinates and a datum", () => {
  const cp: MeasurementDraft = {
    kind: "CONTROL_POINT",
    label: "CP-1",
    method: "TOTAL_STATION",
    unit: "m",
    value: null,
    uncertainty: null,
    datum: "",
    venue: null,
    observations: [
      { point: "P", mediaId: "img1", frameTimeSeconds: null, u: 10, v: 10 },
      { point: "P", mediaId: "img2", frameTimeSeconds: null, u: 12, v: 11 },
    ],
  };
  const problems = checkMeasurementDraft(cp, media).join(" ");
  assert.match(problems, /surveyed x, y and z/);
  assert.match(problems, /survey datum/);
  assert.deepEqual(checkMeasurementDraft({ ...cp, datum: "survey", venue: [1, 2, 0] }, media), []);
});

test("clickToPixel maps a click on a scaled element to the media's own pixels", () => {
  assert.deepEqual(clickToPixel({ offsetX: 50, offsetY: 25 }, { width: 100, height: 75 }, { width: 4032, height: 3024 }), { u: 2016, v: 1008 });
  assert.equal(clickToPixel({ offsetX: 100, offsetY: 10 }, { width: 100, height: 75 }, { width: 4032, height: 3024 }), null);
  assert.equal(clickToPixel({ offsetX: 1, offsetY: 1 }, { width: 0, height: 0 }, { width: 10, height: 10 }), null);
});

test("status badges distinguish incomplete, uploaded, pending, calibrated and rejected", () => {
  assert.equal(captureStageBadge("INCOMPLETE_CAPTURE").label, "Incomplete capture");
  assert.equal(captureStageBadge("MEDIA_UPLOADED").label, "Media uploaded");
  for (const s of ["NO_EVIDENCE", "EVIDENCE_INCOMPLETE", "AWAITING_RECONSTRUCTION", "READY_TO_CALIBRATE"] as const) {
    assert.equal(calibrationBadge(s, "ARBITRARY_SCALE").label, "Calibration pending");
  }
  assert.equal(calibrationBadge("CALIBRATED", "CANONICAL").label, "Calibrated");
  assert.equal(calibrationBadge("CALIBRATED", "METRIC_NOT_ALIGNED").label, "Calibrated (scale only)");
  assert.equal(calibrationBadge("REJECTED", "ARBITRARY_SCALE").tone, "error");
});
