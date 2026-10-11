"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { assessFrame, type Frame, type QualityContext, type QualitySample as FrameQualitySample } from "@/lib/capture-quality";
import { fitViewport, pixelToPlan, type PlanPoint, type Viewport } from "@/lib/capture-position";
import { ApiError } from "@/lib/capture-api";
import {
  createScanPlan,
  estimateBounds,
  getHudStatus,
  postPoses,
  postQuality,
  postSurfaces,
  postTrackingEvents,
  setHudScene,
  subscribeHudStatus,
  type EstimateBoundsResponse,
  type HudStatus,
  type PoseSample,
  type QualitySample,
  type SurfaceSampleWire,
  type TrackingEventWire,
} from "@/lib/hud-api";
import { type CaptureMode, type Instruction, guidance } from "@/lib/capture-guidance";
import { cameraHeadingFromEuler, headingToPlanYaw, type LiveTracking } from "@/lib/capture-tracking";
import { CaptureXrSession, detectCaptureAr, type XrLike } from "@/lib/capture-xr-session";
import { createCaptureBaseLayer, makeViewerRay } from "@/lib/capture-xr-gl";
import { drawMinimap, minimapBox } from "@/lib/capture-minimap";
import { EvidenceQueue } from "@/lib/evidence-queue";
import { parseTrajectory, type ImportedTrajectory } from "@/lib/trajectory-import";

// Analysis runs on a small downsampled frame: fast enough to run often without a backend round trip per frame.
const ANALYSIS_WIDTH = 160;
const ANALYSIS_HEIGHT = 120;
const ANALYSIS_INTERVAL_MS = 600;
const FLUSH_INTERVAL_MS = 1000;
const MAP_WIDTH = 360;
const MAP_HEIGHT = 270;

const SEVERITY_TONE: Record<Instruction["severity"], string> = {
  BLOCKING: "border-red-300 bg-red-50 text-red-900",
  WARNING: "border-amber-300 bg-amber-50 text-amber-900",
  ACTION: "border-blue-300 bg-blue-50 text-blue-900",
  DONE: "border-green-300 bg-green-50 text-green-900",
  INFO: "border-zinc-300 bg-zinc-50 text-zinc-800",
};

const BASIS_LABEL: Record<string, string> = {
  LIVE_TRACKED: "live, from tracked positions",
  TRACKED_NOT_LIVE: "from tracked positions; tracking is not live now",
  MANUAL_MARKS: "from positions you marked (paths between marks are assumed straight)",
  IMPORTED_TRAJECTORY: "from the imported trajectory, not live tracking",
  MIXED: "from tracked, marked and/or imported positions",
  NONE: "no positions yet",
};

function message(e: unknown): string {
  if (e instanceof ApiError) return `${e.code}: ${e.message}`;
  if (e instanceof Error) return e.message;
  return String(e);
}

function readMode(captureId: string): CaptureMode | null {
  try {
    const v = sessionStorage.getItem(`chaya-capture-mode-${captureId}`);
    return v === "AR" || v === "MANUAL" || v === "IMPORT" ? v : null;
  } catch {
    return null;
  }
}

export default function CaptureHud({ venueId, captureId }: { venueId: string; captureId: string }) {
  const [status, setStatus] = useState<HudStatus | null>(null);
  const [mode, setModeState] = useState<CaptureMode | null>(() => (typeof window === "undefined" ? null : readMode(captureId)));
  const [arSupport, setArSupport] = useState<{ state: "CHECKING" | "SUPPORTED" | "UNSUPPORTED"; reason: string | null }>({ state: "CHECKING", reason: null });
  const [xrState, setXrState] = useState<LiveTracking | "OFF">("OFF");
  const [xrProblem, setXrProblem] = useState<string | null>(null);
  const [xrFeatures, setXrFeatures] = useState<string[]>([]);
  const [cameraState, setCameraState] = useState<"OFF" | "STARTING" | "LIVE" | "DENIED" | "UNAVAILABLE">("OFF");
  const [cameraDetail, setCameraDetail] = useState<string | null>(null);
  const [motionWarnings, setMotionWarnings] = useState<string[]>([]);
  const [heading, setHeading] = useState<number | null>(null);
  const [headingAtPlusX, setHeadingAtPlusX] = useState<number | null>(null);
  const [roomWidth, setRoomWidth] = useState(8);
  const [roomDepth, setRoomDepth] = useState(6);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [estimate, setEstimate] = useState<EstimateBoundsResponse | null>(null);
  const [imported, setImported] = useState<{ name: string; value: ImportedTrajectory } | null>(null);
  const [importErrors, setImportErrors] = useState<string[]>([]);
  const [alignTarget, setAlignTarget] = useState<{ x: number; y: number; yawDegrees: number } | null>(null);
  const [queueInfo, setQueueInfo] = useState({ pending: 0, dropped: 0, error: null as string | null });
  const [nowMs, setNowMs] = useState(0);
  const [aligned, setAligned] = useState(false);

  const videoRef = useRef<HTMLVideoElement>(null);
  const analysisCanvasRef = useRef<HTMLCanvasElement>(null);
  const minimapCanvasRef = useRef<HTMLCanvasElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);
  const viewportRef = useRef<Viewport | null>(null);
  const xrRef = useRef<CaptureXrSession | null>(null);
  const qualityContext = useRef<QualityContext>({ prevGray: null, prevPosition: null });
  const lastPlanPosition = useRef<{ x: number; y: number; yawDegrees: number | null } | null>(null);
  const motionRef = useRef<string[]>([]);
  const headingRef = useRef<number | null>(null);

  // ---- evidence queues: batched, kept through brief network failures, never blocking capture -------------------------
  const queues = useMemo(() => ({
    poses: new EvidenceQueue<PoseSample>((b) => postPoses(venueId, captureId, b), 400, 4000),
    surfaces: new EvidenceQueue<SurfaceSampleWire>((b) => postSurfaces(venueId, captureId, b), 400, 8000),
    events: new EvidenceQueue<TrackingEventWire>((b) => postTrackingEvents(venueId, captureId, b), 100, 500),
    quality: new EvidenceQueue<QualitySample>((b) => postQuality(venueId, captureId, b), 100, 400),
  }), [venueId, captureId]);

  const flushAll = useCallback(async () => {
    // Events first, so a loss is stored before the positions that follow it.
    await queues.events.flush();
    await Promise.all([queues.poses.flush(), queues.surfaces.flush(), queues.quality.flush()]);
    const all = [queues.poses, queues.surfaces, queues.events, queues.quality];
    setQueueInfo({
      pending: all.reduce((n, q) => n + q.pending, 0),
      dropped: all.reduce((n, q) => n + q.dropped, 0),
      error: all.map((q) => q.lastError).find((e) => e) ?? null,
    });
  }, [queues]);

  useEffect(() => {
    const id = setInterval(() => { void flushAll(); setNowMs(Date.now()); }, FLUSH_INTERVAL_MS);
    const onHide = () => { if (document.visibilityState === "hidden") void flushAll(); };
    document.addEventListener("visibilitychange", onHide);
    return () => {
      clearInterval(id);
      document.removeEventListener("visibilitychange", onHide);
      void flushAll();
    };
  }, [flushAll]);

  const setMode = (m: CaptureMode) => {
    setModeState(m);
    try {
      sessionStorage.setItem(`chaya-capture-mode-${captureId}`, m);
    } catch {
      /* per-tab convenience only */
    }
  };

  // ---- status: one REST fetch for the first paint, then live updates over SSE --------------------------------
  useEffect(() => {
    let cancelled = false;
    getHudStatus(venueId, captureId).then((s) => { if (!cancelled) setStatus(s); }, () => undefined);
    const handle = subscribeHudStatus(venueId, captureId, (s) => { if (!cancelled) setStatus(s); });
    return () => {
      cancelled = true;
      handle.close();
    };
  }, [venueId, captureId]);

  useEffect(() => {
    const p = status?.currentPosition;
    if (p && (status?.trackingState === "TRACKING" || status?.trackingState === "MANUAL")) {
      lastPlanPosition.current = { x: p.x, y: p.y, yawDegrees: p.headingDegrees };
    }
  }, [status]);

  // ---- capability check: AR tracking needs WebXR immersive-ar ------------------------------------------------------
  useEffect(() => {
    let cancelled = false;
    detectCaptureAr((navigator as Navigator & { xr?: XrLike }).xr).then((r) => {
      if (!cancelled) setArSupport({ state: r.supported ? "SUPPORTED" : "UNSUPPORTED", reason: r.reason });
    });
    return () => { cancelled = true; };
  }, []);

  // ---- orientation changes: logged (they matter for manual headings and the overlay), the map refits on render -------
  useEffect(() => {
    const onChange = () => {
      queues.events.push({ capturedAtMs: Date.now(), state: "ORIENTATION_CHANGED", source: xrRef.current ? "webxr" : "manual",
        frameId: xrRef.current?.frameId ?? null, detail: `screen ${screen.orientation?.type ?? "unknown"} ${screen.orientation?.angle ?? ""}` });
    };
    screen.orientation?.addEventListener?.("change", onChange);
    return () => screen.orientation?.removeEventListener?.("change", onChange);
  }, [queues]);

  // ---- manual mode: the rear camera's heading from device orientation (only meaningful after calibration) -----------
  useEffect(() => {
    if (mode !== "MANUAL") return;
    function onOrientation(e: DeviceOrientationEvent) {
      const h = e.alpha === null || e.beta === null || e.gamma === null ? null : cameraHeadingFromEuler(e.alpha, e.beta, e.gamma);
      headingRef.current = h;
      setHeading(h);
    }
    window.addEventListener("deviceorientation", onOrientation);
    return () => window.removeEventListener("deviceorientation", onOrientation);
  }, [mode]);

  // ---- quality: a frame from either the AR session (camera-access) or the manual-mode camera stream ------------------
  const analyse = useCallback((frame: Frame, capturedAtMs: number) => {
    const position = lastPlanPosition.current ? { x: lastPlanPosition.current.x, y: lastPlanPosition.current.y } : null;
    const { sample, gray } = assessFrame(frame, capturedAtMs, qualityContext.current, position);
    qualityContext.current = { prevGray: gray, prevPosition: position };
    queues.quality.push(toWireSample(sample, motionRef.current));
  }, [queues]);

  // Manual and import modes: the camera through getUserMedia (the AR session owns the camera in AR mode).
  useEffect(() => {
    if (mode !== "MANUAL") return;
    let stream: MediaStream | null = null;
    let cancelled = false;
    if (!navigator.mediaDevices?.getUserMedia) {
      Promise.resolve().then(() => {
        setCameraState("UNAVAILABLE");
        setCameraDetail("This browser exposes no camera API here (it needs HTTPS).");
      });
      queues.events.push({ capturedAtMs: Date.now(), state: "UNSUPPORTED", source: "camera", detail: "navigator.mediaDevices unavailable" });
      return;
    }
    Promise.resolve().then(() => setCameraState("STARTING"));
    navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" }, audio: false }).then((s) => {
      if (cancelled) {
        s.getTracks().forEach((t) => t.stop());
        return;
      }
      stream = s;
      setCameraState("LIVE");
      s.getVideoTracks()[0]?.addEventListener("ended", () => {
        setCameraState("UNAVAILABLE");
        setCameraDetail("The camera stopped (another app may have taken it).");
        queues.events.push({ capturedAtMs: Date.now(), state: "INTERRUPTED", source: "camera", detail: "camera track ended" });
      });
      if (videoRef.current) {
        videoRef.current.srcObject = s;
        videoRef.current.play().catch(() => undefined);
      }
    }).catch((e: unknown) => {
      const name = (e as { name?: string }).name;
      const denied = name === "NotAllowedError" || name === "SecurityError";
      setCameraState(denied ? "DENIED" : "UNAVAILABLE");
      setCameraDetail(message(e));
      queues.events.push({ capturedAtMs: Date.now(), state: denied ? "PERMISSION_DENIED" : "UNSUPPORTED", source: "camera", detail: message(e) });
    });
    return () => {
      cancelled = true;
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, [mode, queues]);

  useEffect(() => {
    if (mode !== "MANUAL" || cameraState !== "LIVE") return;
    const ctx = analysisCanvasRef.current?.getContext("2d", { willReadFrequently: true });
    if (!ctx) return;
    const id = setInterval(() => {
      const video = videoRef.current;
      if (!video || video.readyState < video.HAVE_CURRENT_DATA) return;
      ctx.drawImage(video, 0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);
      analyse(ctx.getImageData(0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT), Date.now());
    }, ANALYSIS_INTERVAL_MS);
    return () => clearInterval(id);
  }, [mode, cameraState, analyse]);

  // ---- AR tracking session -----------------------------------------------------------------------------------------
  async function startAr() {
    setError(null);
    setXrProblem(null);
    const xr = (navigator as Navigator & { xr?: XrLike }).xr;
    const session = new CaptureXrSession({
      xr,
      createBaseLayer: createCaptureBaseLayer,
      makeRay: makeViewerRay,
      now: () => Date.now(),
      newFrameId: () => `xr-${crypto.randomUUID()}`,
    }, {
      onPose: (p) => {
        queues.poses.push(p);
        lastPlanPosition.current = { x: p.x, y: p.y, yawDegrees: p.yawDegrees ?? null };
      },
      onSurface: (s) => queues.surfaces.push(s),
      onEvent: (e) => queues.events.push(e),
      onState: (s) => {
        setXrState(s);
        if (s !== "TRACKING") {
          motionRef.current = [];
          setMotionWarnings([]);
        }
      },
      onEnded: () => {
        if (xrRef.current === session) xrRef.current = null;
        setXrState("OFF");
        setAligned(false);
        void flushAll();
      },
      onCameraFrame: (f, t) => analyse(f, t),
      onMotion: (m) => {
        motionRef.current = m.warnings;
        setMotionWarnings(m.warnings);
      },
    });
    xrRef.current = session;
    const r = await session.start(overlayRef.current ?? undefined);
    void flushAll();
    if (!r.ok) {
      xrRef.current = null;
      setXrProblem(`${r.problem}: ${r.detail}`);
      if (r.problem === "PERMISSION_DENIED") setCameraState("DENIED");
      return;
    }
    setXrFeatures(r.features);
    setXrState("NOT_STARTED");
    setCameraState(r.features.includes("camera-access") ? "LIVE" : "OFF");
    // A session that starts after a plan exists reports in its own frame until it is re-aligned.
  }

  async function stopAr() {
    await xrRef.current?.stop();
    xrRef.current = null;
    setXrState("OFF");
    void flushAll();
  }

  useEffect(() => () => { void xrRef.current?.stop(); }, []);

  function realign() {
    const session = xrRef.current;
    const frame = status?.sceneFrameId;
    if (!session || !frame || !alignTarget) return;
    if (!session.realign(frame, alignTarget)) {
      setError("Re-alignment needs a tracked position: wait until tracking is active, then try again.");
      return;
    }
    setAlignTarget(null);
    setAligned(true);
    void flushAll();
  }

  // ---- bounds and plan -----------------------------------------------------------------------------------------------
  async function run(label: string, fn: () => Promise<void>) {
    setBusy(label);
    setError(null);
    try {
      await flushAll();
      await fn();
      setStatus(await getHudStatus(venueId, captureId));
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(null);
    }
  }

  const onEstimate = () => run("Estimating room bounds", async () => {
    setEstimate(await estimateBounds(venueId, captureId));
  });

  const onSaveOutline = () => run("Saving outline", async () => {
    const w = Math.max(0.5, roomWidth);
    const d = Math.max(0.5, roomDepth);
    // An outline for an imported trajectory is placed in that trajectory's frame; otherwise it gets its own plan frame.
    const frame = mode === "IMPORT" ? imported?.value.frameId : undefined;
    await setHudScene(venueId, captureId, { areas: [[{ x: 0, y: 0 }, { x: w, y: 0 }, { x: w, y: d }, { x: 0, y: d }]] }, undefined, frame);
  });

  const onPlan = () => run("Planning the scan path", async () => {
    await createScanPlan(venueId, captureId);
  });

  // ---- import ---------------------------------------------------------------------------------------------------------
  async function onImportFile(file: File | null) {
    setImportErrors([]);
    setImported(null);
    if (!file) return;
    const r = parseTrajectory(file.name, file.type, await file.text(), Date.now(), () => `import-${crypto.randomUUID()}`);
    if (!r.ok) setImportErrors(r.errors);
    else setImported({ name: file.name, value: r.value });
  }

  const onSendImport = () => run("Sending the imported trajectory", async () => {
    if (!imported) return;
    for (let i = 0; i < imported.value.samples.length; i += 400) await postPoses(venueId, captureId, imported.value.samples.slice(i, i + 400));
    for (let i = 0; i < imported.value.surfaces.length; i += 400) await postSurfaces(venueId, captureId, imported.value.surfaces.slice(i, i + 400));
  });

  // ---- minimap ---------------------------------------------------------------------------------------------------------
  useEffect(() => {
    const canvas = minimapCanvasRef.current;
    if (!canvas || !status) return;
    const box = minimapBox(status, status.scene ? [] : [[{ x: 0, y: 0 }, { x: roomWidth, y: roomDepth }]]);
    const ctx = canvas.getContext("2d");
    if (!box || !ctx) return;
    const vp = fitViewport(box, canvas.width, canvas.height, 16);
    viewportRef.current = vp;
    drawMinimap(ctx, vp, status, { alignTarget, lastKnown: lastPlanPosition.current });
  }, [status, alignTarget, roomWidth, roomDepth, nowMs]);

  function onMinimapClick(e: React.MouseEvent<HTMLCanvasElement>) {
    const vp = viewportRef.current;
    if (!vp || !status) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const scale = e.currentTarget.width / rect.width;
    const p: PlanPoint = pixelToPlan(vp, (e.clientX - rect.left) * scale, (e.clientY - rect.top) * scale);
    if (showAlign) {
      setAlignTarget({ x: p.x, y: p.y, yawDegrees: lastPlanPosition.current?.yawDegrees ?? 0 });
      return;
    }
    if (mode !== "MANUAL" || !status.sceneSet) return;
    const yaw = headingToPlanYaw(headingRef.current, headingAtPlusX);
    lastPlanPosition.current = { x: p.x, y: p.y, yawDegrees: yaw };
    queues.poses.push({ capturedAtMs: Date.now(), x: p.x, y: p.y, yawDegrees: yaw, source: "manual", frameId: status.sceneFrameId ?? null });
    void flushAll();
  }

  // ---- guidance ----------------------------------------------------------------------------------------------------------
  const instructions = guidance(status, {
    mode,
    arSupport: arSupport.state,
    arUnsupportedReason: arSupport.reason,
    arSessionActive: xrState !== "OFF",
    cameraState,
    cameraDetail,
    motionWarnings,
    headingCalibrated: headingAtPlusX !== null,
    nowMs,
  });
  const inAr = xrState !== "OFF";
  // Always allowed outside manual mode: when the evidence is not enough, the server answers NOT_ESTIMABLE with the reasons.
  const canEstimate = mode !== "MANUAL" && !!status;
  const canPlan = !!status?.sceneSet && status?.reconnaissance?.verdict === "READY";
  const showAlign = mode === "AR" && inAr && !!status?.sceneFrameId
    && (status.trackingState === "FRAME_MISMATCH" || (status.sceneSource === "MEASURED" && !aligned));

  return (
    <section className="space-y-4 rounded border p-4" aria-labelledby="hud">
      <h3 id="hud" className="font-medium">Scan planning and live HUD</h3>

      <fieldset className="space-y-2 text-sm">
        <legend className="font-medium">How is your position known?</legend>
        <label className="flex items-start gap-2">
          <input type="radio" name="mode" checked={mode === "AR"} disabled={arSupport.state !== "SUPPORTED"} onChange={() => setMode("AR")} />
          <span>
            AR tracking (this device&apos;s WebXR world tracking){" "}
            {arSupport.state === "CHECKING" && <span className="text-zinc-500">checking…</span>}
            {arSupport.state === "UNSUPPORTED" && <span className="text-amber-700">unavailable: {arSupport.reason}</span>}
          </span>
        </label>
        <label className="flex items-start gap-2">
          <input type="radio" name="mode" checked={mode === "MANUAL"} onChange={() => setMode("MANUAL")} />
          <span>Mark positions on a measured plan (you enter the room size and tap where you stand)</span>
        </label>
        <label className="flex items-start gap-2">
          <input type="radio" name="mode" checked={mode === "IMPORT"} onChange={() => setMode("IMPORT")} />
          <span>Import a trajectory from another tracker (t, x, y in metres)</span>
        </label>
        <p className="text-xs text-zinc-500">An uploaded video is not a position source: it carries no camera poses, and poses recovered from video
          alone have no metric scale and exist only after processing.</p>
      </fieldset>

      {error && <p role="alert" className="whitespace-pre-line rounded border border-red-300 bg-red-50 p-2 text-sm text-red-800">{error}</p>}
      {busy && <p role="status" className="text-sm text-zinc-600">{busy}…</p>}

      {mode === "AR" && (
        <div className="space-y-2 text-sm">
          {!inAr ? (
            <button className="rounded bg-black px-3 py-1.5 text-white disabled:opacity-50" disabled={arSupport.state !== "SUPPORTED"} onClick={startAr}>
              Start AR tracking
            </button>
          ) : (
            <p>AR session: <span className="font-mono">{xrState}</span>{xrFeatures.length > 0 && <span className="text-zinc-500"> · features: {xrFeatures.join(", ")}</span>}</p>
          )}
          {inAr && !xrFeatures.includes("hit-test") && (
            <p className="text-amber-700">This session has no hit testing: no wall observations, so room bounds cannot be estimated. Enter a measured outline instead.</p>
          )}
          {inAr && !xrFeatures.includes("camera-access") && (
            <p className="text-zinc-600">The browser did not grant raw camera access to the AR session, so frame quality (blur, exposure, texture) cannot be checked during AR. Motion is still checked from tracking.</p>
          )}
          {xrProblem && <p role="alert" className="text-red-700">{xrProblem}</p>}
        </div>
      )}

      {mode === "IMPORT" && (
        <div className="space-y-2 text-sm">
          <input type="file" accept=".json,.csv,application/json,text/csv" aria-label="Choose a trajectory file"
            onChange={(e) => onImportFile(e.target.files?.[0] ?? null)} />
          {importErrors.length > 0 && (
            <ul role="alert" className="list-inside list-disc text-red-700">{importErrors.map((m, i) => <li key={i}>{m}</li>)}</ul>
          )}
          {imported && (
            <div>
              <p>{imported.name}: {imported.value.samples.length} positions over {imported.value.durationSeconds.toFixed(1)} s,
                {" "}{imported.value.pathLengthMeters.toFixed(1)} m, {imported.value.surfaces.length} surface observation(s), frame {imported.value.frameId}.</p>
              {imported.value.warnings.map((w, i) => <p key={i} className="text-amber-700">{w}</p>)}
              <button className="mt-1 rounded bg-black px-3 py-1.5 text-white disabled:opacity-50" disabled={!!busy} onClick={onSendImport}>Send to this capture</button>
            </div>
          )}
        </div>
      )}

      {mode && <ReconPanel status={status} />}

      {mode && (
        <div className="grid grid-cols-1 gap-3 text-sm md:grid-cols-2">
          <div className="space-y-2 rounded border p-3">
            <h4 className="font-medium">Room bounds</h4>
            {status?.sceneSet ? (
              <BoundsSummary status={status} />
            ) : (
              <p className="text-zinc-600">Not set. {mode === "MANUAL" ? "Enter the measured outline." : "Estimate them from the walk, or enter a measured outline."}</p>
            )}
            {mode !== "MANUAL" && (
              <button className="rounded bg-black px-3 py-1.5 text-white disabled:opacity-50" disabled={!canEstimate || !!busy} onClick={onEstimate}>
                Estimate room bounds
              </button>
            )}
            {estimate && !estimate.stored && (
              <div role="alert" className="text-amber-800">
                <p className="font-medium">Bounds could not be estimated ({estimate.bounds.status}).</p>
                <ul className="list-inside list-disc">{estimate.bounds.issues.map((i) => <li key={i.code}>{i.message}</li>)}</ul>
              </div>
            )}
            <details>
              <summary className="cursor-pointer">Enter a measured outline</summary>
              <div className="mt-2 flex flex-wrap items-center gap-3">
                <label className="flex items-center gap-1">Width (m)
                  <input type="number" min={0.5} step={0.1} value={roomWidth} onChange={(e) => setRoomWidth(Number(e.target.value) || 0)} className="w-20 rounded border p-1" />
                </label>
                <label className="flex items-center gap-1">Depth (m)
                  <input type="number" min={0.5} step={0.1} value={roomDepth} onChange={(e) => setRoomDepth(Number(e.target.value) || 0)} className="w-20 rounded border p-1" />
                </label>
                <button className="rounded border px-3 py-1" disabled={!!busy} onClick={onSaveOutline}>Save outline</button>
              </div>
              <p className="mt-1 text-xs text-zinc-500">The plan&apos;s origin (0, 0) is the room&apos;s south-west corner, x along the width.
                {mode === "AR" && " With AR tracking you then align the session to it: tap where you stand on the map, face east on the map, and confirm."}</p>
            </details>
          </div>

          <div className="space-y-2 rounded border p-3">
            <h4 className="font-medium">Scan path</h4>
            {status?.plan ? (
              <p>Plan v{status.plan.version}: {status.plan.visited}/{status.plan.waypoints} waypoints visited
                {status.plan.unreachable > 0 && `, ${status.plan.unreachable} unreachable`}. Predicted coverage after the route:
                {" "}{status.plan.predictedCoveragePercent.toFixed(0)}% <span className="text-zinc-500">(a model prediction)</span>.</p>
            ) : status?.plannedPathKind === "LIVE_SUGGESTION" ? (
              <p className="text-zinc-600">Grey waypoints on the map are a live suggestion, not a plan yet.</p>
            ) : (
              <p className="text-zinc-600">No plan yet.</p>
            )}
            <button className="rounded bg-black px-3 py-1.5 text-white disabled:opacity-50" disabled={!canPlan || !!busy} onClick={onPlan}>
              {status?.plan ? "Re-plan scan path" : "Plan scan path"}
            </button>
          </div>
        </div>
      )}

      {/* During an AR session this block is the DOM overlay shown over the camera view. */}
      <div ref={overlayRef} className={inAr ? "space-y-3 overflow-y-auto bg-white/85 p-3" : "space-y-3"}>
        {instructions.length > 0 && (
          <ol className="space-y-2" aria-label="What to do next" aria-live="polite">
            {instructions.slice(0, 4).map((i) => (
              <li key={i.code} className={`rounded border p-2 text-sm ${SEVERITY_TONE[i.severity]}`}>
                <p className="font-medium">{i.title}</p>
                <p>{i.detail}</p>
              </li>
            ))}
          </ol>
        )}
        {showAlign && (
          <div className="rounded border border-pink-300 bg-pink-50 p-2 text-sm text-pink-900">
            <p>{alignTarget ? "Stand on the pink marker facing its arrow, then confirm." : "Tap the map where you are standing."}</p>
            {alignTarget && (
              <div className="mt-1 flex flex-wrap gap-2">
                <label className="flex items-center gap-1">Facing (deg, map)
                  <input type="number" step={15} value={alignTarget.yawDegrees} className="w-20 rounded border p-1"
                    onChange={(e) => setAlignTarget({ ...alignTarget, yawDegrees: Number(e.target.value) || 0 })} />
                </label>
                <button className="rounded bg-pink-700 px-3 py-1 text-white" onClick={realign}>Re-align here</button>
              </div>
            )}
          </div>
        )}
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <div className="space-y-1">
            <canvas
              ref={minimapCanvasRef}
              width={MAP_WIDTH}
              height={MAP_HEIGHT}
              className={`w-full rounded border bg-zinc-50 ${mode === "MANUAL" || showAlign ? "cursor-crosshair" : ""}`}
              onClick={onMinimapClick}
              role="img"
              aria-label="Floor plan with room bounds, your path, the planned waypoints and your position"
            />
            <MapLegend />
            {mode === "MANUAL" && status?.sceneSet && (
              <div className="flex flex-wrap items-center gap-2 text-xs text-zinc-600">
                <span>Tap the map where you stand to mark your position.</span>
                <button className="rounded border px-2 py-0.5" disabled={heading === null}
                  onClick={() => setHeadingAtPlusX(headingRef.current)}>Set direction (facing east on the map)</button>
                {heading === null && <span>No device orientation available: positions are marked without a facing direction.</span>}
              </div>
            )}
          </div>
          {status && <StatusPanels status={status} queue={queueInfo} />}
        </div>
        {inAr && <button className="rounded border px-3 py-1 text-sm" onClick={stopAr}>End AR tracking</button>}
      </div>

      {mode === "MANUAL" && (
        <div className="space-y-1">
          <video ref={videoRef} muted playsInline className="w-full max-w-sm rounded border bg-black" />
          {cameraState === "DENIED" && <p role="alert" className="text-sm text-red-700">Camera access was denied: frame quality cannot be checked. Positions and coverage still work.</p>}
          {cameraState === "UNAVAILABLE" && <p role="alert" className="text-sm text-amber-700">Camera unavailable{cameraDetail ? `: ${cameraDetail}` : ""}. Frame quality is paused.</p>}
        </div>
      )}
      <canvas ref={analysisCanvasRef} width={ANALYSIS_WIDTH} height={ANALYSIS_HEIGHT} className="hidden" />
    </section>
  );
}

function ReconPanel({ status }: { status: HudStatus | null }) {
  const r = status?.reconnaissance;
  if (!r) return null;
  const tone = r.verdict === "READY" ? "text-green-700" : "text-amber-800";
  return (
    <div className="rounded border p-3 text-sm">
      <h4 className="font-medium">Reconnaissance walk: <span className={tone}>{r.verdict.replace("_", " ").toLowerCase()}</span></h4>
      <p className="text-zinc-600">{r.samples} position(s), {r.durationSeconds.toFixed(0)} s, {r.pathLengthMeters.toFixed(1)} m, {r.wallObservations} wall
        observation(s){r.trackingLosses > 0 && `, ${r.trackingLosses} tracking loss(es)`}. Positions: {r.positionBasis.toLowerCase()}{r.metric ? "" : " (not metric)"}.</p>
      {r.issues.length > 0 && (
        <ul className="mt-1 list-inside list-disc">
          {r.issues.map((i) => <li key={i.code} className={i.severity === "BLOCKING" ? "text-amber-800" : "text-zinc-600"}>{i.message}</li>)}
        </ul>
      )}
    </div>
  );
}

function BoundsSummary({ status }: { status: HudStatus }) {
  const b = status.bounds;
  if (!b || status.sceneSource === "MEASURED") {
    return <p>Measured outline (entered by the operator){b?.issues.length ? `: ${b.issues.map((i) => i.message).join("; ")}` : "."}</p>;
  }
  return (
    <div className="space-y-1">
      <p>Estimated from the walk: <span className={b.status === "ESTIMATED" ? "text-green-700" : "text-amber-800"}>{b.status.replace("_", " ").toLowerCase()}</span>,
        {" "}{b.areaM2.toFixed(1)} m², confidence {b.confidence.toFixed(2)} <span className="text-zinc-500">(heuristic, not a probability)</span>.</p>
      {b.frontierMeters > 0 && <p className="text-amber-800">{b.frontierMeters.toFixed(1)} m of boundary not observed (orange on the map).</p>}
      <details>
        <summary className="cursor-pointer">Assumptions and issues</summary>
        <ul className="list-inside list-disc text-zinc-600">
          {b.assumptions.map((a, i) => <li key={`a${i}`}>{a}</li>)}
          {b.issues.map((i) => <li key={i.code} className="text-amber-800">{i.message}</li>)}
        </ul>
      </details>
    </div>
  );
}

function MapLegend() {
  return (
    <p className="text-xs text-zinc-500">
      <span className="text-blue-700">━</span> tracked path · <span className="text-zinc-400">┅</span> assumed between marks ·{" "}
      <span className="text-violet-700">━</span> imported · <span className="text-orange-500">┅</span> boundary not observed ·{" "}
      <span className="text-green-700">●</span> visited · <span className="text-blue-700">○</span> pending · <span className="text-amber-600">○</span> skipped ·{" "}
      <span className="text-red-600">○</span> unreachable · <span className="text-zinc-500">?</span> last known position
    </p>
  );
}

function StatusPanels({ status, queue }: { status: HudStatus; queue: { pending: number; dropped: number; error: string | null } }) {
  const p = status.currentPosition;
  return (
    <div className="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
      <div className="rounded border p-2">
        <h4 className="font-medium">Position</h4>
        {status.trackingAvailable && p ? (
          <p className="text-green-700">
            {p.kind === "MANUAL" ? "Marked" : "Tracked"} at ({p.x.toFixed(1)}, {p.y.toFixed(1)}) m
            {p.headingDegrees != null && `, facing ${Math.round(p.headingDegrees)}°`}
          </p>
        ) : (
          <p role="alert" className="text-amber-700">
            {status.trackingState?.replace("_", " ").toLowerCase() ?? "unavailable"}: {status.trackingUnavailableReason}
            {p && ` Last known (${p.x.toFixed(1)}, ${p.y.toFixed(1)}).`}
          </p>
        )}
      </div>
      <div className="rounded border p-2">
        <h4 className="font-medium">Coverage {status.coverageLive ? <span className="text-green-700">(live)</span> : <span className="text-zinc-500">(not live)</span>}</h4>
        {status.coverageAvailable ? (
          <>
            <p className="text-lg font-semibold">{status.coveragePercent!.toFixed(0)}%</p>
            <p className="text-xs text-zinc-600">Observed {BASIS_LABEL[status.coverageBasis ?? "NONE"]}. {status.uncoveredZones.length} gap(s).</p>
          </>
        ) : (
          <p role="alert" className="text-amber-700">Unavailable: {status.coverageUnavailableReason}</p>
        )}
      </div>
      <div className="rounded border p-2">
        <h4 className="font-medium">Frame quality</h4>
        {status.quality ? (
          <ul className="text-xs text-zinc-700">
            <li>Sharpness {status.quality.avgBlurScore.toFixed(0)} · brightness {status.quality.avgBrightnessMean.toFixed(0)}/255</li>
            <li>Texture {status.quality.avgFeatureCount.toFixed(0)} keypoints · duplicates {(status.quality.duplicateFrameRate * 100).toFixed(0)}%</li>
            {(status.qualityFlags ?? []).length > 0 && <li className="text-amber-700">Sustained: {status.qualityFlags!.join(", ")}</li>}
          </ul>
        ) : (
          <p className="text-xs text-zinc-500">No frames analysed yet.</p>
        )}
      </div>
      <div className="rounded border p-2">
        <h4 className="font-medium">Upload</h4>
        <p className="text-xs text-zinc-600">{queue.pending} waiting{queue.dropped > 0 && <span className="text-red-700">, {queue.dropped} dropped (network too slow)</span>}</p>
        {queue.error && <p className="text-xs text-amber-700">Last error: {queue.error}</p>}
      </div>
      {status.reshootRecommendations.length > 0 && (
        <div className="rounded border border-amber-300 bg-amber-50 p-2 sm:col-span-2">
          <h4 className="font-medium text-amber-900">Needs attention</h4>
          <ul className="list-inside list-disc text-xs text-amber-900">
            {status.reshootRecommendations.map((r, i) => (
              <li key={i}>{r.reason}{r.x != null && r.y != null && ` near (${r.x.toFixed(1)}, ${r.y.toFixed(1)})`}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function toWireSample(s: FrameQualitySample, motionWarnings: string[]): QualitySample {
  // Motion measured from tracked poses is attached to the frame taken at the same time: it is why a frame may be blurred.
  const warnings: string[] = [...s.warnings];
  if (motionWarnings.length > 0 && !warnings.includes("FAST_MOTION")) warnings.push("FAST_MOTION");
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
    warnings,
  };
}
