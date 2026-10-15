"use client";

// lib/processing-live.ts wired to the browser: the signed-in token (renewed silently when it can be), the SSE endpoint read
// with fetch (EventSource cannot send an Authorization header), and the snapshot endpoint for polling while the stream is
// down.

import { accessToken } from "./auth";
import { type ProcessingStatus, getProcessing } from "./capture-api";
import { publicConfig } from "./env";
import { type LiveHandle, type LiveState, followProcessing } from "./processing-live";

export function followCaptureProcessing(venueId: string, captureId: string, onStatus: (s: ProcessingStatus) => void,
                                        onState: (s: LiveState) => void): LiveHandle {
  return followProcessing({
    token: () => accessToken(),
    open: (token, lastEventId, signal) => fetch(
      `${publicConfig().apiBaseUrl}/api/v1/venues/${venueId}/captures/${captureId}/processing/stream`,
      {
        headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream", ...(lastEventId ? { "Last-Event-ID": lastEventId } : {}) },
        signal,
        cache: "no-store",
      }),
    snapshot: () => getProcessing(venueId, captureId),
    sleep: (ms, signal) => new Promise((resolve, reject) => {
      const t = setTimeout(resolve, ms);
      signal.addEventListener("abort", () => {
        clearTimeout(t);
        reject(new DOMException("aborted", "AbortError"));
      }, { once: true });
    }),
  }, onStatus, onState);
}
