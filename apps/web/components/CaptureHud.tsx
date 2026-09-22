"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { assessFrame, type QualityContext, type QualitySample as FrameQualitySample } from "@/lib/capture-quality";
import { boundingBoxOf, fitViewport, pixelToPlan, planToPixel, type PlanPoint, type Viewport } from "@/lib/capture-position";
import { ApiError } from "@/lib/capture-api";
import {
  getHudStatus,
  postPoses,
  postQuality,
  setHudScene,
  subscribeHudStatus,
  type HudStatus,
  type QualitySample,
} from "@/lib/hud-api";

// Analysis runs on a small downsampled frame: fast enough to run often without a backend round trip per frame.
const ANALYSIS_WIDTH = 160;
const ANALYSIS_HEIGHT = 120;
const ANALYSIS_INTERVAL_MS = 600;
const QUALITY_FLUSH_INTERVAL_MS = 1500;
const MAX_BUFFERED_QUALITY_SAMPLES = 40; // dropped oldest-first if the network falls behind, never blocks capture

function message(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}

export default function CaptureHud({ venueId, captureId }: { venueId: string; captureId: string }) {
  const [sceneReady, setSceneReady] = useState(false);
  const [roomWidth, setRoomWidth] = useState(8);
  const [roomDepth, setRoomDepth] = useState(6);
  const [sceneError, setSceneError] = useState<string | null>(null);
  const [status, setStatus] = useState<HudStatus | null>(null);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [heading, setHeading] = useState<number | null>(null);

  const videoRef = useRef<HTMLVideoElement>(null);
  const analysisCanvasRef = useRef<HTMLCanvasElement>(null);
  const minimapCanvasRef = useRef<HTMLCanvasElement>(null);
  const qualityContext = useRef<QualityContext>({ prevGray: null, prevPosition: null });
  const qualityBuffer = useRef<QualitySample[]>([]);
  const lastKnownPosition = useRef<PlanPoint | null>(null);
  const headingRef = useRef<number | null>(null);
  const viewportRef = useRef<Viewport | null>(null);

  // ---- room outline (the only geometry input this build asks for: a measured rectangle) --------------------

  async function saveRoomOutline() {
    setSceneError(null);
    try {
      const w = Math.max(0.5, roomWidth);
      const d = Math.max(0.5, roomDepth);
      await setHudScene(venueId, captureId, { areas: [[{ x: 0, y: 0 }, { x: w, y: 0 }, { x: w, y: d }, { x: 0, y: d }]] });
      setSceneReady(true);
    } catch (e) {
      setSceneError(message(e));
    }
  }

  // ---- status: one REST fetch for the first paint, then live updates over SSE --------------------------------

  useEffect(() => {
    let cancelled = false;
    getHudStatus(venueId, captureId).then((s) => {
      if (!cancelled) {
        setStatus(s);
        if (s.sceneSet) setSceneReady(true);
      }
    }, () => undefined);
    const handle = subscribeHudStatus(venueId, captureId, (s) => {
      if (!cancelled) {
        setStatus(s);
        if (s.sceneSet) setSceneReady(true);
      }
    });
    return () => {
      cancelled = true;
      handle.close();
    };
  }, [venueId, captureId]);

  useEffect(() => {
    if (status?.currentPosition) {
      lastKnownPosition.current = { x: status.currentPosition.x, y: status.currentPosition.y };
    }
  }, [status?.currentPosition]);

  // ---- camera + client-side quality analysis: nothing but summarised numbers ever leaves the browser ---------

  useEffect(() => {
    if (!sceneReady) return;
    let stream: MediaStream | null = null;
    let cancelled = false;
    navigator.mediaDevices?.getUserMedia({ video: { facingMode: "environment" }, audio: false }).then((s) => {
      if (cancelled) {
        s.getTracks().forEach((t) => t.stop());
        return;
      }
      stream = s;
      if (videoRef.current) {
        videoRef.current.srcObject = s;
        videoRef.current.play().catch(() => undefined);
      }
    }).catch((e) => setCameraError(message(e)));
    return () => {
      cancelled = true;
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, [sceneReady]);

  // Best-effort device compass heading. Real sensor data only; when it is unavailable the pose is sent without one.
  useEffect(() => {
    function onOrientation(e: DeviceOrientationEvent) {
      const compass = (e as DeviceOrientationEvent & { webkitCompassHeading?: number }).webkitCompassHeading;
      const value = typeof compass === "number" ? compass : e.alpha !== null ? (360 - e.alpha) % 360 : null;
      headingRef.current = value;
      setHeading(value);
    }
    window.addEventListener("deviceorientation", onOrientation);
    return () => window.removeEventListener("deviceorientation", onOrientation);
  }, []);

  const flushQuality = useCallback(() => {
    const batch = qualityBuffer.current;
    if (batch.length === 0) return;
    qualityBuffer.current = [];
    postQuality(venueId, captureId, batch).catch(() => undefined); // best-effort: dropped samples are lost, never blocking
  }, [venueId, captureId]);

  useEffect(() => {
    if (!sceneReady) return;
    const analysisCanvas = analysisCanvasRef.current;
    if (!analysisCanvas) return;
    const ctx = analysisCanvas.getContext("2d", { willReadFrequently: true });
    if (!ctx) return;
    const tick = () => {
      const video = videoRef.current;
      if (!video || video.readyState < video.HAVE_CURRENT_DATA) return;
      ctx.drawImage(video, 0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);
      const image = ctx.getImageData(0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);
      const capturedAtMs = Date.now();
      const { sample, gray } = assessFrame(image, capturedAtMs, qualityContext.current, lastKnownPosition.current);
      qualityContext.current = { prevGray: gray, prevPosition: lastKnownPosition.current };
      qualityBuffer.current.push(toWireSample(sample));
      if (qualityBuffer.current.length > MAX_BUFFERED_QUALITY_SAMPLES) {
        qualityBuffer.current.splice(0, qualityBuffer.current.length - MAX_BUFFERED_QUALITY_SAMPLES);
      }
    };
    const analysisId = setInterval(tick, ANALYSIS_INTERVAL_MS);
    const flushId = setInterval(flushQuality, QUALITY_FLUSH_INTERVAL_MS);
    return () => {
      clearInterval(analysisId);
      clearInterval(flushId);
      flushQuality();
    };
  }, [sceneReady, flushQuality]);

  // ---- minimap: room outline, completed path, planned path, uncovered zones, current position -----------------

  const roomBox = boundingBoxOf(status ? [[{ x: 0, y: 0 }, { x: roomWidth, y: 0 }, { x: roomWidth, y: roomDepth }, { x: 0, y: roomDepth }]] : []);

  useEffect(() => {
    const canvas = minimapCanvasRef.current;
    if (!canvas || !roomBox) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const vp = fitViewport(roomBox, canvas.width, canvas.height);
    viewportRef.current = vp;
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Room outline.
    ctx.strokeStyle = "#71717a";
    ctx.lineWidth = 2;
    strokeRect(ctx, vp, roomBox);

    // Uncovered zones: what needs attention.
    for (const z of status?.uncoveredZones ?? []) {
      const c = planToPixel(vp, { x: z.centroidX, y: z.centroidY });
      ctx.beginPath();
      ctx.fillStyle = "rgba(239, 68, 68, 0.35)";
      ctx.arc(c.x, c.y, Math.max(6, Math.sqrt(z.areaM2) * vp.scale * 0.4), 0, Math.PI * 2);
      ctx.fill();
    }

    // Completed path: where the operator has actually been.
    if ((status?.pathSoFar.length ?? 0) > 1) {
      ctx.strokeStyle = "#2563eb";
      ctx.lineWidth = 2;
      ctx.beginPath();
      status!.pathSoFar.forEach((p, i) => {
        const px = planToPixel(vp, p);
        if (i === 0) ctx.moveTo(px.x, px.y);
        else ctx.lineTo(px.x, px.y);
      });
      ctx.stroke();
    }

    // Planned path: where the operator should go next.
    if ((status?.plannedPath.length ?? 0) > 0) {
      ctx.strokeStyle = "#16a34a";
      ctx.setLineDash([6, 4]);
      ctx.lineWidth = 2;
      ctx.beginPath();
      const start = status!.currentPosition ?? { x: status!.plannedPath[0].x, y: status!.plannedPath[0].y };
      const startPx = planToPixel(vp, start);
      ctx.moveTo(startPx.x, startPx.y);
      for (const w of status!.plannedPath) {
        const px = planToPixel(vp, w);
        ctx.lineTo(px.x, px.y);
      }
      ctx.stroke();
      ctx.setLineDash([]);
      for (const w of status!.plannedPath) {
        const px = planToPixel(vp, w);
        ctx.beginPath();
        ctx.fillStyle = "#16a34a";
        ctx.arc(px.x, px.y, 4, 0, Math.PI * 2);
        ctx.fill();
      }
    }

    // Current position: where the operator is, only when tracking is actually available.
    if (status?.trackingAvailable && status.currentPosition) {
      const px = planToPixel(vp, status.currentPosition);
      ctx.beginPath();
      ctx.fillStyle = "#1d4ed8";
      ctx.arc(px.x, px.y, 7, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = "white";
      ctx.lineWidth = 2;
      ctx.stroke();
    }
  }, [status, roomBox]);

  function onMinimapClick(e: React.MouseEvent<HTMLCanvasElement>) {
    const vp = viewportRef.current;
    if (!vp) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const p = pixelToPlan(vp, e.clientX - rect.left, e.clientY - rect.top);
    lastKnownPosition.current = p;
    postPoses(venueId, captureId, [{ capturedAtMs: Date.now(), x: p.x, y: p.y, yawDegrees: headingRef.current, source: "manual" }])
      .catch(() => undefined);
  }

  // ---- render -----------------------------------------------------------------------------------------------

  if (!sceneReady) {
    return (
      <section className="space-y-3 rounded border p-4" aria-labelledby="hud-scene">
        <h3 id="hud-scene" className="font-medium">Room outline</h3>
        <p className="text-sm text-zinc-600">
          Measure the room and enter its size. Coverage cannot be estimated until this is set.
        </p>
        <div className="flex items-center gap-3 text-sm">
          <label className="flex items-center gap-1">
            Width (m)
            <input type="number" min={0.5} step={0.1} value={roomWidth} onChange={(e) => setRoomWidth(Number(e.target.value) || 0)} className="w-20 rounded border p-1" />
          </label>
          <label className="flex items-center gap-1">
            Depth (m)
            <input type="number" min={0.5} step={0.1} value={roomDepth} onChange={(e) => setRoomDepth(Number(e.target.value) || 0)} className="w-20 rounded border p-1" />
          </label>
          <button className="rounded bg-black px-3 py-1.5 text-white" onClick={saveRoomOutline}>Save outline</button>
        </div>
        {sceneError && <p role="alert" className="text-sm text-red-700">{sceneError}</p>}
      </section>
    );
  }

  return (
    <section className="space-y-4 rounded border p-4" aria-labelledby="hud">
      <h3 id="hud" className="font-medium">Live capture HUD</h3>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <div className="space-y-2">
          <video ref={videoRef} muted playsInline className="w-full rounded border bg-black" />
          <canvas ref={analysisCanvasRef} width={ANALYSIS_WIDTH} height={ANALYSIS_HEIGHT} className="hidden" />
          {cameraError && <p role="alert" className="text-sm text-amber-700">Camera unavailable: {cameraError}. Quality signals are paused; coverage and position still work.</p>}
          {heading === null && <p className="text-xs text-zinc-500">Compass heading unavailable on this device; positions are recorded without a facing direction.</p>}
        </div>

        <div className="space-y-2">
          <canvas
            ref={minimapCanvasRef}
            width={320}
            height={240}
            className="w-full cursor-crosshair rounded border bg-zinc-50"
            onClick={onMinimapClick}
            role="img"
            aria-label="Floor plan: tap where you are standing to update your position"
          />
          <p className="text-xs text-zinc-500">Tap the map where you are standing to update your position.</p>
        </div>
      </div>

      {status && <StatusPanels status={status} />}
    </section>
  );
}

function StatusPanels({ status }: { status: HudStatus }) {
  return (
    <div className="grid grid-cols-1 gap-4 text-sm md:grid-cols-3">
      <div className="rounded border p-3">
        <h4 className="font-medium">Tracking</h4>
        {status.trackingAvailable ? (
          <p className="text-green-700">
            Position ({status.currentPosition!.x.toFixed(1)}, {status.currentPosition!.y.toFixed(1)}) m
            {status.currentPosition!.headingDegrees != null && `, facing ${Math.round(status.currentPosition!.headingDegrees)}°`}
          </p>
        ) : (
          <p role="alert" className="text-amber-700">Tracking unavailable — {status.trackingUnavailableReason}</p>
        )}
      </div>

      <div className="rounded border p-3">
        <h4 className="font-medium">Coverage</h4>
        {status.coverageAvailable ? (
          <>
            <p className="text-lg font-semibold">{status.coveragePercent!.toFixed(0)}%</p>
            <p className="text-zinc-600">{status.uncoveredZones.length} uncovered area(s) remaining</p>
          </>
        ) : (
          <p role="alert" className="text-amber-700">Coverage unavailable — {status.coverageUnavailableReason}</p>
        )}
      </div>

      <div className="rounded border p-3">
        <h4 className="font-medium">Frame quality</h4>
        {status.quality ? (
          <ul className="space-y-0.5 text-zinc-700">
            <li>Sharpness score: {status.quality.avgBlurScore.toFixed(0)}</li>
            <li>Brightness: {status.quality.avgBrightnessMean.toFixed(0)} / 255</li>
            <li>Duplicate frames: {(status.quality.duplicateFrameRate * 100).toFixed(0)}%</li>
            <li>Feature richness: {status.quality.avgFeatureCount.toFixed(0)} keypoints</li>
          </ul>
        ) : (
          <p className="text-zinc-500">No frames analysed yet.</p>
        )}
      </div>

      {status.reshootRecommendations.length > 0 && (
        <div className="rounded border border-amber-300 bg-amber-50 p-3 md:col-span-3">
          <h4 className="font-medium text-amber-900">Needs attention</h4>
          <ul className="mt-1 list-inside list-disc text-amber-900">
            {status.reshootRecommendations.map((r, i) => (
              <li key={i}>
                {r.reason}
                {r.x != null && r.y != null && ` near (${r.x.toFixed(1)}, ${r.y.toFixed(1)})`}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function strokeRect(ctx: CanvasRenderingContext2D, vp: Viewport, box: { minX: number; minY: number; maxX: number; maxY: number }) {
  const corners = [
    { x: box.minX, y: box.minY }, { x: box.maxX, y: box.minY }, { x: box.maxX, y: box.maxY }, { x: box.minX, y: box.maxY },
  ].map((p) => planToPixel(vp, p));
  ctx.beginPath();
  corners.forEach((c, i) => (i === 0 ? ctx.moveTo(c.x, c.y) : ctx.lineTo(c.x, c.y)));
  ctx.closePath();
  ctx.stroke();
}

function toWireSample(s: FrameQualitySample): QualitySample {
  return {
    capturedAtMs: s.capturedAtMs,
    blurScore: s.blurScore,
    brightnessMean: s.brightnessMean,
    shadowClipFraction: s.shadowClipFraction,
    highlightClipFraction: s.highlightClipFraction,
    motionScore: s.motionScore,
    duplicateFrame: s.duplicateFrame,
    featureCount: s.featureCount,
    spacingMeters: s.spacingMeters,
    warnings: s.warnings,
  };
}
