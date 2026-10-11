"use client";

import { api } from "./capture-api";
import { accessToken } from "./auth";
import { publicConfig } from "./env";
import { sseFrameData, splitSseFrames } from "./sse";

import type {
  EstimateBoundsResponse,
  HudStatus,
  PlanView,
  PoseSample,
  QualitySample,
  ReconAssessment,
  Scene,
  SurfaceSampleWire,
  TrackingEventWire,
} from "./hud-types";

export type * from "./hud-types";

// ---- scene: a measured outline the operator enters, or bounds estimated from the reconnaissance walk -------------

const hud = (venueId: string, captureId: string) => `/venues/${venueId}/captures/${captureId}/hud`;

export const setHudScene = (venueId: string, captureId: string, scene: Scene, config?: Record<string, number>, frameId?: string) =>
  api<void>(`${hud(venueId, captureId)}/scene`, {
    method: "PUT",
    body: JSON.stringify({ scene, config: config ?? {}, frameId: frameId ?? null }),
  });

export const estimateBounds = (venueId: string, captureId: string, frameId?: string) =>
  api<EstimateBoundsResponse>(`${hud(venueId, captureId)}/scene/estimate`, {
    method: "POST",
    body: JSON.stringify({ frameId: frameId ?? null }),
  });

// ---- evidence: positions, surface observations, tracking events, frame quality --------------------------------------

export const postPoses = (venueId: string, captureId: string, samples: PoseSample[]) =>
  api<void>(`${hud(venueId, captureId)}/pose`, { method: "POST", body: JSON.stringify({ samples }) });

export const postSurfaces = (venueId: string, captureId: string, samples: SurfaceSampleWire[]) =>
  api<void>(`${hud(venueId, captureId)}/surface`, { method: "POST", body: JSON.stringify({ samples }) });

export const postTrackingEvents = (venueId: string, captureId: string, events: TrackingEventWire[]) =>
  api<void>(`${hud(venueId, captureId)}/tracking-events`, { method: "POST", body: JSON.stringify({ events }) });

export const postQuality = (venueId: string, captureId: string, samples: QualitySample[]) =>
  api<void>(`${hud(venueId, captureId)}/quality`, { method: "POST", body: JSON.stringify({ samples }) });

// ---- reconnaissance and plans -----------------------------------------------------------------------------------------

export const getReconnaissance = (venueId: string, captureId: string) =>
  api<ReconAssessment>(`${hud(venueId, captureId)}/reconnaissance`);

export const createScanPlan = (venueId: string, captureId: string, config?: Record<string, number>) =>
  api<PlanView>(`${hud(venueId, captureId)}/plan`, { method: "POST", body: JSON.stringify({ config: config ?? null }) });

// ---- status: the durable, poll-friendly view (also what the SSE stream carries) -------------------------------

export const getHudStatus = (venueId: string, captureId: string) =>
  api<HudStatus>(`${hud(venueId, captureId)}/status`);

// ---- live status: Server-Sent Events, read by hand ------------------------------------------------------------
//
// The browser's built-in EventSource cannot send an Authorization header, and this API is bearer-token
// authenticated throughout, so the stream is read with `fetch` + a stream reader instead: real SSE framing
// ("event: name\ndata: json\n\n"), just not the EventSource class. REST (getHudStatus) is the fallback if a
// browser or proxy cannot sustain the streamed connection at all.

export interface HudStreamHandle { close(): void }

export function subscribeHudStatus(
  venueId: string,
  captureId: string,
  onStatus: (status: HudStatus) => void,
  onError?: (error: unknown) => void,
): HudStreamHandle {
  const controller = new AbortController();
  let closed = false;

  async function connectOnce(): Promise<void> {
    const token = await accessToken();
    const res = await fetch(`${publicConfig().apiBaseUrl}/api/v1/venues/${venueId}/captures/${captureId}/hud/stream`, {
      headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
      signal: controller.signal,
    });
    if (!res.ok || !res.body) throw new Error(`HUD stream failed: HTTP ${res.status}`);
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const { frames, rest } = splitSseFrames(buffer);
      buffer = rest;
      for (const frame of frames) {
        const data = sseFrameData(frame);
        if (data === null) continue; // a comment / heartbeat frame carries no data
        try {
          onStatus(JSON.parse(data) as HudStatus);
        } catch (e) {
          onError?.(e);
        }
      }
    }
  }

  (async () => {
    while (!closed) {
      try {
        await connectOnce();
      } catch (e) {
        if (closed) return;
        onError?.(e);
      }
      if (closed) return;
      await new Promise((r) => setTimeout(r, 2000)); // brief backoff, then reconnect
    }
  })();

  return {
    close() {
      closed = true;
      controller.abort();
    },
  };
}
