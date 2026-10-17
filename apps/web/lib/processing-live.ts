// Live processing status for the capture page (docs/pipeline.md, "Live progress"). The rules are pure and unit-tested
// (processing-live.test.ts); the stream reader at the bottom wires them to fetch.
//
//   ordering    every status carries a revision that strictly increases with each committed change. A status is shown
//               only if it is newer than the one shown: a higher revision, or the same revision read later (a worker's
//               lease expiring changes the status with time, not with a write). Older or identical ones are dropped, so
//               a late poll answer or a duplicate event never moves the page backwards.
//   recovery    every event is the whole status, so a missed event loses nothing; a reconnect sends the last revision
//               as Last-Event-ID and the server answers with the current status if it differs.
//   reconnect   the stream is reopened with backoff (1 s doubling to 30 s). While it is down the snapshot endpoint is
//               polled, so the page never freezes on a stale view.
//   sign-in     the stream never outlives the token it was opened with: on session-expired (or a 401) a fresh token is
//               taken (silent renewal) and the stream reopened; if none can be had, live updates stop and the page says
//               the sign-in expired.
//   gone        the capture no longer exists: updates stop.
//   refused     any other client error from the stream (400, 410, ...) is not retried: retrying cannot change it.
//   time        the poll window is measured on deps.now, the same clock deps.sleep advances. It used to be Date.now while
//               sleeping went through deps.sleep, so a sleep that returned at once (the unit tests') turned the poll loop
//               into a busy loop for the whole real backoff: ~290,000 iterations in 4 s, which overflowed the stack of a
//               test that spread them into Math.max on a fast CI runner (frontend workflow, 2026-10-17).

import type { ProcessingStatus } from "./capture-api.ts";
import { sseFrameData, sseFrameField, splitSseFrames } from "./sse.ts";

export type LiveState = "CONNECTING" | "LIVE" | "RECONNECTING" | "POLLING" | "SESSION_EXPIRED" | "GONE" | "STOPPED" | "CLOSED";

/** Whether `incoming` should replace `current`. */
export function isNewer(current: Pick<ProcessingStatus, "revision" | "observedAt"> | null,
                        incoming: Pick<ProcessingStatus, "revision" | "observedAt">): boolean {
  if (!current) return true;
  if (incoming.revision === undefined || current.revision === undefined) {
    // An API without revisions: only time can order them.
    return !current.observedAt || !incoming.observedAt || Date.parse(incoming.observedAt) > Date.parse(current.observedAt);
  }
  if (incoming.revision !== current.revision) return incoming.revision > current.revision;
  return !!incoming.observedAt && !!current.observedAt && Date.parse(incoming.observedAt) > Date.parse(current.observedAt);
}

export const RECONNECT_BASE_MS = 1_000;
export const RECONNECT_MAX_MS = 30_000;
export const POLL_WHILE_DOWN_MS = 5_000;

export function reconnectDelayMs(failures: number): number {
  return Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** Math.max(0, failures - 1));
}

export interface ParsedEvent {
  event: string;
  id: string | null;
  data: string | null;
}

/** The events in a chunk of stream text, and the incomplete remainder. Comment-only frames (heartbeats) are dropped. */
export function parseEvents(buffer: string): { events: ParsedEvent[]; rest: string } {
  const { frames, rest } = splitSseFrames(buffer.replace(/\r\n/g, "\n"));
  const events: ParsedEvent[] = [];
  for (const frame of frames) {
    const data = sseFrameData(frame);
    const event = sseFrameField(frame, "event");
    if (data === null && event === null) continue;
    events.push({ event: event ?? "message", id: sseFrameField(frame, "id"), data });
  }
  return { events, rest };
}

// ---- the stream ------------------------------------------------------------------------------------------------------

export interface LiveDeps {
  /** A valid access token, renewed if needed; rejects when nobody can be signed in. */
  token: () => Promise<string>;
  /** Opens the stream (fetch). */
  open: (token: string, lastEventId: string | null, signal: AbortSignal) => Promise<Response>;
  /** The snapshot (GET .../processing). */
  snapshot: () => Promise<ProcessingStatus>;
  /** Waits `ms`; rejects or returns early when `signal` aborts (abortableSleep). */
  sleep: (ms: number, signal: AbortSignal) => Promise<void>;
  /** The clock `sleep` advances (milliseconds). Default Date.now; a test with a virtual sleep passes its virtual clock. */
  now?: () => number;
}

export interface LiveHandle { close(): void }

/**
 * setTimeout that ends early when `signal` aborts, and leaves nothing behind either way: the abort listener is removed
 * when the time passes. (Before, every pause added a listener that only an abort removed, so a page polling for hours
 * accumulated thousands of them on one signal.) Already aborted: rejects at once.
 */
export function abortableSleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new DOMException("aborted", "AbortError"));
    const onAbort = () => {
      clearTimeout(timer);
      reject(new DOMException("aborted", "AbortError"));
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

const isTransientClientError = (status: number) => status === 408 || status === 429;

/**
 * Follows a capture's processing status: calls onStatus with each status newer than the last one given, and onState
 * when the connection's state changes. One connection at a time; after close() nothing more is opened, and neither
 * callback is called again (an attempt still in flight is cancelled and its result dropped).
 */
export function followProcessing(deps: LiveDeps, onStatus: (s: ProcessingStatus) => void, onState: (s: LiveState) => void): LiveHandle {
  const controller = new AbortController();
  const aborted = () => controller.signal.aborted;
  const now = deps.now ?? Date.now;
  let shown: ProcessingStatus | null = null;
  let lastEventId: string | null = null;
  let state: LiveState = "CONNECTING";
  const setState = (s: LiveState) => {
    if (aborted() && s !== "CLOSED") return; // a closed handle reports nothing (a remounted page has a new one)
    if (s !== state) {
      state = s;
      onState(s);
    }
  };
  const offer = (s: ProcessingStatus) => {
    if (aborted()) return;
    if (isNewer(shown, s)) {
      shown = s;
      onStatus(s);
    }
  };
  const pause = (ms: number) => deps.sleep(ms, controller.signal).catch(() => {});
  const stopped = () => aborted() || state === "SESSION_EXPIRED" || state === "GONE" || state === "STOPPED";
  /** Consecutive attempts that delivered nothing: refused, failed, or ended before a single byte arrived. Reset by data,
   * so a page open for hours that loses its stream now and then reconnects after the base delay, while a server that
   * accepts and closes at once is backed off like one that refuses. */
  let failures = 0;
  /** The token the server last reported expired: renewal must produce a different one, or nobody can be signed in (a
   * renewal that keeps returning the rejected token used to reconnect in a tight loop). */
  let expiredToken: string | null = null;
  let tokenInUse: string | null = null;

  type Outcome = "ended" | "empty" | "expired" | "gone" | "unauthorized" | "refused";

  /** One connection; returns how it ended. */
  async function connectOnce(): Promise<Outcome> {
    let token: string;
    try {
      token = await deps.token();
    } catch {
      return "unauthorized";
    }
    if (token === expiredToken) return "unauthorized";
    tokenInUse = token;
    if (aborted()) return "ended";
    const res = await deps.open(token, lastEventId, controller.signal);
    if (res.status === 401) return "unauthorized";
    if (res.status === 404 || res.status === 403) return "gone";
    if (res.status >= 400 && res.status < 500 && !isTransientClientError(res.status)) return "refused";
    if (!res.ok || !res.body) throw new Error(`processing stream: HTTP ${res.status}`);
    setState("LIVE");
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let received = false;
    let finished = false;
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) {
          finished = true;
          return received ? "ended" : "empty";
        }
        if (aborted()) return "ended";
        if (value && value.byteLength > 0 && !received) {
          received = true;
          failures = 0;
        }
        buffer += decoder.decode(value, { stream: true });
        const { events, rest } = parseEvents(buffer);
        buffer = rest;
        for (const e of events) {
          if (e.event === "session-expired") return "expired";
          if (e.event === "gone") return "gone";
          if (e.event === "status" && e.data) {
            if (e.id) lastEventId = e.id;
            expiredToken = null; // this token was accepted
            offer(JSON.parse(e.data) as ProcessingStatus);
          }
        }
      }
    } finally {
      // Leaving early (session-expired, gone, a parse error, close): release the connection now, not when it is collected.
      if (!finished) reader.cancel().catch(() => {});
    }
  }

  (async () => {
    while (!stopped()) {
      let outcome: Outcome | "error";
      try {
        outcome = await connectOnce();
      } catch {
        outcome = "error";
      }
      if (aborted()) return;
      if (outcome === "gone") {
        setState("GONE");
        return;
      }
      if (outcome === "unauthorized") {
        setState("SESSION_EXPIRED");
        return;
      }
      if (outcome === "refused") {
        setState("STOPPED");
        return;
      }
      if (outcome === "expired") {
        // The server's token check: reconnect at once with a fresh token (connectOnce refuses the same one again).
        expiredToken = tokenInUse;
        setState("RECONNECTING");
        continue;
      }
      if (outcome === "ended") {
        // A clean end after data (the server's stream timeout): reconnect after a short pause.
        setState("RECONNECTING");
        await pause(RECONNECT_BASE_MS);
        continue;
      }
      // "error" or "empty": nothing arrived. Back off, polling the snapshot meanwhile so the page keeps up.
      failures++;
      setState(failures > 1 ? "POLLING" : "RECONNECTING");
      const until = now() + reconnectDelayMs(failures);
      while (!aborted() && now() < until) {
        try {
          offer(await deps.snapshot());
        } catch (e) {
          const code = (e as { status?: number }).status;
          if (code === 401) {
            setState("SESSION_EXPIRED");
            return;
          }
          if (code === 404 || code === 403) {
            setState("GONE");
            return;
          }
        }
        if (aborted()) return;
        await pause(Math.min(POLL_WHILE_DOWN_MS, Math.max(0, until - now())));
      }
    }
  })();

  return {
    close() {
      controller.abort();
      setState("CLOSED");
    },
  };
}
