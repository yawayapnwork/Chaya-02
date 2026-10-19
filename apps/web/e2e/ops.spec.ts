import { expect, type Page, test } from "@playwright/test";
import { mockJson } from "./viewer-fixture";
import { mockOidc } from "./oidc-fixture";

// The operations dashboard's states in a real browser, against a mocked API and a mocked OIDC provider. Sign-in is the
// real authorization-code + PKCE flow of lib/auth.ts (oidc-client-ts): the mock provider only answers its endpoints.
// Every figure asserted here is a mocked API value: this checks what the UI does with measured, missing and failed data,
// not the data itself (OpsDashboardApiTest and JobRecoveryTest check that against PostgreSQL).

const VENUE = "11111111-1111-1111-1111-111111111111";
const OPS = `**/mock-api/api/v1/venues/${VENUE}/ops`;
const now = () => new Date().toISOString();

function access(sections: Partial<Record<string, boolean>>) {
  const all = ["OVERVIEW", "COVERAGE", "JOBS", "FAILURES", "STORAGE", "RESOURCES", "SEARCH_ANALYTICS", "RESCANS", "AUDIT"];
  return {
    venueId: VENUE, roles: ["OPERATOR"], mayControlProcessing: true,
    sections: Object.fromEntries(all.map((s) => [s, sections[s] ?? false])),
  };
}

const overview = () => ({
  venueId: VENUE, venueName: "Test Hall", venueSlug: "test-hall", timezone: "UTC", venueCreatedAt: now(), floorCount: 0,
  captureCount: 0, poiCount: 0, jobCounts: { QUEUED: 1, RUNNING: 1, SUCCEEDED: 0, FAILED: 0, CANCELLED: 0 }, floors: [], asOf: now(),
});

const noAction = { available: false, via: null, captureId: null, reason: "not applicable" };

function jobs(withQueue: boolean) {
  const asOf = now();
  const job = (id: string, stage: string, status: string, lease: string | null) => ({
    id, stage, status, scanId: "s", captureId: "c", runId: "r", runStatus: "RUNNING", retryCount: 0, maxRetries: 3,
    queuedAt: asOf, startedAt: status === "RUNNING" ? asOf : null, finishedAt: null, workerId: status === "RUNNING" ? "cpu-worker" : null,
    leaseExpiresAt: lease, errorCode: null, errorMessage: null, retry: noAction, cancel: noAction,
  });
  return {
    counts: { QUEUED: 1, RUNNING: 1, SUCCEEDED: 0, FAILED: 0, CANCELLED: 0 },
    ...(withQueue ? {
      queue: {
        claimableQueued: 1, oldestQueuedAgeSeconds: 1260, running: 1, staleRunning: 1, overdueRunning: 0,
        unservedStages: ["POSE_ESTIMATION"], workerLiveAfterSeconds: 180,
        workers: [{ workerId: "cpu-worker", stages: ["INPUT_VALIDATION"], lastSeenAt: asOf, lastSeenAgeSeconds: 900, lastClaimedAt: null, live: false }],
      },
    } : {}),
    jobs: [
      job("aaaaaaaa-0000-0000-0000-000000000001", "POSE_ESTIMATION", "QUEUED", null),
      job("aaaaaaaa-0000-0000-0000-000000000002", "INPUT_VALIDATION", "RUNNING", new Date(Date.now() - 60_000).toISOString()),
    ],
    limit: 50,
    asOf,
  };
}

async function signedInDashboard(page: Page, sections: Partial<Record<string, boolean>>) {
  await mockOidc(page, { session: false }); // the explicit sign-in; silent restore: ui-audit.spec.ts
  await mockJson(page, "**/mock-api/api/v1/venues", [{ id: VENUE, name: "Test Hall", slug: "test-hall", timezone: "UTC" }]);
  await mockJson(page, `${OPS}/access`, access(sections));
  await mockJson(page, `${OPS}/overview`, overview());
  await page.goto(`/ops?venue=${VENUE}`);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Operations dashboard" })).toBeVisible();
  await expect(page.locator("#overview")).toContainText("Test Hall");
}

test("queue depth, stale jobs and worker liveness are shown as measured", async ({ page }) => {
  await mockJson(page, `${OPS}/jobs**`, jobs(true));
  await signedInDashboard(page, { OVERVIEW: true, JOBS: true });

  const q = page.getByTestId("ops-queue");
  await expect(q).toContainText("Waiting to start");
  await expect(q).toContainText("oldest 21 min ago");
  await expect(q).toContainText("0 of 1"); // live workers
  await expect(q).toContainText("No worker is live");
  await expect(q).toContainText("no live worker runs POSE_ESTIMATION");
  await expect(q).toContainText("expired lease");
  await expect(page.locator("#jobs")).toContainText("lease expired — worker lost, recovery pending");
});

test("queue data an older API does not report is unavailable, not zero", async ({ page }) => {
  await mockJson(page, `${OPS}/jobs**`, jobs(false));
  await signedInDashboard(page, { OVERVIEW: true, JOBS: true });
  await expect(page.getByTestId("ops-queue-unavailable")).toBeVisible();
  await expect(page.getByTestId("ops-queue")).toHaveCount(0);
});

test("a failed refresh keeps the last data visible and marks it out of date", async ({ page }) => {
  let fail = false;
  await page.route(`${OPS}/jobs**`, (route) => fail
    ? route.fulfill({ status: 503, contentType: "application/problem+json",
      body: JSON.stringify({ code: "DATABASE_UNAVAILABLE", detail: "The database is unavailable" }) })
    : route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(jobs(true)) }));
  await signedInDashboard(page, { OVERVIEW: true, JOBS: true });
  await expect(page.getByTestId("ops-queue")).toBeVisible();

  fail = true;
  await page.getByRole("button", { name: "Refresh" }).click();
  await expect(page.getByTestId("jobs-stale")).toContainText("Refresh failed");
  await expect(page.getByTestId("ops-queue")).toBeVisible(); // the last good data is still there
  await expect(page.locator("#jobs h2")).toContainText("out of date");

  fail = false;
  await page.getByTestId("jobs-stale").getByRole("button", { name: "Try again" }).click();
  await expect(page.getByTestId("jobs-stale")).toHaveCount(0);
});

test("a section the role may not read is never requested and says which roles may", async ({ page }) => {
  let requested = false;
  await page.route(`${OPS}/jobs**`, (route) => {
    requested = true;
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(jobs(true)) });
  });
  await signedInDashboard(page, { OVERVIEW: true });
  await expect(page.getByTestId("jobs-unauthorized")).toContainText("admin, venue-manager or operator");
  expect(requested).toBe(false);
});

test("a section whose first load fails shows the error and recovers on retry", async ({ page }) => {
  let fail = true;
  await page.route(`${OPS}/jobs**`, (route) => fail
    ? route.fulfill({ status: 500, contentType: "application/problem+json", body: JSON.stringify({ code: "INTERNAL", detail: "boom" }) })
    : route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(jobs(true)) }));
  await signedInDashboard(page, { OVERVIEW: true, JOBS: true });
  const jobsSection = page.locator("#jobs");
  await expect(jobsSection.getByRole("alert")).toContainText("Could not load this section");
  fail = false;
  await jobsSection.getByRole("button", { name: "Try again" }).click();
  await expect(page.getByTestId("ops-queue")).toBeVisible();
});

const usage = (attempts: number, wall: number, gpu: number | null, gpuN: number) => ({
  attempts, wallSeconds: wall, cpuSeconds: null, cpuMeasuredAttempts: 0, gpuSeconds: gpu, gpuMeasuredAttempts: gpuN,
  gpuUnmeasuredAttempts: attempts - gpuN, peakGpuMemoryBytes: null, artifactBytes: 0,
});

function resources() {
  const estimate = {
    kind: "ESTIMATED", amounts: [{ currency: "USD", amount: 1.25 }], pricedAttempts: 2, unpricedAttempts: 1, unpricedWallSeconds: 30,
    unpricedReasons: { RESOURCE_CLASS_NOT_REPORTED: 1 }, complete: false,
  };
  return {
    days: 30, since: now(), window: usage(3, 5400, 1800, 1), allTime: usage(3, 5400, 1800, 1),
    byOutcome: [{ outcome: "INTERRUPTED", attempts: 1, wallSeconds: 30, gpuSeconds: null, estimated: [], unpricedAttempts: 1 }],
    estimate, estimateAllTime: estimate,
    billed: { available: true, totals: [{ currency: "USD", amount: 812.4 }], note: "Recorded by an admin with the provider's evidence reference.",
      bills: [{ id: "b", provider: "AWS", periodStart: "2026-09-01", periodEnd: "2026-09-30", amount: 812.4, currency: "USD",
        evidenceReference: "invoice 4417", description: null, recordedBy: "admin", recordedAt: now() }] },
    storage: { artifactBytes: 0, rawMediaBytes: 0, estimatedPerMonth: null, estimateBasis: null, unavailableReason: "NO_STORAGE_RATE" },
    perScan: { versions: 1, fullyPricedVersions: 0, averageEstimated: [], averageWallSeconds: 5400 },
    scanVersions: [], workers: [], trend: [], rateCard: null, stageRunsWithoutUsage: 0, asOf: now(),
  };
}

test("estimated cost is labelled as an estimate, a partial one as a lower bound, and kept apart from billed cost", async ({ page }) => {
  await mockJson(page, `${OPS}/resources**`, resources());
  await signedInDashboard(page, { OVERVIEW: true, RESOURCES: true });

  const est = page.getByTestId("ops-estimate");
  await expect(est).toContainText("Estimated processing cost");
  await expect(est).toContainText("at least 1.25 USD");
  await expect(est).toContainText("1 attempt not priced: the worker did not report its resource class");
  await expect(page.getByTestId("ops-billed")).toContainText("812.40 USD");
  const r = page.getByTestId("ops-resources");
  await expect(r).toContainText("measured on 1 of 3 attempts"); // GPU time is never presented as covering every attempt
  await expect(r).toContainText("not measured"); // CPU time the workers did not report
});
