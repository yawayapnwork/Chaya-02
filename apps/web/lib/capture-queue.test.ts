import assert from "node:assert/strict";
import { test } from "node:test";
import {
  BACKOFF_CAP_MS,
  CaptureQueue,
  type InitAnswer,
  type LocalFile,
  type LocalSession,
  MAX_ATTEMPTS,
  type QueueApi,
  type QueueStore,
  type ServerMedia,
  backoffMs,
  classifyError,
} from "./capture-queue.ts";

// A fake capture server with the real one's idempotency rules (V38) and fault injection. It is a test double, not the
// service: the server's own behaviour is tested in services/api (CaptureOfflineRecoveryTest).

const VENUE = "venue-1";
const FLOOR = "floor-1";
const PART = 4;

class HttpError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message = code) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
class NotSignedInError extends Error {
  constructor() {
    super("You are not signed in.");
    this.name = "NotSignedInError";
  }
}
const networkError = () => new HttpError(0, "NETWORK", "Network error while uploading");

interface Media extends ServerMedia { id: string; clientFileId: string; sha256: string; size: number; parts: Map<number, Uint8Array> }

class FakeServer implements QueueApi {
  captures = new Map<string, { id: string; status: string; clientSessionId: string; media: Map<string, Media> }>();
  venues = new Set([VENUE]);
  floorVersion: string | null = "v1";
  erased = new Set<string>();
  verdict: "ACCEPTED" | "REJECTED" | "QUARANTINED" = "ACCEPTED";
  calls: string[] = [];
  /** Fails the next call to `op`: before it takes effect, or after (the answer is lost). */
  faults: { op: string; error: () => Error; after?: boolean; times: number }[] = [];
  authExpired = false;

  private fault(op: string, after: boolean) {
    if (this.authExpired) throw new NotSignedInError();
    const f = this.faults.find((x) => x.op === op && !!x.after === after && x.times > 0);
    if (f) {
      f.times--;
      throw f.error();
    }
  }
  private capture(id: string) {
    const c = [...this.captures.values()].find((x) => x.id === id);
    if (!c || !this.venues.has(VENUE)) throw new HttpError(404, "NOT_FOUND", "capture not found");
    return c;
  }
  async createCapture(venueId: string, body: Parameters<QueueApi["createCapture"]>[1]) {
    this.calls.push("createCapture");
    this.fault("createCapture", false);
    if (!this.venues.has(venueId)) throw new HttpError(404, "NOT_FOUND", "venue not found");
    if (this.erased.has(body.clientSessionId)) throw new HttpError(410, "CAPTURE_ERASED");
    let c = this.captures.get(body.clientSessionId);
    if (!c) {
      if (body.expectedScanVersion && body.expectedScanVersion.versionId !== this.floorVersion) {
        throw new HttpError(409, "SCAN_VERSION_CHANGED");
      }
      c = { id: `cap-${this.captures.size + 1}`, status: "CREATED", clientSessionId: body.clientSessionId, media: new Map() };
      this.captures.set(body.clientSessionId, c);
    }
    this.fault("createCapture", true);
    return { id: c.id, status: c.status };
  }
  async getCapture(_v: string, id: string) {
    const c = this.capture(id);
    return { id: c.id, status: c.status };
  }
  async listVenues() {
    return [...this.venues].map((id) => ({ id }));
  }
  async initMedia(_v: string, captureId: string, body: Parameters<QueueApi["initMedia"]>[2]): Promise<InitAnswer> {
    this.calls.push("initMedia");
    this.fault("initMedia", false);
    const c = this.capture(captureId);
    if (c.status !== "CREATED" && c.status !== "UPLOADING") throw new HttpError(409, "CAPTURE_NOT_ACCEPTING_UPLOADS");
    if (body.contentType === "application/x-msdownload") throw new HttpError(415, "UNSUPPORTED_MEDIA_TYPE");
    let m = [...c.media.values()].find((x) => x.clientFileId === body.clientFileId);
    const existing = !!m;
    if (!m) {
      m = { id: `media-${c.media.size + 1}`, clientFileId: body.clientFileId, sha256: body.sha256, size: body.sizeBytes, status: "PENDING",
        partSizeBytes: PART, uploadedParts: [], rejectionCode: null, rejectionMessage: null, parts: new Map() };
      c.media.set(m.id, m);
    }
    this.fault("initMedia", true);
    return { mediaId: m.id, partSizeBytes: PART, totalParts: Math.ceil(m.size / PART), existing, status: m.status };
  }
  async getMedia(_v: string, captureId: string, mediaId: string): Promise<ServerMedia> {
    this.fault("getMedia", false);
    const m = this.capture(captureId).media.get(mediaId)!;
    return { ...m, uploadedParts: [...m.parts.keys()].sort() };
  }
  async putPart(_v: string, captureId: string, mediaId: string, n: number, data: Blob, sha: string, onProgress: (l: number) => void) {
    this.calls.push(`putPart:${n}`);
    this.fault("putPart", false);
    const bytes = new Uint8Array(await data.arrayBuffer());
    if (sha !== (await sha256(data))) throw new HttpError(422, "CHECKSUM_MISMATCH");
    this.capture(captureId).media.get(mediaId)!.parts.set(n, bytes);
    onProgress(bytes.length);
    this.fault("putPart", true);
  }
  async completeMedia(_v: string, captureId: string, mediaId: string) {
    this.calls.push("completeMedia");
    this.fault("completeMedia", false);
    const m = this.capture(captureId).media.get(mediaId)!;
    const all = new Uint8Array(m.size);
    for (const [n, b] of m.parts) all.set(b, (n - 1) * PART);
    // The server verifies the whole file before any verdict but ACCEPTED is possible.
    const ok = (await sha256(new Blob([all]))) === m.sha256;
    m.status = !ok ? "REJECTED" : this.verdict;
    if (!ok) m.rejectionCode = "CHECKSUM_MISMATCH";
    else if (this.verdict !== "ACCEPTED") m.rejectionCode = this.verdict === "REJECTED" ? "MALWARE_DETECTED" : "SCANNER_UNAVAILABLE";
  }
  mediaCount() {
    return [...this.captures.values()].reduce((n, c) => n + c.media.size, 0);
  }
}

class MemoryStore implements QueueStore {
  s = new Map<string, LocalSession>();
  f = new Map<string, LocalFile>();
  b = new Map<string, Blob>();
  quotaExceeded = false;
  async sessions(owner: string) { return [...this.s.values()].filter((x) => x.ownerKey === owner).map((x) => ({ ...x })); }
  async sessionCount() { return this.s.size; }
  async putSession(x: LocalSession) { this.s.set(x.localId, { ...x }); }
  async deleteSession(id: string) { this.s.delete(id); }
  async files(sid: string) { return [...this.f.values()].filter((x) => x.sessionLocalId === sid).map((x) => ({ ...x })); }
  async putFile(x: LocalFile) { this.f.set(x.localId, { ...x }); }
  async deleteFile(id: string) { this.f.delete(id); }
  async putBlob(id: string, blob: Blob) {
    if (this.quotaExceeded) throw Object.assign(new Error("quota"), { name: "QuotaExceededError" });
    this.b.set(id, blob);
  }
  async getBlob(id: string) { return this.b.get(id) ?? null; }
  async deleteBlob(id: string) { this.b.delete(id); }
}

async function sha256(blob: Blob): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
  return Array.from(new Uint8Array(d), (x) => x.toString(16).padStart(2, "0")).join("");
}

function world(opts: { owner?: string; free?: number | null } = {}) {
  const server = new FakeServer();
  const store = new MemoryStore();
  const env = { owner: opts.owner ?? "iss|alice", online: true, now: 1_000_000, free: opts.free === undefined ? null : opts.free };
  const make = () => new CaptureQueue({
    store, api: server, ownerKey: () => env.owner, sha256, now: () => env.now, online: () => env.online,
    freeBytes: async () => env.free, sleep: async () => {}, random: () => 1,
  });
  return { server, store, env, queue: make(), restart: make };
}

const video = (text = "0123456789") => new Blob([text], { type: "video/mp4" });

async function newSession(q: CaptureQueue, expected: string | null | undefined = "v1") {
  return q.createSession({ venueId: VENUE, venueName: "HQ", floorId: FLOOR, floorName: "Ground", expectedScanVersionId: expected, device: {} });
}

async function onlyFile(store: MemoryStore): Promise<LocalFile> {
  return [...store.f.values()][0];
}

test("offline: the session and its file are kept on the device, nothing is sent, nothing is called uploaded", async () => {
  const w = world();
  w.env.online = false;
  const s = await newSession(w.queue);
  const [r] = await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  assert.equal(r.durable, true);
  await w.queue.sync();
  assert.deepEqual(w.server.calls, []);
  const [summary] = await w.queue.list();
  assert.equal(summary.pending, 1);
  assert.equal(summary.synced, false);
  assert.equal((await onlyFile(w.store)).state, "QUEUED");
});

test("when connectivity returns the queue uploads, and success is the server's acceptance only", async () => {
  const w = world();
  w.env.online = false;
  const s = await newSession(w.queue);
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  w.env.online = true;
  await w.queue.sync();
  const f = await onlyFile(w.store);
  assert.equal(f.state, "ACCEPTED");
  assert.equal(w.store.b.size, 0, "the local copy is removed once the server holds the verified bytes");
  const [summary] = await w.queue.list();
  assert.equal(summary.synced, true);
  assert.deepEqual(w.server.calls, ["createCapture", "initMedia", "putPart:1", "putPart:2", "putPart:3", "completeMedia"]);
});

test("a browser restart keeps the queue; a copy that was only held by the closed page is reported, never invented", async () => {
  const w = world({ free: 1 }); // no room: the file is held in memory only
  const s = await newSession(w.queue);
  const [r] = await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  assert.equal(r.durable, false);
  assert.match(r.reason!, /Not enough storage/);
  w.env.online = false;
  const afterRestart = w.restart();
  w.env.online = true;
  await afterRestart.sync();
  const f = await onlyFile(w.store);
  assert.equal(f.state, "CORRUPTED");
  assert.equal(f.code, "LOCAL_COPY_MISSING");
  assert.equal(w.server.mediaCount(), 0);

  // A durable file survives the restart and uploads.
  const w2 = world();
  const s2 = await newSession(w2.queue);
  w2.env.online = false;
  await w2.queue.addFiles(s2.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  w2.env.online = true;
  await w2.restart().sync();
  assert.equal((await onlyFile(w2.store)).state, "ACCEPTED");
});

test("the device refusing to store a copy (quota) is said, and the file still uploads from this page", async () => {
  const w = world();
  w.store.quotaExceeded = true;
  const s = await newSession(w.queue);
  const [r] = await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  assert.equal(r.durable, false);
  assert.match(r.reason!, /QuotaExceededError/);
  await w.queue.sync();
  assert.equal((await onlyFile(w.store)).state, "ACCEPTED");
});

test("a partial upload resumes with only the missing parts", async () => {
  const w = world();
  const s = await newSession(w.queue);
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  w.server.calls = [];
  // Part 1 goes through; part 2 hits the network failure.
  const orig = w.server.putPart.bind(w.server);
  let n = 0;
  w.server.putPart = async (...a) => {
    if (++n === 2) throw networkError();
    return orig(...a);
  };
  await w.queue.sync();
  let f = await onlyFile(w.store);
  assert.equal(f.state, "RETRY_WAIT");
  assert.equal(f.attempts, 1);
  w.server.putPart = orig;
  w.server.faults = [];
  w.server.calls = [];
  w.env.now += BACKOFF_CAP_MS;
  await w.queue.sync();
  f = await onlyFile(w.store);
  assert.equal(f.state, "ACCEPTED");
  assert.deepEqual(w.server.calls.filter((c) => c.startsWith("putPart")), ["putPart:2", "putPart:3"], "part 1 is not sent again");
});

test("a retry after a lost answer never makes a second session or a second file", async () => {
  const w = world();
  const s = await newSession(w.queue);
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  w.server.faults.push({ op: "createCapture", error: networkError, after: true, times: 1 });
  w.server.faults.push({ op: "initMedia", error: networkError, after: true, times: 1 });
  for (let i = 0; i < 4; i++) {
    w.env.now += BACKOFF_CAP_MS;
    await w.queue.sync();
  }
  assert.equal(w.server.captures.size, 1);
  assert.equal(w.server.mediaCount(), 1);
  assert.equal((await onlyFile(w.store)).state, "ACCEPTED");
  // Syncing again sends nothing: the file is settled.
  w.server.calls = [];
  await w.queue.sync();
  assert.deepEqual(w.server.calls, []);
});

test("an expired sign-in pauses the queue without losing anything or using up attempts", async () => {
  const w = world();
  const s = await newSession(w.queue);
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  w.server.authExpired = true;
  await w.queue.sync();
  let [summary] = await w.queue.list();
  assert.equal(summary.session.authRequired, true);
  assert.equal(summary.files[0].state, "QUEUED");
  assert.equal(summary.files[0].attempts, 0);
  assert.equal(w.store.b.size, 1);
  // Still paused until the user signs in again.
  w.server.authExpired = false;
  await w.queue.sync();
  [summary] = await w.queue.list();
  assert.equal(summary.files[0].state, "QUEUED");
  await w.queue.resumeAfterSignIn();
  await w.queue.sync();
  [summary] = await w.queue.list();
  assert.equal(summary.files[0].state, "ACCEPTED");
});

test("a corrupted local copy is detected by its checksum and never sent", async () => {
  const w = world();
  const s = await newSession(w.queue);
  w.env.online = false;
  await w.queue.addFiles(s.localId, [{ file: video("0123456789"), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  const f0 = await onlyFile(w.store);
  w.store.b.set(f0.localId, video("0123456780")); // same size, one byte flipped
  w.env.online = true;
  await w.queue.sync();
  const f = await onlyFile(w.store);
  assert.equal(f.state, "CORRUPTED");
  assert.equal(f.code, "LOCAL_CHECKSUM_MISMATCH");
  assert.equal(w.server.mediaCount(), 0);
});

test("server rejection is a verdict: the file is REJECTED, kept for the user, and never reported uploaded", async () => {
  const w = world();
  w.server.verdict = "REJECTED";
  const s = await newSession(w.queue);
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  await w.queue.sync();
  const f = await onlyFile(w.store);
  assert.equal(f.state, "REJECTED");
  assert.equal(f.code, "MALWARE_DETECTED");
  assert.equal(w.store.b.size, 1, "kept until the user removes it");
  // A claim the server refuses at the door is a rejection too, not a retry.
  const s2 = await newSession(w.queue);
  await w.queue.addFiles(s2.localId, [{ file: new Blob(["MZ"]), name: "x.exe", type: "application/x-msdownload", kind: "VIDEO" }]);
  await w.queue.sync();
  const bad = (await w.store.files(s2.localId))[0];
  assert.equal(bad.state, "REJECTED");
  assert.equal(bad.code, "UNSUPPORTED_MEDIA_TYPE");
});

test("a quarantined file waits for the user to retry validation", async () => {
  const w = world();
  w.server.verdict = "QUARANTINED";
  const s = await newSession(w.queue);
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  await w.queue.sync();
  let f = await onlyFile(w.store);
  assert.equal(f.state, "QUARANTINED");
  w.server.verdict = "ACCEPTED";
  const m = [...w.server.captures.values()][0].media.get(f.mediaId!)!;
  await w.queue.retryFile(f.localId, s.localId);
  const complete = w.server.completeMedia.bind(w.server);
  w.server.completeMedia = async (...a) => {
    m.status = "ACCEPTED";
    return complete(...a);
  };
  await w.queue.sync();
  f = await onlyFile(w.store);
  assert.equal(f.state, "ACCEPTED");
});

test("recoverable failures back off exponentially within a bound, then wait for the user", async () => {
  assert.deepEqual([1, 2, 3, 4, 7, 8, 20].map((a) => backoffMs(a, () => 1)), [1000, 2000, 4000, 8000, 60000, 60000, 60000]);
  assert.equal(backoffMs(3, () => 0), 2000, "jitter takes off at most half");
  const w = world();
  const s = await newSession(w.queue);
  await w.queue.sync(); // the session is created
  w.server.faults.push({ op: "initMedia", error: () => new HttpError(503, "STORAGE_UNAVAILABLE"), times: 100 });
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  const delays: number[] = [];
  for (let i = 0; i < MAX_ATTEMPTS + 2; i++) {
    await w.queue.sync();
    const f = await onlyFile(w.store);
    if (f.state === "RETRY_WAIT") {
      delays.push(f.nextAttemptAtMs - w.env.now);
      w.env.now = f.nextAttemptAtMs;
    }
  }
  const f = await onlyFile(w.store);
  assert.equal(f.state, "FAILED");
  assert.equal(f.attempts, MAX_ATTEMPTS);
  assert.ok(delays.every((d, i) => i === 0 || d >= delays[i - 1]), "delays never shrink");
  assert.ok(delays.every((d) => d <= BACKOFF_CAP_MS));
  w.server.faults = [];
  await w.queue.retryFile(f.localId, s.localId);
  await w.queue.sync();
  assert.equal((await onlyFile(w.store)).state, "ACCEPTED");
});

test("being offline uses up no attempts", async () => {
  const w = world();
  const s = await newSession(w.queue);
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  w.env.online = false;
  for (let i = 0; i < 20; i++) await w.queue.sync();
  assert.equal((await onlyFile(w.store)).attempts, 0);
});

test("a deleted venue stops the session with a conflict and keeps every file", async () => {
  const w = world();
  w.env.online = false;
  const s = await newSession(w.queue);
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  w.server.venues.clear();
  w.env.online = true;
  await w.queue.sync();
  const [summary] = await w.queue.list();
  assert.equal(summary.session.conflict?.kind, "VENUE_UNAVAILABLE");
  assert.equal(summary.files[0].state, "QUEUED");
  assert.equal(w.store.b.size, 1);
  // Nothing is retried on its own; the user checks again once access is back.
  w.server.venues.add(VENUE);
  await w.queue.sync();
  assert.equal((await w.queue.list())[0].session.conflict?.kind, "VENUE_UNAVAILABLE");
  await w.queue.resolve(s.localId, "check-again");
  await w.queue.sync();
  assert.equal((await onlyFile(w.store)).state, "ACCEPTED");
});

test("a server session that disappeared is a conflict; the user may upload into a new session", async () => {
  const w = world();
  const s = await newSession(w.queue);
  await w.queue.sync(); // created on the server
  w.server.captures.clear();
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  await w.queue.sync();
  const [summary] = await w.queue.list();
  assert.equal(summary.session.conflict?.kind, "CAPTURE_NOT_FOUND");
  assert.equal(summary.files[0].state, "QUEUED", "the file is kept, not dropped");
  const oldClientId = summary.session.clientSessionId;
  await w.queue.resolve(s.localId, "new-session");
  await w.queue.sync();
  const [after] = await w.queue.list();
  assert.notEqual(after.session.clientSessionId, oldClientId);
  assert.equal(after.files[0].state, "ACCEPTED");
});

test("a session closed elsewhere is a conflict, not a silent failure", async () => {
  const w = world();
  const s = await newSession(w.queue);
  await w.queue.sync();
  [...w.server.captures.values()][0].status = "READY_FOR_PROCESSING";
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  await w.queue.sync();
  assert.equal((await w.queue.list())[0].session.conflict?.kind, "SESSION_CLOSED");
});

test("an erased capture is never uploaded again: only discarding is offered", async () => {
  const w = world();
  w.env.online = false;
  const s = await newSession(w.queue);
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  w.server.erased.add(s.clientSessionId);
  w.env.online = true;
  await w.queue.sync();
  assert.equal((await w.queue.list())[0].session.conflict?.kind, "CAPTURE_ERASED");
  await assert.rejects(() => w.queue.resolve(s.localId, "new-session"), /erased/);
  await w.queue.discardSession(s.localId);
  assert.equal(w.store.s.size, 0);
  assert.equal(w.store.b.size, 0);
});

test("a floor republished since the session was recorded needs the user's confirmation", async () => {
  const w = world();
  w.env.online = false;
  const s = await newSession(w.queue, "v1");
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  w.server.floorVersion = "v2";
  w.env.online = true;
  await w.queue.sync();
  assert.equal((await w.queue.list())[0].session.conflict?.kind, "SCAN_VERSION_CHANGED");
  assert.equal(w.server.captures.size, 0);
  await w.queue.resolve(s.localId, "continue");
  await w.queue.sync();
  assert.equal((await onlyFile(w.store)).state, "ACCEPTED");
});

test("one account's queued data is never listed or synchronised for another account", async () => {
  const w = world({ owner: "iss|alice" });
  w.env.online = false;
  const s = await newSession(w.queue);
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  w.env.owner = "iss|bob";
  w.env.online = true;
  assert.deepEqual(await w.queue.list(), []);
  await w.queue.sync();
  assert.deepEqual(w.server.calls, []);
  await assert.rejects(() => w.queue.addFiles(s.localId, [{ file: video(), name: "x.mp4", type: "video/mp4", kind: "VIDEO" }]));
  w.env.owner = "iss|alice";
  await w.queue.sync();
  assert.equal((await onlyFile(w.store)).state, "ACCEPTED");
});

test("nothing the queue stores is a credential", async () => {
  const w = world();
  const s = await newSession(w.queue);
  await w.queue.addFiles(s.localId, [{ file: video(), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);
  const stored = JSON.stringify([...w.store.s.values(), ...w.store.f.values()]);
  assert.doesNotMatch(stored, /token|bearer|authorization|password/i);
});

test("error classes", () => {
  assert.equal(classifyError(new NotSignedInError()).kind, "AUTH");
  assert.equal(classifyError(new HttpError(401, "HTTP_401")).kind, "AUTH");
  assert.equal(classifyError(new TypeError("Failed to fetch")).kind, "RETRY");
  assert.equal(classifyError(new HttpError(429, "TOO_MANY")).kind, "RETRY");
  assert.equal(classifyError(new HttpError(503, "STORAGE_UNAVAILABLE")).kind, "RETRY");
  assert.equal(classifyError(new HttpError(404, "NOT_FOUND")).kind, "NOT_FOUND");
  assert.deepEqual((classifyError(new HttpError(410, "CAPTURE_ERASED")) as { conflict: string }).conflict, "CAPTURE_ERASED");
  assert.equal(classifyError(new HttpError(415, "UNSUPPORTED_MEDIA_TYPE")).kind, "REFUSED");
});


test("backend cold starts (502, 503, 504) during session creation, media init and part upload recover cleanly", async () => {
  const w = world();
  const s = await w.queue.createSession({
    venueId: VENUE,
    venueName: "HQ",
    floorId: FLOOR,
    floorName: "Ground",
    roomId: "room-lobby",
    roomName: "Main Lobby",
    expectedScanVersionId: "v1",
    device: { source: "browser", screen: "320x568" },
  });
  await w.queue.addFiles(s.localId, [
    { file: video("0123456789ab"), name: "lobby.mp4", type: "video/mp4", kind: "VIDEO" },
    { file: new Blob(['{"fps":30}'], { type: "application/json" }), name: "meta.json", type: "application/json", kind: "METADATA" },
  ]);

  // Inject backend cold-start errors (502 Bad Gateway, 503 Service Unavailable, 504 Gateway Timeout)
  w.server.faults.push({ op: "createCapture", error: () => new HttpError(503, "BACKEND_COLD_START"), times: 1 });
  w.server.faults.push({ op: "initMedia", error: () => new HttpError(502, "BAD_GATEWAY"), times: 1 });
  w.server.faults.push({ op: "putPart", error: () => new HttpError(504, "GATEWAY_TIMEOUT"), times: 1 });

  for (let round = 0; round < 6; round++) {
    w.env.now += BACKOFF_CAP_MS;
    await w.queue.sync();
  }

  const [summary] = await w.queue.list();
  assert.equal(summary.synced, true);
  assert.equal(summary.accepted, 2);
  assert.equal(summary.session.roomId, "room-lobby");
  assert.equal(summary.session.floorId, FLOOR);
  assert.equal(summary.session.expectedScanVersionId, "v1");
  assert.equal(w.server.captures.size, 1);
  assert.equal(w.server.mediaCount(), 2);
});

test("concurrent duplicate sync calls are coalesced and never duplicate uploads or parts", async () => {
  const w = world();
  const s = await newSession(w.queue);
  await w.queue.addFiles(s.localId, [{ file: video("0123456789"), name: "walk.mp4", type: "video/mp4", kind: "VIDEO" }]);

  // Trigger 5 concurrent sync requests simultaneously (e.g. rapid user taps or simultaneous online events)
  await Promise.all([
    w.queue.sync(),
    w.queue.sync(),
    w.queue.sync(),
    w.queue.sync(),
    w.queue.sync(),
  ]);

  const f = await onlyFile(w.store);
  assert.equal(f.state, "ACCEPTED");
  assert.equal(w.server.captures.size, 1);
  assert.equal(w.server.mediaCount(), 1);
  // Verify putPart was called exactly once per part (3 parts for 10 bytes with PART=4)
  const partPuts = w.server.calls.filter((c) => c.startsWith("putPart"));
  assert.deepEqual(partPuts, ["putPart:1", "putPart:2", "putPart:3"]);
});

test("slow uploads interrupted by network loss resume with remaining parts upon reconnection", async () => {
  const w = world();
  const s = await newSession(w.queue);
  await w.queue.addFiles(s.localId, [{ file: video("0123456789abcdef"), name: "large.mp4", type: "video/mp4", kind: "VIDEO" }]);

  // Simulate slow upload where part 1 succeeds, part 2 drops connection mid-flight
  const orig = w.server.putPart.bind(w.server);
  let putCount = 0;
  w.server.putPart = async (...a) => {
    putCount++;
    if (putCount === 2) {
      w.env.online = false; // network drop
      throw networkError();
    }
    return orig(...a);
  };

  await w.queue.sync();
  let f = await onlyFile(w.store);
  assert.equal(f.state, "RETRY_WAIT");
  assert.equal(f.attempts, 0, "offline mid-transfer uses up zero retry attempts");

  // Network returns, sync triggered
  w.server.putPart = orig;
  w.server.calls = [];
  w.env.online = true;
  await w.queue.sync();

  f = await onlyFile(w.store);
  assert.equal(f.state, "ACCEPTED");
  // Only remaining parts (2, 3, 4) sent on resumption
  const resumedParts = w.server.calls.filter((c) => c.startsWith("putPart"));
  assert.deepEqual(resumedParts, ["putPart:2", "putPart:3", "putPart:4"]);
});
