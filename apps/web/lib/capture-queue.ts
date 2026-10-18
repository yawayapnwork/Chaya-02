// The durable capture upload queue (docs/capture-ingestion.md, "Offline capture and the local upload queue").
//
// A capture session and its files are recorded on the device first, then synchronised with the server. Nothing is ever
// reported as uploaded until the server says so: a file is ACCEPTED only when the server's own validation (size, SHA-256
// of the stored bytes, malware scan, content type) accepted it.
//
//   identity     every record belongs to one signed-in account (ownerKey: issuer + subject) and is only ever listed or
//                synchronised for that account. The queue never stores a token: requests take the in-memory token of
//                whoever is signed in now, and an expired sign-in pauses the queue (AUTH_REQUIRED) without losing data.
//   idempotency  a session carries the clientSessionId it was recorded under and a file its clientFileId; the server
//                returns what an earlier attempt created instead of creating it again (V38), so a retry after a lost
//                answer never makes a second session or publishes a file twice.
//   integrity    the file's SHA-256 is computed when it is queued, every part is sent with its own SHA-256, the stored
//                copy is hashed again before it is sent (a damaged or evicted local copy is CORRUPTED, never sent), and
//                the server verifies the whole file before accepting it.
//   retries      recoverable failures (no connection, 5xx, 408, 429) wait with bounded exponential backoff and jitter, at
//                most MAX_ATTEMPTS times, then the file is FAILED until the user retries. Being offline costs no attempt.
//   conflicts    a deleted venue, a capture that is gone, erased or no longer accepting files, or a floor that published
//                another scan version since the session was recorded stop that session with a CONFLICT the user must
//                resolve explicitly. The local data is kept until the user chooses to discard it.
//
// Pure apart from the injected store, API, hashing and clock, so every rule is unit-tested in node (capture-queue.test.ts);
// lib/capture-queue-idb.ts is the IndexedDB store the browser uses.

export const MAX_ATTEMPTS = 8;
export const BACKOFF_BASE_MS = 1_000;
export const BACKOFF_CAP_MS = 60_000;
/** Kept free on the device beyond a file before it is stored there. */
export const STORAGE_MARGIN_BYTES = 64 * 1024 * 1024;
/** Unsynchronised sessions older than this are flagged so the user syncs or discards them (they are never deleted
 * automatically: they may be the only copy). */
export const LOCAL_RETENTION_MS = 14 * 24 * 3600 * 1000;

// ---- records -------------------------------------------------------------------------------------------------------

export type ConflictKind =
  | "VENUE_UNAVAILABLE" // the venue was deleted, or this account no longer has access to it
  | "CAPTURE_NOT_FOUND" // the server-side session is gone
  | "CAPTURE_ERASED" // the session was erased: it is never recreated
  | "SESSION_CLOSED" // the session no longer accepts files (finished elsewhere, failed)
  | "SCAN_VERSION_CHANGED" // the floor published another scan version since the session was recorded
  | "FLOOR_UNAVAILABLE" // the floor was deleted
  | "ID_CONFLICT"; // the server holds another session or file under this client id

export interface Conflict {
  kind: ConflictKind;
  code: string;
  message: string;
  detectedAt: number;
}

export interface LocalSession {
  localId: string;
  /** Sent with the creation request; the server answers a repeat with the same session (V38). */
  clientSessionId: string;
  ownerKey: string;
  venueId: string;
  venueName: string;
  floorId: string | null;
  floorName: string | null;
  /** The room of the floor this session captures on its own (docs/multi-room.md); absent in sessions recorded before. */
  roomId?: string | null;
  roomName?: string | null;
  /** The floor's published scan version when the session was recorded: null none, undefined not known. */
  expectedScanVersionId: string | null | undefined;
  /** The user confirmed continuing although the floor published another version. */
  scanVersionChangeAccepted: boolean;
  device: Record<string, unknown>;
  startedAt: string;
  createdAtMs: number;
  serverCaptureId: string | null;
  conflict: Conflict | null;
  /** Sync of this session is paused until the user signs in again. */
  authRequired: boolean;
  /** Session creation: attempts and backoff, like a file's. */
  attempts: number;
  nextAttemptAtMs: number;
  lastError: string | null;
}

export type FileState =
  | "QUEUED" // waiting to be sent
  | "UPLOADING"
  | "VALIDATING" // all bytes sent; the server is checking them
  | "RETRY_WAIT" // a recoverable failure; retried at nextAttemptAtMs
  | "FAILED" // retries exhausted: waits for the user
  | "ACCEPTED" // the server accepted it (the only success state)
  | "REJECTED" // the server refused it (a verdict, not a transfer problem)
  | "QUARANTINED" // the server could not check it yet (e.g. scanner down); retry validation later
  | "CORRUPTED" // the local copy no longer matches its checksum, or is gone
  | "CONFLICT"; // the server holds another file under this client id

export interface LocalFile {
  /** Also the clientFileId the server deduplicates on. */
  localId: string;
  sessionLocalId: string;
  ownerKey: string;
  name: string;
  type: string;
  kind: "VIDEO" | "IMAGE" | "METADATA";
  size: number;
  sha256: string;
  /** Whether the bytes are stored on the device (survive a restart) or only held by this tab. */
  durable: boolean;
  mediaId: string | null;
  state: FileState;
  attempts: number;
  nextAttemptAtMs: number;
  code: string | null;
  message: string | null;
  addedAtMs: number;
  settledAtMs: number | null;
}

// ---- dependencies ---------------------------------------------------------------------------------------------------

export interface QueueStore {
  sessions(ownerKey: string): Promise<LocalSession[]>;
  /** Every owner's session count, for the signed-out notice (no details of anyone's data). */
  sessionCount(): Promise<number>;
  putSession(s: LocalSession): Promise<void>;
  deleteSession(localId: string): Promise<void>;
  files(sessionLocalId: string): Promise<LocalFile[]>;
  putFile(f: LocalFile): Promise<void>;
  deleteFile(localId: string): Promise<void>;
  /** Stores the bytes durably; throws QuotaExceededError (or similar) when the device refuses. */
  putBlob(localId: string, blob: Blob): Promise<void>;
  getBlob(localId: string): Promise<Blob | null>;
  deleteBlob(localId: string): Promise<void>;
}

export interface ServerCapture {
  id: string;
  status: string;
}

export interface InitAnswer {
  mediaId: string;
  partSizeBytes: number;
  totalParts: number;
  existing?: boolean;
  status?: string;
}

export interface ServerMedia {
  status: "PENDING" | "VALIDATING" | "ACCEPTED" | "REJECTED" | "QUARANTINED";
  partSizeBytes: number;
  uploadedParts: number[];
  rejectionCode: string | null;
  rejectionMessage: string | null;
}

/** The capture API, as the queue uses it. Errors carry `status` and `code` (lib/capture-api ApiError); a missing
 * sign-in is an error named NotSignedInError. */
export interface QueueApi {
  createCapture(venueId: string, body: {
    floorId?: string; roomId?: string; device: Record<string, unknown>; startedAt: string; clientSessionId: string;
    expectedScanVersion?: { versionId: string | null };
  }): Promise<ServerCapture>;
  getCapture(venueId: string, captureId: string): Promise<ServerCapture>;
  listVenues(): Promise<{ id: string }[]>;
  initMedia(venueId: string, captureId: string, body: {
    kind: string; filename: string; contentType: string; sizeBytes: number; sha256: string; clientFileId: string;
  }): Promise<InitAnswer>;
  getMedia(venueId: string, captureId: string, mediaId: string): Promise<ServerMedia>;
  putPart(venueId: string, captureId: string, mediaId: string, partNumber: number, data: Blob, sha256: string,
          onProgress: (loaded: number) => void): Promise<void>;
  completeMedia(venueId: string, captureId: string, mediaId: string): Promise<void>;
}

export interface QueueDeps {
  store: QueueStore;
  api: QueueApi;
  /** The signed-in account's key, or null when nobody is signed in. */
  ownerKey: () => string | null;
  sha256: (blob: Blob, onProgress?: (fraction: number) => void) => Promise<string>;
  now: () => number;
  online: () => boolean;
  /** Free bytes the device will still give this origin, or null when the browser does not say. */
  freeBytes: () => Promise<number | null>;
  /** Waits between polls of a file's verdict (tests pass a no-op). */
  sleep: (ms: number) => Promise<void>;
  random?: () => number;
}

// ---- pure rules -----------------------------------------------------------------------------------------------------

/** Delay before attempt `attempt` (1-based) is retried: BASE * 2^(attempt-1), capped, with up to 50% jitter off. */
export function backoffMs(attempt: number, random: () => number = Math.random): number {
  const raw = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * 2 ** Math.max(0, attempt - 1));
  return Math.round(raw * (0.5 + 0.5 * random()));
}

export type ErrorClass =
  | { kind: "RETRY"; code: string; message: string }
  | { kind: "AUTH"; code: string; message: string }
  | { kind: "NOT_FOUND"; code: string; message: string }
  | { kind: "CONFLICT"; conflict: ConflictKind; code: string; message: string }
  | { kind: "REFUSED"; code: string; message: string };

/** What a failed request means for the queue. */
export function classifyError(e: unknown): ErrorClass {
  const err = e as { name?: string; status?: number; code?: string; message?: string } | null;
  const message = err?.message ?? String(e);
  const status = typeof err?.status === "number" ? err.status : null;
  const code = err?.code ?? (status !== null ? `HTTP_${status}` : err?.name ?? "ERROR");
  const name = err?.name ?? (err?.constructor as { name?: string } | undefined)?.name;
  if (name === "NotSignedInError" || (e as object)?.constructor?.name === "NotSignedInError" || status === 401) {
    return { kind: "AUTH", code: "SESSION_EXPIRED", message };
  }
  if (status === null || status === 0 || status >= 500 || status === 408 || status === 429) {
    return { kind: "RETRY", code, message };
  }
  if (status === 404) return { kind: "NOT_FOUND", code, message };
  if (status === 410 || code === "CAPTURE_ERASED") return { kind: "CONFLICT", conflict: "CAPTURE_ERASED", code, message };
  if (code === "SCAN_VERSION_CHANGED") return { kind: "CONFLICT", conflict: "SCAN_VERSION_CHANGED", code, message };
  if (code === "CAPTURE_NOT_ACCEPTING_UPLOADS" || code === "INVALID_CAPTURE_TRANSITION") {
    return { kind: "CONFLICT", conflict: "SESSION_CLOSED", code, message };
  }
  if (code === "CLIENT_SESSION_CONFLICT" || code === "CLIENT_FILE_CONFLICT") return { kind: "CONFLICT", conflict: "ID_CONFLICT", code, message };
  if (status === 403) return { kind: "CONFLICT", conflict: "VENUE_UNAVAILABLE", code, message };
  return { kind: "REFUSED", code, message };
}

/** Whether a file of `size` bytes may be stored durably, given what the device still offers. Unknown: try (the store
 * still refuses with a quota error if it must). */
export function fitsOnDevice(size: number, free: number | null): boolean {
  return free === null || size + STORAGE_MARGIN_BYTES <= free;
}

export const SETTLED: readonly FileState[] = ["ACCEPTED", "REJECTED"];
const PENDING: readonly FileState[] = ["QUEUED", "UPLOADING", "VALIDATING", "RETRY_WAIT"];

export interface SessionSummary {
  session: LocalSession;
  files: LocalFile[];
  pending: number;
  failed: number;
  accepted: number;
  /** Synchronised: the server holds the session, and every file has the server's final verdict. */
  synced: boolean;
  /** Not yet synchronised and older than LOCAL_RETENTION_MS. */
  overdue: boolean;
}

export function summarize(session: LocalSession, files: LocalFile[], now: number): SessionSummary {
  const pending = files.filter((f) => PENDING.includes(f.state)).length;
  const failed = files.filter((f) => ["FAILED", "CORRUPTED", "CONFLICT", "QUARANTINED"].includes(f.state)).length;
  const accepted = files.filter((f) => f.state === "ACCEPTED").length;
  const synced = session.serverCaptureId !== null && session.conflict === null && files.length > 0
    && files.every((f) => SETTLED.includes(f.state));
  return { session, files, pending, failed, accepted, synced, overdue: !synced && now - session.createdAtMs > LOCAL_RETENTION_MS };
}

// ---- the queue ------------------------------------------------------------------------------------------------------

type Listener = () => void;

export class CaptureQueue {
  private readonly d: QueueDeps;
  /** Bytes of files that could not be stored on the device: usable only while this tab lives. */
  private readonly memory = new Map<string, Blob>();
  /** Upload progress of files in flight (0..1); not persisted. */
  readonly progress = new Map<string, number>();
  private readonly listeners = new Set<Listener>();
  private running: Promise<void> | null = null;
  private rerun = false;

  constructor(deps: QueueDeps) {
    this.d = deps;
  }

  subscribe(l: Listener): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  private emit(): void {
    for (const l of this.listeners) l();
  }

  /** The signed-in account's sessions, newest first. */
  async list(): Promise<SessionSummary[]> {
    const owner = this.d.ownerKey();
    if (!owner) return [];
    const now = this.d.now();
    const out: SessionSummary[] = [];
    for (const s of await this.d.store.sessions(owner)) out.push(summarize(s, await this.d.store.files(s.localId), now));
    return out.sort((a, b) => b.session.createdAtMs - a.session.createdAtMs);
  }

  /** The bytes of a file this tab can read (for calibration marking), stored or held in memory. */
  async blobOf(fileLocalId: string): Promise<Blob | null> {
    return this.memory.get(fileLocalId) ?? (await this.d.store.getBlob(fileLocalId));
  }

  private requireOwner(): string {
    const owner = this.d.ownerKey();
    if (!owner) throw new Error("Sign in before recording a capture: queued data always belongs to a signed-in account.");
    return owner;
  }

  /** Records a session on the device. It is created on the server by the next synchronisation. */
  async createSession(args: {
    venueId: string; venueName: string; floorId: string | null; floorName: string | null;
    roomId?: string | null; roomName?: string | null;
    expectedScanVersionId: string | null | undefined; device: Record<string, unknown>; serverCaptureId?: string;
  }): Promise<LocalSession> {
    const owner = this.requireOwner();
    const now = this.d.now();
    const s: LocalSession = {
      localId: crypto.randomUUID(), clientSessionId: crypto.randomUUID(), ownerKey: owner,
      venueId: args.venueId, venueName: args.venueName, floorId: args.floorId, floorName: args.floorName,
      roomId: args.floorId ? args.roomId ?? null : null, roomName: args.floorId ? args.roomName ?? null : null,
      expectedScanVersionId: args.expectedScanVersionId, scanVersionChangeAccepted: false, device: args.device,
      startedAt: new Date(now).toISOString(), createdAtMs: now, serverCaptureId: args.serverCaptureId ?? null, conflict: null,
      authRequired: false, attempts: 0, nextAttemptAtMs: 0, lastError: null,
    };
    await this.d.store.putSession(s);
    this.emit();
    return s;
  }

  /**
   * Queues files for a session: each is hashed, then stored on the device if it fits (durable) or held by this tab only
   * (not durable, said so). Returns, per file, whether it was stored durably and why not.
   */
  async addFiles(sessionLocalId: string, files: { file: Blob; name: string; type: string; kind: LocalFile["kind"] }[],
                 onHash?: (name: string, fraction: number) => void): Promise<{ name: string; durable: boolean; reason: string | null }[]> {
    const owner = this.requireOwner();
    const session = (await this.d.store.sessions(owner)).find((s) => s.localId === sessionLocalId);
    if (!session) throw new Error("This capture session is not on this device for the signed-in account.");
    const results: { name: string; durable: boolean; reason: string | null }[] = [];
    for (const f of files) {
      const localId = crypto.randomUUID();
      const sha256 = await this.d.sha256(f.file, (p) => onHash?.(f.name, p));
      let durable = false;
      let reason: string | null = null;
      if (!fitsOnDevice(f.file.size, await this.d.freeBytes())) {
        reason = "Not enough storage on this device to keep a copy: it uploads while this page stays open.";
      } else {
        try {
          await this.d.store.putBlob(localId, f.file);
          durable = true;
        } catch (e) {
          reason = `This device refused to store a copy (${(e as Error)?.name ?? "error"}): it uploads while this page stays open.`;
        }
      }
      if (!durable) this.memory.set(localId, f.file);
      const now = this.d.now();
      await this.d.store.putFile({
        localId, sessionLocalId, ownerKey: owner, name: f.name, type: f.type, kind: f.kind, size: f.file.size, sha256, durable,
        mediaId: null, state: "QUEUED", attempts: 0, nextAttemptAtMs: 0, code: null, message: reason, addedAtMs: now, settledAtMs: null,
      });
      results.push({ name: f.name, durable, reason });
    }
    this.emit();
    return results;
  }

  // ---- user actions ----

  /** Retry now: a FAILED, waiting or quarantined file, or a session paused by a sign-in that has since been renewed. */
  async retryFile(fileLocalId: string, sessionLocalId: string): Promise<void> {
    const f = (await this.d.store.files(sessionLocalId)).find((x) => x.localId === fileLocalId);
    if (!f || !["FAILED", "RETRY_WAIT", "QUARANTINED"].includes(f.state)) return;
    await this.d.store.putFile({ ...f, state: f.state === "QUARANTINED" ? "VALIDATING" : "QUEUED", attempts: 0, nextAttemptAtMs: 0,
      message: null, code: f.state === "QUARANTINED" ? "RETRY_VALIDATION" : null });
    this.emit();
  }

  async resumeAfterSignIn(): Promise<void> {
    const owner = this.d.ownerKey();
    if (!owner) return;
    for (const s of await this.d.store.sessions(owner)) {
      if (s.authRequired) await this.d.store.putSession({ ...s, authRequired: false, nextAttemptAtMs: 0 });
    }
    this.emit();
  }

  /**
   * Explicit conflict resolutions. Nothing here runs on its own: each is a user's decision.
   *  - "continue": the floor published another scan version; upload anyway (no expectation is sent again).
   *  - "new-session": the server-side session is gone or closed; upload the same files into a new session. Never offered
   *    for an erased capture: erasure is final.
   *  - "check-again": the venue was unavailable; try again (access may have been restored).
   */
  async resolve(sessionLocalId: string, action: "continue" | "new-session" | "check-again"): Promise<void> {
    const owner = this.requireOwner();
    const s = (await this.d.store.sessions(owner)).find((x) => x.localId === sessionLocalId);
    if (!s?.conflict) return;
    if (action === "new-session" && s.conflict.kind === "CAPTURE_ERASED") {
      throw new Error("An erased capture is not uploaded again.");
    }
    if (action === "continue" && s.conflict.kind !== "SCAN_VERSION_CHANGED") return;
    const next: LocalSession = { ...s, conflict: null, attempts: 0, nextAttemptAtMs: 0, lastError: null };
    if (action === "continue") next.scanVersionChangeAccepted = true;
    if (action === "new-session") {
      next.clientSessionId = crypto.randomUUID();
      next.serverCaptureId = null;
      for (const f of await this.d.store.files(s.localId)) {
        if (!SETTLED.includes(f.state) || f.state === "ACCEPTED") {
          // Accepted files belonged to the old session; they are sent again into the new one only if their bytes are here.
          const bytes = await this.blobOf(f.localId);
          await this.d.store.putFile({ ...f, localId: f.localId, mediaId: null, attempts: 0, nextAttemptAtMs: 0,
            state: bytes ? "QUEUED" : "CORRUPTED", code: bytes ? null : "LOCAL_COPY_MISSING",
            message: bytes ? null : "The local copy is no longer on this device.", settledAtMs: null });
        }
      }
    }
    await this.d.store.putSession(next);
    this.emit();
  }

  /** Discards a session's local data (metadata and stored bytes). The server's copy, if any, is not touched. */
  async discardSession(sessionLocalId: string): Promise<void> {
    for (const f of await this.d.store.files(sessionLocalId)) await this.discardFileData(f);
    await this.d.store.deleteSession(sessionLocalId);
    this.emit();
  }

  /** Removes one file from the device (a corrupted, rejected or unwanted local copy). */
  async discardFile(fileLocalId: string, sessionLocalId: string): Promise<void> {
    const f = (await this.d.store.files(sessionLocalId)).find((x) => x.localId === fileLocalId);
    if (f) await this.discardFileData(f);
    this.emit();
  }

  private async discardFileData(f: LocalFile): Promise<void> {
    this.memory.delete(f.localId);
    this.progress.delete(f.localId);
    await this.d.store.deleteBlob(f.localId);
    await this.d.store.deleteFile(f.localId);
  }

  // ---- synchronisation ----

  /** Runs a synchronisation pass now, or once more after the one in progress. */
  sync(): Promise<void> {
    if (this.running) {
      this.rerun = true;
      return this.running;
    }
    this.running = (async () => {
      try {
        do {
          this.rerun = false;
          await this.syncOnce();
        } while (this.rerun);
      } finally {
        this.running = null;
      }
    })();
    return this.running;
  }

  /** The earliest time any waiting work becomes due, for the caller's timer (null: nothing waits). */
  async nextDueAtMs(): Promise<number | null> {
    let next: number | null = null;
    for (const { session, files } of await this.list()) {
      if (session.conflict || session.authRequired) continue;
      if (!session.serverCaptureId && session.nextAttemptAtMs > 0) next = Math.min(next ?? Infinity, session.nextAttemptAtMs);
      for (const f of files) if (f.state === "RETRY_WAIT") next = Math.min(next ?? Infinity, f.nextAttemptAtMs);
    }
    return next;
  }

  /** One pass over the signed-in account's queue: creates sessions, then sends due files one at a time. */
  async syncOnce(): Promise<void> {
    const owner = this.d.ownerKey();
    if (!owner || !this.d.online()) return;
    for (const s0 of await this.d.store.sessions(owner)) {
      if (!this.d.online()) return;
      let s = s0;
      if (s.conflict || s.authRequired || s.ownerKey !== owner) continue;
      if (!s.serverCaptureId) {
        if (s.nextAttemptAtMs > this.d.now()) continue;
        const created = await this.createOnServer(s);
        if (!created) continue;
        s = created;
      }
      for (const f of await this.d.store.files(s.localId)) {
        if (!this.d.online()) return;
        const due = f.state === "QUEUED" || f.state === "UPLOADING" || f.state === "VALIDATING"
          || (f.state === "RETRY_WAIT" && f.nextAttemptAtMs <= this.d.now());
        if (!due) continue;
        const stop = await this.sendFile(s, f);
        if (stop) break; // the session hit a conflict or lost its sign-in: nothing more is sent for it now
      }
    }
  }

  private async createOnServer(s: LocalSession): Promise<LocalSession | null> {
    try {
      const expect = s.expectedScanVersionId !== undefined && s.floorId && !s.scanVersionChangeAccepted
        ? { expectedScanVersion: { versionId: s.expectedScanVersionId } } : {};
      const c = await this.d.api.createCapture(s.venueId, {
        ...(s.floorId ? { floorId: s.floorId } : {}), ...(s.floorId && s.roomId ? { roomId: s.roomId } : {}),
        device: s.device, startedAt: s.startedAt, clientSessionId: s.clientSessionId,
        ...expect,
      });
      const next = { ...s, serverCaptureId: c.id, attempts: 0, nextAttemptAtMs: 0, lastError: null };
      await this.d.store.putSession(next);
      this.emit();
      return next;
    } catch (e) {
      await this.sessionFailed(s, e, "create");
      return null;
    }
  }

  /** Records a session-level failure: a pause for sign-in, a backoff, or a conflict. */
  private async sessionFailed(s: LocalSession, e: unknown, during: "create" | "file"): Promise<void> {
    const c = classifyError(e);
    let next: LocalSession = s;
    if (c.kind === "AUTH") {
      next = { ...s, authRequired: true, lastError: "Sign in again to continue uploading. Everything stays on this device." };
    } else if (c.kind === "RETRY") {
      const attempts = s.attempts + 1;
      next = { ...s, attempts, nextAttemptAtMs: this.d.now() + backoffMs(Math.min(attempts, MAX_ATTEMPTS), this.d.random), lastError: c.message };
    } else if (c.kind === "CONFLICT") {
      next = { ...s, conflict: { kind: c.conflict, code: c.code, message: c.message, detectedAt: this.d.now() } };
    } else if (c.kind === "NOT_FOUND") {
      next = { ...s, conflict: await this.whatIsGone(s, c, during) };
    } else {
      next = { ...s, conflict: { kind: "ID_CONFLICT", code: c.code, message: c.message, detectedAt: this.d.now() } };
    }
    await this.d.store.putSession(next);
    this.emit();
  }

  /** A 404 means the venue, the floor or the session is gone (or no longer visible to this account). */
  private async whatIsGone(s: LocalSession, c: { code: string; message: string }, during: "create" | "file"): Promise<Conflict> {
    const now = this.d.now();
    let venueVisible = true;
    try {
      venueVisible = (await this.d.api.listVenues()).some((v) => v.id === s.venueId);
    } catch {
      /* unknown: report the session problem */
    }
    if (!venueVisible) {
      return { kind: "VENUE_UNAVAILABLE", code: c.code, message: "The venue was deleted, or this account no longer has access to it.", detectedAt: now };
    }
    if (during === "create") {
      return { kind: "FLOOR_UNAVAILABLE", code: c.code, message: c.message || "The floor no longer exists.", detectedAt: now };
    }
    return { kind: "CAPTURE_NOT_FOUND", code: c.code, message: "This capture session no longer exists on the server.", detectedAt: now };
  }

  private async putFile(f: LocalFile): Promise<void> {
    await this.d.store.putFile(f);
    this.emit();
  }

  /** Sends one file as far as it can go now. Returns true when the whole session must stop (conflict, sign-in). */
  private async sendFile(s: LocalSession, f0: LocalFile): Promise<boolean> {
    const venue = s.venueId;
    const capture = s.serverCaptureId as string;
    let f = f0;
    try {
      // Only verified local bytes are ever sent.
      if (!f.mediaId || f.state !== "VALIDATING") {
        const bytes = await this.blobOf(f.localId);
        if (!bytes) {
          await this.putFile({ ...f, state: "CORRUPTED", code: "LOCAL_COPY_MISSING", message: f.durable
            ? "The copy stored on this device is gone (the browser may have cleared site storage). Add the file again."
            : "The file was held by a page that has since closed. Add the file again." });
          return false;
        }
        if (bytes.size !== f.size || (await this.d.sha256(bytes)) !== f.sha256) {
          await this.putFile({ ...f, state: "CORRUPTED", code: "LOCAL_CHECKSUM_MISMATCH",
            message: "The copy on this device no longer matches the checksum taken when it was added. It was not sent." });
          return false;
        }
        let mediaId = f.mediaId;
        let partSize: number;
        if (!mediaId) {
          const init = await this.d.api.initMedia(venue, capture, {
            kind: f.kind, filename: f.name, contentType: f.type, sizeBytes: f.size, sha256: f.sha256, clientFileId: f.localId,
          });
          mediaId = init.mediaId;
          partSize = init.partSizeBytes;
          f = { ...f, mediaId, state: "UPLOADING" };
          await this.putFile(f);
          if (init.existing && init.status && init.status !== "PENDING") {
            return await this.awaitVerdict(s, f);
          }
        }
        const media = await this.d.api.getMedia(venue, capture, mediaId);
        if (media.status !== "PENDING") return await this.awaitVerdict(s, { ...f, state: "VALIDATING" });
        partSize = media.partSizeBytes;
        const have = new Set(media.uploadedParts);
        f = { ...f, state: "UPLOADING" };
        await this.putFile(f);
        let sent = 0;
        for (let start = 0, n = 1; start < f.size; start += partSize, n++) {
          const end = Math.min(f.size, start + partSize);
          if (have.has(n)) {
            sent += end - start;
            continue;
          }
          const part = bytes.slice(start, end);
          const partSha = await this.d.sha256(part);
          const base = sent;
          await this.d.api.putPart(venue, capture, mediaId, n, part, partSha, (loaded) => {
            this.progress.set(f.localId, (base + loaded) / f.size);
            this.emit();
          });
          sent += end - start;
          this.progress.set(f.localId, sent / f.size);
          this.emit();
        }
        await this.d.api.completeMedia(venue, capture, mediaId);
        f = { ...f, state: "VALIDATING" };
        await this.putFile(f);
      } else if (f.code === "RETRY_VALIDATION") {
        await this.d.api.completeMedia(venue, capture, f.mediaId);
      }
      return await this.awaitVerdict(s, f);
    } catch (e) {
      return await this.fileFailed(s, f, e);
    }
  }

  /** Polls the server's verdict. ACCEPTED is the only success. The local copy is removed once the server has accepted
   * the file (it holds the verified bytes); a rejected or quarantined file keeps its copy until the user decides. */
  private async awaitVerdict(s: LocalSession, f: LocalFile): Promise<boolean> {
    for (let i = 0; ; i++) {
      const m = await this.d.api.getMedia(s.venueId, s.serverCaptureId as string, f.mediaId as string);
      if (m.status === "ACCEPTED") {
        this.progress.delete(f.localId);
        this.memory.delete(f.localId);
        await this.d.store.deleteBlob(f.localId);
        await this.putFile({ ...f, state: "ACCEPTED", durable: false, code: null, message: null, settledAtMs: this.d.now() });
        return false;
      }
      if (m.status === "REJECTED" || m.status === "QUARANTINED") {
        this.progress.delete(f.localId);
        await this.putFile({ ...f, state: m.status, code: m.rejectionCode, message: m.rejectionMessage ?? m.rejectionCode,
          settledAtMs: m.status === "REJECTED" ? this.d.now() : null });
        return false;
      }
      if (m.status === "PENDING" && i > 0) {
        // The server lost the completion (e.g. restarted before it was recorded): send the file again from its parts.
        await this.putFile({ ...f, state: "QUEUED" });
        return false;
      }
      await this.d.sleep(Math.min(5_000, 500 * 2 ** Math.min(i, 4)));
    }
  }

  private async fileFailed(s: LocalSession, f: LocalFile, e: unknown): Promise<boolean> {
    const c = classifyError(e);
    this.progress.delete(f.localId);
    if (c.kind === "AUTH" || c.kind === "NOT_FOUND" || (c.kind === "CONFLICT" && c.conflict !== "ID_CONFLICT")) {
      // A session-wide problem: the file keeps its place (its state is unchanged, so it resumes from its parts later).
      await this.putFile({ ...f, state: f.state === "UPLOADING" ? "QUEUED" : f.state });
      await this.sessionFailed(s, e, "file");
      return true;
    }
    if (c.kind === "CONFLICT") {
      await this.putFile({ ...f, state: "CONFLICT", code: c.code, message: c.message });
      return false;
    }
    if (c.kind === "RETRY") {
      const attempts = f.attempts + (this.d.online() ? 1 : 0);
      if (attempts >= MAX_ATTEMPTS) {
        await this.putFile({ ...f, state: "FAILED", attempts, code: c.code, message: `${c.message} (after ${attempts} attempts)` });
      } else {
        await this.putFile({ ...f, state: "RETRY_WAIT", attempts, code: c.code, message: c.message,
          nextAttemptAtMs: this.d.now() + (attempts === 0 ? 0 : backoffMs(attempts, this.d.random)) });
      }
      return false;
    }
    // Refused by the server's rules (type, size, checksum format): a verdict on this file, not a transfer problem.
    await this.putFile({ ...f, state: "REJECTED", code: c.code, message: c.message, settledAtMs: this.d.now() });
    return false;
  }
}
