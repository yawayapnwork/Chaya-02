import { readFileSync } from "node:fs";
import path from "node:path";
import { expect, test, type Page, type Route } from "@playwright/test";
import { mockOidc } from "./oidc-fixture";

// The processing panel against a run recorded from the real API (docs/pipeline.md, "Live progress"). The fixture is what
// services/api answered and streamed for one run against real PostgreSQL and MinIO
// (ProcessingLiveStatusTest#aRunRecordedAsTheApiStreamedItForTheWebReplay): the snapshot, the capture, the stream's own
// bytes after each step, and the answers to retry and cancel. This spec replays it into the real CaptureWorkspace, one
// stream connection at a time, and checks what the page shows after each. Only the replay is staged here: which recorded
// frames go into which connection, and the duplicate and out-of-order frames this spec repeats on purpose. It is not a live
// stack: no worker, database or identity provider runs during this spec.

interface Recorded {
  venueId: string;
  floorId: string;
  captureId: string;
  captureList: unknown[];
  capture: Record<string, unknown>;
  captureAfterCancel: Record<string, unknown>;
  snapshot: Status;
  retryAnswer: Status;
  cancelAnswer: Status;
  steps: { name: string; sse: string }[];
}
interface Status { revision: number; run: { stages: { stage: string }[] } }

const REC: Recorded = JSON.parse(readFileSync(path.join(__dirname, "fixtures", "processing-live-run.json"), "utf8"));
const STAGES = REC.snapshot.run.stages.map((s) => s.stage);

/** The status frame (exact bytes, with its trailing blank line) the stream wrote at a recorded step. */
function frame(step: string): string {
  const s = REC.steps.find((x) => x.name === step);
  if (!s) throw new Error(`no recorded step ${step}`);
  const frames = s.sse.split("\n\n").filter((f) => f.includes("event:status"));
  if (frames.length !== 1) throw new Error(`step ${step} recorded ${frames.length} status frames`);
  return `${frames[0]}\n\n`;
}
const revisionOf = (f: string) => Number(/^id:(\d+)$/m.exec(f)![1]);

/** Serves the recorded API. Each stream connection waits until the test hands it the frames to send. */
class Replay {
  processing: Status = REC.snapshot;
  capture = REC.capture;
  lastEventIds: (string | null)[] = [];
  unmocked: string[] = [];
  private waiting: ((frames: string[]) => void)[] = [];

  async install(page: Page) {
    await page.route("**/mock-api/api/v1/**", (route) => this.handle(route));
  }

  /** Answers the page's open stream connection with `frames`, once the page has opened it. A connection the page opened
   * earlier and is no longer reading is ended empty. */
  async serve(frames: string[]) {
    await expect.poll(() => this.waiting.length, { timeout: 15_000 }).toBeGreaterThan(0);
    const newest = this.waiting.pop()!;
    this.waiting.splice(0).forEach((stale) => stale([]));
    newest(frames);
  }

  private json(route: Route, body: unknown, status = 200) {
    return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  }

  private async handle(route: Route) {
    const req = route.request();
    const path = new URL(req.url()).pathname.replace(/^.*\/api\/v1/, "");
    const method = req.method();
    const v = `/venues/${REC.venueId}`;
    const c = `${v}/captures/${REC.captureId}`;
    if (method === "GET" && path === "/venues") return this.json(route, [{ id: REC.venueId, name: "Recorded venue", slug: "recorded" }]);
    if (method === "GET" && path === `${v}/floors`) {
      return this.json(route, [{ id: REC.floorId, level: 0, name: "Ground", currentScanVersionId: null, elevation: null }]);
    }
    if (method === "GET" && path === `${v}/captures`) return this.json(route, REC.captureList);
    if (method === "GET" && path === c) return this.json(route, this.capture);
    if (method === "GET" && path === `${c}/media`) return this.json(route, []);
    if (method === "GET" && path === `${c}/processing`) return this.json(route, this.processing);
    if (method === "POST" && path === `${c}/processing/retry`) {
      this.processing = REC.retryAnswer;
      return this.json(route, REC.retryAnswer, 202);
    }
    if (method === "POST" && path === `${c}/processing/cancel`) {
      this.processing = REC.cancelAnswer;
      this.capture = REC.captureAfterCancel;
      return this.json(route, REC.cancelAnswer);
    }
    if (method === "GET" && path === `${c}/processing/stream`) {
      this.lastEventIds.push(req.headers()["last-event-id"] ?? null);
      const frames = await new Promise<string[]>((resolve) => this.waiting.push(resolve));
      return route.fulfill({ status: 200, headers: { "content-type": "text/event-stream" }, body: frames.join("") });
    }
    // Calibration, measurements and reconstructions belong to other panels; this run has none of them.
    if (method === "GET" && (path === `${c}/calibration` || path === `${c}/measurements` || path.endsWith("/reconstructions"))) {
      return route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ status: 404, code: "NOT_FOUND" }) });
    }
    this.unmocked.push(`${method} ${path}`);
    return route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ status: 404, code: "NOT_FOUND" }) });
  }
}

const stage = (page: Page, name: string) => page.locator(`[data-testid="pipeline-stage"][data-stage="${name}"]`);

test("the panel follows a recorded run: stages, a lost worker, retry, cancellation, reconnects and duplicates", async ({ page }) => {
  const api = new Replay();
  await mockOidc(page, { session: false }); // the explicit sign-in; silent restore: ui-audit.spec.ts
  await api.install(page);
  await page.goto("/capture");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("combobox").first().selectOption(REC.venueId);
  await page.getByRole("button", { name: "Continue" }).click();

  // The snapshot first: the first stage waits for a worker; nothing has completed.
  await expect(page.getByTestId("pipeline-progress")).toContainText(`0 of ${STAGES.length} stages completed`);
  await expect(page.getByTestId("pipeline-progress")).toContainText("waiting for a worker");

  // Connection 1: the subscription's first status, then the first stage running.
  await api.serve([frame("queued"), frame("first-stage-running")]);
  expect(api.lastEventIds, "loading the page opens one stream, not one per state change").toEqual([null]);
  await expect(stage(page, STAGES[0])).toHaveAttribute("data-state", "RUNNING");
  await expect(stage(page, STAGES[0])).toHaveAttribute("aria-current", "step");
  await expect(page.getByText("The active stage does not report how far along it is, so no percentage is shown.")).toBeVisible();
  await expect(page.locator("[data-testid=pipeline-bar] [data-state=RUNNING]")).toHaveClass(/chaya-indeterminate/);

  // Connection 2 resumes from the last id. It repeats that status and an older one before the next; neither moves the page.
  await api.serve([frame("first-stage-running"), frame("queued"), frame("first-stage-succeeded")]);
  expect(api.lastEventIds[1]).toBe(String(revisionOf(frame("first-stage-running"))));
  await expect(stage(page, STAGES[0])).toHaveAttribute("data-state", "SUCCEEDED");
  await expect(page.getByTestId("pipeline-progress")).toContainText(`1 of ${STAGES.length} stages completed`);
  await expect(page.getByTestId("artifacts")).not.toContainText("Available outputs (0)");

  // Connection 3: the second stage runs, then its worker stops heartbeating (same revision, read later).
  await api.serve([frame("second-stage-running"), frame("second-stage-lease-expired")]);
  expect(api.lastEventIds[2]).toBe(String(revisionOf(frame("first-stage-succeeded"))));
  await expect(page.getByTestId("stale-lease")).toContainText("has stopped sending heartbeats");
  await expect(stage(page, STAGES[1])).toHaveAttribute("data-state", "RUNNING");
  await expect(page.getByTestId("run-badge")).not.toHaveText(/succeeded|finalized/i);

  // Connection 4: the enforcer settled it as WORKER_LOST. Interrupted, retryable, never succeeded.
  await api.serve([frame("worker-lost")]);
  await expect(stage(page, STAGES[1])).toHaveAttribute("data-state", "FAILED");
  await expect(page.getByTestId("failure-kind")).toHaveAttribute("data-kind", "INTERRUPTED");
  await expect(page.getByTestId("stale-lease")).toHaveCount(0);
  const retry = page.getByRole("button", { name: /^Retry .*retr(y|ies) left\)$/ });
  await expect(retry).toBeVisible();

  // Retry: the server's answer is shown at once; the stream's copy of the same state is not shown twice.
  await retry.click();
  await expect(stage(page, STAGES[1])).toHaveAttribute("data-state", "QUEUED");
  await api.serve([frame("retried"), frame("second-stage-attempt-2-running")]);
  await expect(stage(page, STAGES[1])).toHaveAttribute("data-state", "RUNNING");
  await expect(stage(page, STAGES[1])).toContainText("attempt 2");

  // Cancel: the run says so; nothing after the cancellation runs.
  await page.getByRole("button", { name: "Cancel processing" }).click();
  await expect(page.getByTestId("failure-kind")).toHaveAttribute("data-kind", "CANCELLED");
  await api.serve([frame("cancelled")]);
  await expect(page.getByTestId("run-badge")).toHaveText("Cancelled");
  await expect(page.getByRole("button", { name: "Cancel processing" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /^Retry/ })).toHaveCount(0);
  // The cancelled stage was on attempt 2; attempt 1's lost worker is history, not advice to retry.
  await expect(stage(page, STAGES[1])).toHaveAttribute("data-state", "CANCELLED");
  await expect(stage(page, STAGES[1])).toContainText("attempt 2");
  await expect(stage(page, STAGES[1]).getByTestId("earlier-attempt")).toContainText("Attempt 1 failed: WORKER_LOST");
  await expect(stage(page, STAGES[1])).not.toContainText("Retry to run it again");

  expect(api.unmocked).toEqual([]);
});

test("after a browser refresh the page subscribes again and shows the persisted state", async ({ page }) => {
  const api = new Replay();
  api.processing = REC.cancelAnswer;
  api.capture = REC.captureAfterCancel;
  await mockOidc(page, { session: false }); // the explicit sign-in; silent restore: ui-audit.spec.ts
  await api.install(page);
  await page.goto("/capture");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("combobox").first().selectOption(REC.venueId);
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByTestId("run-badge")).toHaveText("Cancelled");
  await expect(stage(page, STAGES[1])).toContainText("attempt 2");
  await expect(stage(page, STAGES[1])).not.toContainText("Retry to run it again");
  // A finished run is still followed, so a retry made elsewhere would show. A reloaded page holds no event id yet: the
  // server sends the current status, and the page keeps showing the same state.
  expect(api.lastEventIds).toEqual([null]);
  await api.serve([frame("cancelled")]);
  await expect(page.getByTestId("processing-live")).toBeVisible();
  await expect(page.getByTestId("run-badge")).toHaveText("Cancelled");
});
