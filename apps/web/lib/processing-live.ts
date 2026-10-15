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

import type { ProcessingStatus } from "./capture-api.ts";
import { sseFrameData, sseFrameField, splitSseFrames } from "./sse.ts";

export type LiveState = "CONNECTING" | "LIVE" | "RECONNECTING" | "POLLING" | "SESSION_EXPIRED" | "GONE" | "CLOSED";

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
  sleep: (ms: number, signal: AbortSignal) => Promise<void>;
}

export interface LiveHandle { close(): void }

/**
 * Follows a capture's processing status: calls onStatus with each status newer than the last one given, and onState
 * when the connection's state changes.
 */
export function followProcessing(deps: LiveDeps, onStatus: (s: ProcessingStatus) => void, onState: (s: LiveState) => void): LiveHandle {
  const controller = new AbortController();
  let shown: ProcessingStatus | null = null;
  let lastEventId: string | null = null;
  let state: LiveState = "CONNECTING";
  const setState = (s: LiveState) => {
    if (s !== state) {
      state = s;
      onState(s);
    }
  };
  const offer = (s: ProcessingStatus) => {
    if (isNewer(shown, s)) {
      shown = s;
      onStatus(s);
    }
  };
  const stopped = () => controller.signal.aborted || state === "SESSION_EXPIRED" || state === "GONE";

  /** One connection; returns how it ended. */
  async function connectOnce(): Promise<"ended" | "expired" | "gone" | "unauthorized"> {
    let token: string;
    try {
      token = await deps.token();
    } catch {
      return "unauthorized";
    }
    const res = await deps.open(token, lastEventId, controller.signal);
    if (res.status === 401) return "unauthorized";
    if (res.status === 404 || res.status === 403) return "gone";
    if (!res.ok || !res.body) throw new Error(`processing stream: HTTP ${res.status}`);
    setState("LIVE");
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return "ended";
      buffer += decoder.decode(value, { stream: true });
      const { events, rest } = parseEvents(buffer);
      buffer = rest;
      for (const e of events) {
        if (e.event === "session-expired") return "expired";
        if (e.event === "gone") return "gone";
        if (e.event === "status" && e.data) {
          if (e.id) lastEventId = e.id;
          offer(JSON.parse(e.data) as ProcessingStatus);
        }
      }
    }
  }

  (async () => {
    let failures = 0;
    while (!stopped()) {
      let outcome: Awaited<ReturnType<typeof connectOnce>> | "error";
      try {
        outcome = await connectOnce();
      } catch {
        outcome = "error";
      }
      if (controller.signal.aborted) return;
      if (outcome === "gone") {
        setState("GONE");
        return;
      }
      if (outcome === "unauthorized") {
        setState("SESSION_EXPIRED");
        return;
      }
      if (outcome === "expired" || outcome === "ended") {
        // A clean end (the server's stream timeout, or the token expired): reconnect with a fresh token, after a short
        // pause for an ended stream so a server that keeps closing at once is not hammered.
        failures = 0;
        setState("RECONNECTING");
        if (outcome === "ended") await deps.sleep(RECONNECT_BASE_MS, controller.signal).catch(() => {});
        continue;
      }
      failures++;
      setState(failures > 1 ? "POLLING" : "RECONNECTING");
      // While the stream is down, poll the snapshot so the page keeps up.
      const until = Date.now() + reconnectDelayMs(failures);
      while (!controller.signal.aborted && Date.now() < until) {
        try {
          offer(await deps.snapshot());
        } catch (e) {
          if ((e as { status?: number }).status === 401) {
            setState("SESSION_EXPIRED");
            return;
          }
        }
        await deps.sleep(Math.min(POLL_WHILE_DOWN_MS, Math.max(0, until - Date.now())), controller.signal).catch(() => {});
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
