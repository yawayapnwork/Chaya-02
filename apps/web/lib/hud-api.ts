"use client";

import { api } from "./capture-api";
import { accessToken } from "./auth";
import { publicConfig } from "./env";
import { sseFrameData, splitSseFrames } from "./sse";

// ---- scene (the room outline the operator draws once) -------------------------------------------------------

export interface ScenePoint { x: number; y: number }
export interface SceneSegment { a: ScenePoint; b: ScenePoint }
export interface SceneDoorway { x: number; y: number; width: number; passageDegrees?: number | null }
export interface Scene {
  areas: ScenePoint[][];
  walls?: SceneSegment[];
  obstacles?: ScenePoint[][];
  noGoZones?: ScenePoint[][];
  doorways?: SceneDoorway[];
}

export const setHudScene = (venueId: string, captureId: string, scene: Scene, config?: Record<string, number>) =>
  api<void>(`/venues/${venueId}/captures/${captureId}/hud/scene`, {
    method: "PUT",
    body: JSON.stringify({ scene, config: config ?? {} }),
  });

// ---- pose and quality samples --------------------------------------------------------------------------------

export interface PoseSample { capturedAtMs: number; x: number; y: number; yawDegrees?: number | null; source?: string }
export interface QualitySample {
  capturedAtMs: number;
  blurScore: number;
  brightnessMean: number;
  shadowClipFraction: number;
  highlightClipFraction: number;
  motionScore: number | null;
  duplicateFrame: boolean;
  featureCount: number;
  spacingMeters: number | null;
  warnings: string[];
}

export const postPoses = (venueId: string, captureId: string, samples: PoseSample[]) =>
  api<void>(`/venues/${venueId}/captures/${captureId}/hud/pose`, { method: "POST", body: JSON.stringify({ samples }) });

export const postQuality = (venueId: string, captureId: string, samples: QualitySample[]) =>
  api<void>(`/venues/${venueId}/captures/${captureId}/hud/quality`, { method: "POST", body: JSON.stringify({ samples }) });

// ---- status: the durable, poll-friendly view (also what the SSE stream carries) -------------------------------

export interface Position { x: number; y: number; headingDegrees: number | null; asOfMs: number }
export interface PathPoint { x: number; y: number }
export interface UncoveredZone { centroidX: number; centroidY: number; areaM2: number; reason: string }
export interface PlannedWaypoint {
  order: number; x: number; y: number; yawDegrees: number; type: string; reason: string; expectedCoverageGainM2: number;
}
export interface QualitySummary {
  sampleCount: number; avgBlurScore: number; avgBrightnessMean: number; duplicateFrameRate: number;
  avgFeatureCount: number; avgSpacingMeters: number | null; lastSampleAtMs: number;
}
export interface ReshootRecommendation { reason: string; x: number | null; y: number | null }

export interface HudStatus {
  trackingAvailable: boolean;
  trackingUnavailableReason: string | null;
  currentPosition: Position | null;
  pathSoFar: PathPoint[];
  sceneSet: boolean;
  coverageAvailable: boolean;
  coverageUnavailableReason: string | null;
  coveragePercent: number | null;
  weightedCoveragePercent: number | null;
  uncoveredAreaM2: number | null;
  uncoveredZones: UncoveredZone[];
  plannedPath: PlannedWaypoint[];
  quality: QualitySummary | null;
  warnings: string[];
  reshootRecommendations: ReshootRecommendation[];
  computedAt: string;
}

export const getHudStatus = (venueId: string, captureId: string) =>
  api<HudStatus>(`/venues/${venueId}/captures/${captureId}/hud/status`);

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
