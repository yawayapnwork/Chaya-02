import assert from "node:assert/strict";
import { test } from "node:test";
import {
  JOB_STATUSES,
  classifyFailure,
  formatCount,
  keepsStaleDataAfter,
  leaseExpired,
  queueProblems,
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

test("an unmeasured count is 'unavailable', never 0", () => {
  assert.equal(formatCount(null), "unavailable");
  assert.equal(formatCount(undefined), "unavailable");
  assert.equal(formatCount(0), "0");
  assert.equal(formatCount(7), "7");
});

test("only transient failures keep the last good data visible", () => {
  assert.equal(keepsStaleDataAfter("error"), true);
  assert.equal(keepsStaleDataAfter("signed-out"), false);
  assert.equal(keepsStaleDataAfter("unauthorized"), false);
  assert.equal(keepsStaleDataAfter("not-found"), false);
});

const healthyQueue = {
  claimableQueued: 0, oldestQueuedAgeSeconds: null, running: 0, staleRunning: 0, overdueRunning: 0, unservedStages: [],
  workers: [{ workerId: "w1", stages: ["INPUT_VALIDATION"], lastSeenAgeSeconds: 4, live: true }], workerLiveAfterSeconds: 180,
};

test("queue problems come from measured values only", () => {
  assert.deepEqual(queueProblems(healthyQueue), []);
  const none = queueProblems({ ...healthyQueue, workers: [] });
  assert.equal(none[0].level, "error");
  assert.match(none[0].text, /No worker has contacted the API/);
  const gone = queueProblems({ ...healthyQueue, workers: [{ ...healthyQueue.workers[0], live: false, lastSeenAgeSeconds: 900 }] });
  assert.match(gone[0].text, /No worker is live.*3 min/);
  const unserved = queueProblems({ ...healthyQueue, claimableQueued: 2, unservedStages: ["POSE_ESTIMATION"] });
  assert.match(unserved[0].text, /no live worker runs POSE_ESTIMATION/);
  const stale = queueProblems({ ...healthyQueue, running: 1, staleRunning: 1, overdueRunning: 1 });
  assert.deepEqual(stale.map((p) => p.level), ["warning", "warning"]);
  assert.match(stale[0].text, /expired lease/);
});

test("a running job's expired lease is judged against the server clock", () => {
  const asOf = "2026-10-11T10:00:00Z";
  assert.equal(leaseExpired("RUNNING", "2026-10-11T09:59:00Z", asOf), true);
  assert.equal(leaseExpired("RUNNING", "2026-10-11T10:04:00Z", asOf), false);
  assert.equal(leaseExpired("RUNNING", null, asOf), false);
  assert.equal(leaseExpired("FAILED", "2026-10-11T09:59:00Z", asOf), false);
});
