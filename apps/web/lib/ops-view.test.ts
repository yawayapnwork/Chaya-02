import assert from "node:assert/strict";
import { test } from "node:test";
import {
  JOB_STATUSES,
  classifyFailure,
  formatAge,
  formatMs,
  formatPercent,
  gapLabel,
  jobStatusLabel,
  reconstructionQualityLabel,
  zeroResultRate,
} from "./ops-view.ts";

test("the job view shows all five lifecycle states, with SUCCEEDED as Completed", () => {
  assert.deepEqual(JOB_STATUSES.map((s) => s.label), ["Queued", "Running", "Completed", "Failed", "Cancelled"]);
  assert.equal(jobStatusLabel("SUCCEEDED"), "Completed");
  assert.equal(jobStatusLabel("SOMETHING_NEW"), "SOMETHING_NEW");
});

test("ages come from timestamps; a missing timestamp is 'never', not zero", () => {
  assert.equal(formatAge(null), "never");
  assert.equal(formatAge(undefined), "never");
  assert.equal(formatAge(0), "0 s ago");
  assert.equal(formatAge(90), "1 min ago");
  assert.equal(formatAge(3 * 3600), "3 h ago");
  assert.equal(formatAge(5 * 86400), "5 days ago");
  assert.match(formatAge(-5), /ahead of the server clock/);
});

test("missing measurements are shown as missing, never as 0", () => {
  assert.equal(formatMs(null), "—");
  assert.equal(formatPercent(null), "—");
  assert.equal(zeroResultRate(0, 0), null);
  assert.equal(zeroResultRate(1, 4), 25);
});

test("only a FINAL reconstruction is labelled final", () => {
  assert.deepEqual(reconstructionQualityLabel("FINAL", "SUCCEEDED"), { label: "Final", final: true });
  assert.equal(reconstructionQualityLabel("PARTIAL", "PARTIAL").final, false);
  const failedLater = reconstructionQualityLabel(null, "FAILED");
  assert.equal(failedLater.final, false);
  assert.match(failedLater.label, /FAILED/);
});

test("request failures map to explicit section states", () => {
  assert.equal(classifyFailure(401), "signed-out");
  assert.equal(classifyFailure(403), "unauthorized");
  assert.equal(classifyFailure(404), "not-found");
  assert.equal(classifyFailure(500), "error");
  assert.equal(classifyFailure(null), "error");
});

test("coverage gap codes have readable labels, unknown ones pass through", () => {
  assert.match(gapLabel("NO_RECONSTRUCTION"), /No reconstruction/);
  assert.equal(gapLabel("NEW_CODE"), "NEW_CODE");
});
