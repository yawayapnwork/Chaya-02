import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { FLOOR, MockCaptureServer, VENUE, mp4 } from "./capture-fixture";
import { mockOidc } from "./oidc-fixture";

// Offline capture recovery in a real browser (Chromium): the real CaptureWorkspace, the real IndexedDB queue
// (lib/capture-queue-idb.ts), real SHA-256 hashing and real XHR part uploads, against a stateful mock capture API
// (e2e/capture-fixture.ts) with injected failures. "Offline" is the browser's own offline mode (navigator.onLine,
// online/offline events) plus an API that cannot be reached. A browser restart is a new page in the same browser
// profile (IndexedDB persists; the in-memory sign-in does not).

async function signIn(page: Page, server: MockCaptureServer) {
  // No provider session yet: the explicit sign-in (the silent restore is covered by ui-audit.spec.ts).
  await mockOidc(page, { session: false });
  await server.install(page);
  await page.goto("/capture");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByTestId("capture-queue")).toBeVisible();
  // The venue and floor lists are loaded while online; recording a session offline uses them.
  await expect(page.locator(`option[value="${FLOOR}"]`)).toBeAttached();
}

async function recordSession(page: Page) {
  await page.getByRole("combobox").first().selectOption({ label: "Test Hall" });
  await page.getByRole("combobox").nth(1).selectOption(FLOOR);
  await page.getByRole("button", { name: "Create capture session" }).click();
  await expect(page.getByTestId("active-session-status")).toBeVisible();
}

async function addFile(page: Page, name: string, bytes: Buffer) {
  await page.getByLabel("Choose files to upload").setInputFiles({ name, mimeType: "video/mp4", buffer: bytes });
}

const fileState = (page: Page) => page.getByTestId("active-files").getByTestId("queue-file").first();

/** Everything the queue keeps in IndexedDB, as JSON (blobs as their sizes). */
async function dumpQueueDb(page: Page): Promise<string> {
  return page.evaluate(async () => {
    const db: IDBDatabase = await new Promise((res, rej) => {
      const r = indexedDB.open("chaya-capture-queue");
      r.onsuccess = () => res(r.result);
      r.onerror = () => rej(r.error);
    });
    const out: Record<string, unknown[]> = {};
    for (const name of Array.from(db.objectStoreNames)) {
      out[name] = await new Promise((res) => {
        const r = db.transaction(name).objectStore(name).getAll();
        r.onsuccess = () => res(r.result.map((x: { blob?: Blob }) => (x.blob ? { ...x, blob: x.blob.size } : x)));
      });
    }
    db.close();
    return JSON.stringify(out);
  });
}

async function goOffline(context: BrowserContext, server: MockCaptureServer) {
  server.down = true;
  await context.setOffline(true);
}

async function goOnline(context: BrowserContext, server: MockCaptureServer) {
  server.down = false;
  await context.setOffline(false);
}

test("offline: the session and file are saved on the device, survive a browser restart, and upload when the connection returns", async ({ context, page }) => {
  const server = new MockCaptureServer();
  await signIn(page, server);
  await goOffline(context, server);
  await recordSession(page);
  await expect(page.getByTestId("queue-online")).toContainText("Offline");
  await expect(page.getByTestId("active-session-status")).toContainText("Recorded on this device");
  await addFile(page, "walk.mp4", mp4(3000));
  await expect(fileState(page)).toHaveAttribute("data-state", "QUEUED");
  await expect(fileState(page)).toContainText("Saved, waiting to upload");
  await expect(page.getByTestId("queue-counts")).toContainText("1 file(s) waiting to upload");
  await expect(page.getByTestId("queue-session")).toHaveAttribute("data-status", "Saved on this device");
  expect(server.captures.size).toBe(0);
  expect(await dumpQueueDb(page)).toContain('"blob":3000');

  // The browser is closed while offline. Later, online again, the operator opens the app and signs in.
  await page.close();
  await goOnline(context, server);
  const again = await context.newPage();
  // No identity-provider session any more (the browser was closed): the silent restore is refused, the page says so.
  await mockOidc(again, { session: false });
  await server.install(again);
  await again.goto("/capture");
  await expect(again.getByTestId("queue-signed-out")).toContainText("1 capture session(s) are saved on this device");
  await again.getByRole("button", { name: "Sign in" }).click();
  await expect(again.getByTestId("queue-session")).toHaveAttribute("data-status", "Synchronized", { timeout: 30_000 });
  expect(server.captures.size).toBe(1);
  expect(server.mediaCount()).toBe(1);
  const media = [...[...server.captures.values()][0].media.values()][0];
  expect(media.status).toBe("ACCEPTED");
  expect(server.log.filter((l) => l.startsWith("PUT")).length).toBe(3);
  // The verified bytes are on the server, so the device no longer keeps them.
  expect(await dumpQueueDb(again)).not.toContain('"blob":3000');
});

test("a network drop mid-upload resumes with the missing parts only; a lost answer never duplicates the session or the file", async ({ context, page }) => {
  const server = new MockCaptureServer();
  server.faults.push({ match: "POST /captures", kind: "lose-answer", times: 1 });
  server.faults.push({ match: "POST /media", kind: "lose-answer", times: 1 });
  server.faults.push({ match: "PUT /parts/2", kind: "abort", times: 1 });
  await signIn(page, server);
  await recordSession(page);
  await addFile(page, "walk.mp4", mp4(3000, 3));
  await expect(page.getByTestId("queue-session")).toHaveAttribute("data-status", "Synchronized", { timeout: 60_000 });
  expect(server.captures.size).toBe(1);
  expect(server.mediaCount()).toBe(1);
  const puts = server.log.filter((l) => l.startsWith("PUT"));
  expect(puts.filter((l) => l.endsWith("/parts/1"))).toHaveLength(1);
  expect(puts.filter((l) => l.endsWith("/parts/2"))).toHaveLength(1); // the aborted attempt never reached the server
  void context;
});

test("an expired sign-in pauses the queue, keeps the file, and resumes after signing in again", async ({ page }) => {
  const server = new MockCaptureServer();
  await signIn(page, server);
  await recordSession(page);
  await expect(page.getByTestId("active-session-status")).toContainText("Created on the server");
  server.faults.push({ match: "POST /media", kind: 401, code: "HTTP_401", times: 1 });
  await addFile(page, "walk.mp4", mp4(2000, 5));
  await expect(page.getByRole("alert").filter({ hasText: "Your sign-in expired" })).toBeVisible();
  await expect(fileState(page)).toHaveAttribute("data-state", "QUEUED");
  expect(server.mediaCount()).toBe(0);
  await page.getByRole("button", { name: "Sign in again" }).click();
  await expect(page.getByTestId("queue-session")).toHaveAttribute("data-status", "Synchronized", { timeout: 30_000 });
  expect(server.mediaCount()).toBe(1);
});

test("a corrupted local copy is detected by its checksum and never sent", async ({ context, page }) => {
  const server = new MockCaptureServer();
  await signIn(page, server);
  await goOffline(context, server);
  await recordSession(page);
  await addFile(page, "walk.mp4", mp4(3000, 7));
  await expect(fileState(page)).toHaveAttribute("data-state", "QUEUED");
  // Damage the stored copy (same size, different bytes), as a faulty disk or a partial write would.
  await page.evaluate(async () => {
    const db: IDBDatabase = await new Promise((res) => {
      const r = indexedDB.open("chaya-capture-queue");
      r.onsuccess = () => res(r.result);
    });
    const tx = db.transaction("blobs", "readwrite");
    const store = tx.objectStore("blobs");
    const all: { localId: string; blob: Blob }[] = await new Promise((res) => {
      const r = store.getAll();
      r.onsuccess = () => res(r.result);
    });
    for (const row of all) store.put({ localId: row.localId, blob: new Blob([new Uint8Array(row.blob.size).fill(7)]) });
    await new Promise((res) => (tx.oncomplete = res));
  });
  await goOnline(context, server);
  await expect(fileState(page)).toHaveAttribute("data-state", "CORRUPTED", { timeout: 30_000 });
  await expect(fileState(page)).toContainText("no longer matches the checksum");
  expect(server.mediaCount()).toBe(0);
  expect(server.log.some((l) => l.startsWith("PUT"))).toBe(false);
});

test("too little storage on the device is stated, and the file still uploads while the page is open", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator.storage, "estimate", { value: async () => ({ usage: 1_000, quota: 2_000 }) });
  });
  const server = new MockCaptureServer();
  await signIn(page, server);
  await expect(page.getByTestId("queue-storage")).toContainText("used of about");
  await recordSession(page);
  await addFile(page, "walk.mp4", mp4(3000, 9));
  await expect(page.getByTestId("storage-notices")).toContainText("Not enough storage on this device");
  await expect(page.getByTestId("queue-session")).toHaveAttribute("data-status", "Synchronized", { timeout: 30_000 });
  expect(server.mediaCount()).toBe(1);
});

test("a server rejection is shown as a rejection, never as an upload that succeeded", async ({ page }) => {
  const server = new MockCaptureServer();
  server.verdict = "REJECTED";
  await signIn(page, server);
  await recordSession(page);
  await addFile(page, "walk.mp4", mp4(2000, 11));
  await expect(fileState(page)).toHaveAttribute("data-state", "REJECTED", { timeout: 30_000 });
  await expect(fileState(page)).toContainText("Rejected by the server");
  await expect(fileState(page)).toContainText("malware");
  await expect(page.getByRole("button", { name: "Finish upload" })).toBeDisabled();
});

test("transient server errors are retried with backoff and the user can retry at once", async ({ page }) => {
  const server = new MockCaptureServer();
  await signIn(page, server);
  await recordSession(page);
  await expect(page.getByTestId("active-session-status")).toContainText("Created on the server");
  server.faults.push({ match: "POST /media", kind: 503, code: "STORAGE_UNAVAILABLE", times: 2 });
  await addFile(page, "walk.mp4", mp4(2000, 13));
  await expect(fileState(page)).toHaveAttribute("data-state", "RETRY_WAIT");
  await expect(fileState(page)).toContainText("Upload interrupted, retrying");
  await fileState(page).getByRole("button", { name: "Retry now" }).click();
  await expect(fileState(page)).toHaveAttribute("data-state", "ACCEPTED", { timeout: 30_000 });
});

test("a venue deleted while offline is a conflict that keeps the data until the user discards it", async ({ context, page }) => {
  const server = new MockCaptureServer();
  await signIn(page, server);
  await goOffline(context, server);
  await recordSession(page);
  await addFile(page, "walk.mp4", mp4(2000, 15));
  server.venueVisible = false;
  await goOnline(context, server);
  const session = page.getByTestId("queue-session");
  await expect(session).toHaveAttribute("data-conflict", "VENUE_UNAVAILABLE", { timeout: 30_000 });
  await expect(session.getByTestId("queue-conflict")).toContainText("Your local data is kept until you choose");
  expect(await dumpQueueDb(page)).toContain('"blob":2000');
  page.once("dialog", (d) => d.accept());
  await session.getByRole("button", { name: "Discard local data" }).click();
  await expect(page.getByTestId("queue-session")).toHaveCount(0);
  expect(await dumpQueueDb(page)).not.toContain('"blob":2000');
});

test("a floor republished while offline needs the user's confirmation before the session is created", async ({ context, page }) => {
  const server = new MockCaptureServer();
  await signIn(page, server);
  await goOffline(context, server);
  await recordSession(page);
  await addFile(page, "walk.mp4", mp4(2000, 17));
  server.floorVersion = "44444444-4444-4444-4444-444444444444";
  await goOnline(context, server);
  const session = page.getByTestId("queue-session");
  await expect(session).toHaveAttribute("data-conflict", "SCAN_VERSION_CHANGED", { timeout: 30_000 });
  expect(server.captures.size).toBe(0);
  await session.getByRole("button", { name: "Upload anyway" }).click();
  await expect(session).toHaveAttribute("data-status", "Synchronized", { timeout: 30_000 });
  expect(server.captures.size).toBe(1);
});

test("no credential is ever written to the device's queue", async ({ context, page }) => {
  const server = new MockCaptureServer();
  await signIn(page, server);
  await goOffline(context, server);
  await recordSession(page);
  await addFile(page, "walk.mp4", mp4(2000, 19));
  await goOnline(context, server);
  await expect(page.getByTestId("queue-session")).toHaveAttribute("data-status", "Synchronized", { timeout: 30_000 });
  expect([...server.tokens]).toContain("Bearer test-access-token");
  const db = await dumpQueueDb(page);
  expect(db).not.toContain("test-access-token");
  expect(db).not.toMatch(/bearer|access_token|id_token/i);
  const storage = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
  expect(storage).not.toContain("test-access-token");
  void VENUE;
});
