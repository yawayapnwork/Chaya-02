"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { NotSignedInError, signIn, userManager } from "@/lib/auth";
import {
  ApiError,
  type Capture,
  type Floor,
  type ProcessingStatus,
  type Venue,
  cancelProcessing,
  completeUpload,
  getCapture,
  getProcessing,
  listCaptures,
  listFloors,
  listMedia,
  listVenues,
  type MediaItem,
  retryProcessing,
  startProcessing,
} from "@/lib/capture-api";
import ProcessingPanel from "@/components/ProcessingPanel";
import CaptureHud from "@/components/CaptureHud";
import CalibrationPanel from "@/components/CalibrationPanel";
import { getCalibration } from "@/lib/capture-api";
import { listReconstructions, type ReconstructionVersion } from "@/lib/reconstruction-api";
import { type ItemState, workflowStatus } from "@/lib/workflow-status";

const WORKFLOW_TONE: Record<ItemState, string> = {
  AVAILABLE: "border-green-300 bg-green-50 text-green-900",
  IN_PROGRESS: "border-blue-300 bg-blue-50 text-blue-900",
  WAITING: "border-amber-300 bg-amber-50 text-amber-900",
  FAILED: "border-red-300 bg-red-50 text-red-900",
  NOT_AVAILABLE: "border-zinc-300 bg-zinc-50 text-zinc-700",
};
const WORKFLOW_LABEL: Record<ItemState, string> = {
  AVAILABLE: "available",
  IN_PROGRESS: "in progress",
  WAITING: "waiting",
  FAILED: "failed",
  NOT_AVAILABLE: "not available",
};
import { checkFileLocally, explainCode, kindForType } from "@/lib/upload-plan";
import type { SessionSummary } from "@/lib/capture-queue";
import { captureQueue, currentOwnerKey, queueStore, setQueueOwner, syncNow } from "@/lib/capture-queue-browser";
import { type StorageStatus, requestPersistentStorage, storageStatus } from "@/lib/capture-queue-idb";
import CaptureQueuePanel, { FileRowView } from "@/components/CaptureQueuePanel";
import FloorManager from "@/components/FloorManager";
import { floorLabel } from "@/lib/floor-transitions";

/**
 * What this browser actually reports about the device. Nothing is inferred or filled in: camera intrinsics, lens
 * distortion and sensor capabilities are not observable from a browser, so they are absent (an operator who knows the
 * intrinsics uploads them as a cameraCalibration metadata file, docs/capture-ingestion.md).
 */
async function deviceMetadata(): Promise<Record<string, unknown>> {
  const nav = navigator as Navigator & {
    deviceMemory?: number;
    userAgentData?: { getHighEntropyValues(hints: string[]): Promise<Record<string, unknown>> };
  };
  const device: Record<string, unknown> = {
    source: "browser",
    userAgent: navigator.userAgent,
    platform: navigator.platform,
    language: navigator.language,
    screen: `${window.screen.width}x${window.screen.height}`,
    devicePixelRatio: window.devicePixelRatio,
    hardwareConcurrency: navigator.hardwareConcurrency,
  };
  if (nav.deviceMemory !== undefined) device.deviceMemoryGiB = nav.deviceMemory;
  if (nav.userAgentData) {
    try {
      // Chromium only; the browser decides which values it reveals.
      device.userAgentData = await nav.userAgentData.getHighEntropyValues(["model", "platform", "platformVersion", "mobile"]);
    } catch {
      /* not revealed */
    }
  }
  return device;
}

function message(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}

export default function CaptureWorkspace() {
  const [signedIn, setSignedIn] = useState<boolean | null>(null);
  const [venues, setVenues] = useState<Venue[]>([]);
  const [floors, setFloors] = useState<Floor[]>([]);
  const [venueId, setVenueId] = useState("");
  const [floorId, setFloorId] = useState("");
  const [capture, setCapture] = useState<Capture | null>(null);
  // The durable upload queue (lib/capture-queue): sessions and files are recorded on this device first, then synchronised.
  const [summaries, setSummaries] = useState<SessionSummary[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null);
  const [online, setOnline] = useState(true);
  const [storage, setStorage] = useState<StorageStatus | null>(null);
  const [savedElsewhere, setSavedElsewhere] = useState(0);
  const [hashing, setHashing] = useState<{ name: string; fraction: number } | null>(null);
  const [notices, setNotices] = useState<string[]>([]);
  // Files chosen in this tab, by queue id: calibration marks pixels on them (the stored copy is gone once accepted).
  const [tabFiles, setTabFiles] = useState<Map<string, File>>(new Map());
  const [processing, setProcessing] = useState<ProcessingStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [timeBudgetMinutes, setTimeBudgetMinutes] = useState(60);
  const [openCaptures, setOpenCaptures] = useState<Capture[]>([]);
  const [serverMedia, setServerMedia] = useState<MediaItem[]>([]);
  const [publishedVersions, setPublishedVersions] = useState<ReconstructionVersion[]>([]);
  const [calibrationState, setCalibrationState] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const fail = useCallback((e: unknown) => {
    if (e instanceof NotSignedInError) setSignedIn(false);
    setError(message(e));
  }, []);

  const captureId = capture?.id;
  const venueOfCapture = capture?.venueId;
  const captureStatus = capture?.status;

  // Sign-in state and venues.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const user = await userManager().getUser();
        if (cancelled) return;
        if (!user || user.expired) {
          // Nobody is signed in: say that sessions are waiting on this device, but nothing about whose or what.
          setQueueOwner(null);
          queueStore().sessionCount().then((n) => !cancelled && setSavedElsewhere(n), () => undefined);
          return setSignedIn(false);
        }
        setQueueOwner(await currentOwnerKey());
        setSignedIn(true);
        // Signing in (again) resumes whatever an expired sign-in paused.
        await captureQueue().resumeAfterSignIn();
        void syncNow();
        const vs = await listVenues();
        if (cancelled) return;
        setVenues(vs);
        if (vs.length === 1) setVenueId(vs[0].id);
      } catch (e) {
        if (!cancelled) fail(e);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [fail]);

  useEffect(() => {
    if (!venueId) return;
    listFloors(venueId).then(setFloors, fail);
    listCaptures(venueId).then((cs) => setOpenCaptures(cs.filter((c) => !["COMPLETED", "FAILED"].includes(c.status))), fail);
  }, [venueId, fail]);

  // The queue's state, refreshed whenever it changes (progress included), and the connection and storage status.
  useEffect(() => {
    if (!signedIn) return;
    const q = captureQueue();
    let pending = false;
    const refresh = () => {
      if (pending) return;
      pending = true;
      setTimeout(() => {
        pending = false;
        q.list().then(setSummaries, () => undefined);
      }, 100);
    };
    refresh();
    const unsubscribe = q.subscribe(refresh);
    const updateOnline = () => setOnline(navigator.onLine);
    updateOnline();
    window.addEventListener("online", updateOnline);
    window.addEventListener("offline", updateOnline);
    return () => {
      unsubscribe();
      window.removeEventListener("online", updateOnline);
      window.removeEventListener("offline", updateOnline);
    };
  }, [signedIn]);
  const fileCount = summaries.reduce((n, s) => n + s.files.length, 0);
  useEffect(() => {
    storageStatus().then(setStorage, () => undefined);
  }, [fileCount]);

  const active = summaries.find((s) => s.session.localId === activeSessionId) ?? null;
  const activeServerId = active?.session.serverCaptureId ?? null;
  // A session recorded on this device becomes the capture on screen once the server has created it.
  useEffect(() => {
    if (!activeServerId || !active || captureId === activeServerId) return;
    getCapture(active.session.venueId, activeServerId).then(setCapture, fail);
  }, [activeServerId, active, captureId, fail]);

  // What the server holds for this capture (files uploaded from elsewhere, or before this device's queue existed).
  const settledRows = active?.files.filter((f) => ["ACCEPTED", "REJECTED", "QUARANTINED"].includes(f.state)).length ?? 0;
  useEffect(() => {
    if (!captureId || !venueOfCapture) return;
    listMedia(venueOfCapture, captureId).then(setServerMedia, fail);
  }, [captureId, venueOfCapture, settledRows, fail]);

  // Publication and calibration move with the run: refetched whenever a stage changes state.
  const stageSignature = processing?.run ? `${processing.run.status}:${processing.run.stages.map((s) => s.state).join(",")}` : "";
  useEffect(() => {
    if (!captureId || !venueOfCapture) return;
    let cancelled = false;
    getCalibration(venueOfCapture, captureId).then((c) => !cancelled && setCalibrationState(c.state), () => undefined);
    const floor = capture?.floorId;
    if (floor) {
      listReconstructions(venueOfCapture, floor).then((vs) => !cancelled && setPublishedVersions(vs), () => undefined);
    }
    return () => {
      cancelled = true;
    };
  }, [captureId, venueOfCapture, capture?.floorId, stageSignature]);

  // Poll processing status while processing is going on (or has failed and may be retried).
  useEffect(() => {
    if (!captureId || !venueOfCapture || captureStatus !== "PROCESSING") return;
    const tick = async () => {
      try {
        const p = await getProcessing(venueOfCapture, captureId);
        setProcessing(p);
        if (p.captureStatus !== captureStatus) setCapture(await getCapture(venueOfCapture, captureId));
      } catch (e) {
        fail(e);
      }
    };
    tick();
    const id = setInterval(tick, 3000);
    return () => clearInterval(id);
  }, [captureId, venueOfCapture, captureStatus, fail]);

  /** Records the session on this device first; it is created on the server now if online, or when back online. */
  async function onCreate() {
    setError(null);
    setBusy(true);
    try {
      const venue = venues.find((v) => v.id === venueId);
      const floor = floors.find((f) => f.id === floorId);
      const s = await captureQueue().createSession({
        venueId, venueName: venue?.name ?? venueId, floorId: floor?.id ?? null, floorName: floor?.name ?? null,
        // What the floor publishes now; a server that predates the field leaves it unknown (no check).
        expectedScanVersionId: floor ? floor.currentScanVersionId : undefined,
        device: await deviceMetadata(),
      });
      setCapture(null);
      setActiveSessionId(s.localId);
      void syncNow();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }

  /** Queues the chosen files: each is checksummed, kept on this device when it fits, and uploaded by the queue. */
  async function onFiles(list: FileList | null) {
    if (!active || !list) return;
    setError(null);
    const problems: string[] = [];
    const accepted: File[] = [];
    for (const f of Array.from(list)) {
      const p = checkFileLocally(f);
      if (p) problems.push(p);
      else accepted.push(f);
    }
    if (problems.length) setError(problems.join("\n"));
    if (fileInput.current) fileInput.current.value = "";
    if (accepted.length === 0) return;
    await requestPersistentStorage();
    try {
      const results = await captureQueue().addFiles(
        active.session.localId,
        accepted.map((file) => ({ file, name: file.name, type: file.type, kind: kindForType(file.type) ?? "METADATA" })),
        (name, fraction) => setHashing({ name, fraction }),
      );
      setNotices(results.filter((r) => !r.durable).map((r) => `${r.name}: ${r.reason}`));
      // Keep this tab's File objects for calibration marking (matched to the queue's records by name and size).
      const files = await queueStore().files(active.session.localId);
      setTabFiles((m) => {
        const next = new Map(m);
        for (const file of accepted) {
          const rec = files.find((f) => f.name === file.name && f.size === file.size && !next.has(f.localId));
          if (rec) next.set(rec.localId, file);
        }
        return next;
      });
    } catch (e) {
      fail(e);
    } finally {
      setHashing(null);
    }
    void syncNow();
  }

  /** Continues a capture the server already has. Its files are queued on this device like a new session's. */
  async function onResumeCapture(c: Capture) {
    setError(null);
    setBusy(true);
    try {
      const fresh = await getCapture(c.venueId, c.id);
      const q = captureQueue();
      let local = (await q.list()).find((x) => x.session.serverCaptureId === fresh.id);
      if (!local) {
        const floor = floors.find((f) => f.id === fresh.floorId);
        const s = await q.createSession({
          venueId: fresh.venueId, venueName: venues.find((v) => v.id === fresh.venueId)?.name ?? fresh.venueId,
          floorId: fresh.floorId, floorName: floor?.name ?? null, expectedScanVersionId: undefined, device: fresh.device,
          serverCaptureId: fresh.id,
        });
        local = { session: s, files: [], pending: 0, failed: 0, accepted: 0, synced: false, overdue: false };
      }
      setActiveSessionId(local.session.localId);
      setCapture(fresh);
      if (fresh.status === "PROCESSING") setProcessing(await getProcessing(fresh.venueId, fresh.id));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }

  const activeFiles = active?.files ?? [];
  const localFiles = new Map(
    activeFiles.filter((f) => f.mediaId && tabFiles.has(f.localId)).map((f) => [f.mediaId as string, tabFiles.get(f.localId) as File]),
  );
  const queuedMediaIds = new Set(activeFiles.map((f) => f.mediaId).filter(Boolean));
  const unfinishedOnServer = serverMedia.filter((m) => ["PENDING", "QUARANTINED"].includes(m.status) && !queuedMediaIds.has(m.id));

  // Finishing needs the server's verdict on every file of the session: queued, uploading or unchecked files block it.
  const settled = activeFiles.length > 0 && activeFiles.every((f) => ["ACCEPTED", "REJECTED"].includes(f.state));
  const anyAccepted = activeFiles.some((f) => f.state === "ACCEPTED");

  // What this run has really produced (lib/workflow-status.ts). The viewer lists only published versions, so it is linked
  // only once this run is one of them, and then to exactly this run.
  const workflow = workflowStatus(processing?.run ?? null, publishedVersions, calibrationState);
  const ownVersion = processing?.run ? publishedVersions.find((v) => v.runId === processing.run!.id) : undefined;
  const viewerHref = capture && ownVersion
    ? `/viewer?venue=${capture.venueId}${capture.floorId ? `&floor=${capture.floorId}` : ""}&run=${ownVersion.runId}`
    : null;

  async function onFinish() {
    if (!capture) return;
    setError(null);
    setBusy(true);
    try {
      setCapture(await completeUpload(capture.venueId, capture.id, new Date().toISOString()));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }

  async function onProcess() {
    if (!capture) return;
    setError(null);
    setBusy(true);
    try {
      const status = await startProcessing(capture.venueId, capture.id, { timeBudgetSeconds: timeBudgetMinutes * 60 });
      setProcessing(status);
      setCapture({ ...capture, status: status.captureStatus });
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }

  async function onRetryProcessing() {
    if (!capture) return;
    setError(null);
    setBusy(true);
    try {
      setProcessing(await retryProcessing(capture.venueId, capture.id));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }

  async function onCancelProcessing() {
    if (!capture) return;
    setError(null);
    setBusy(true);
    try {
      const status = await cancelProcessing(capture.venueId, capture.id);
      setProcessing(status);
      setCapture(await getCapture(capture.venueId, capture.id));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }

  if (signedIn === null) return <p className="p-8">Loading…</p>;
  if (!signedIn) {
    return (
      <main className="mx-auto max-w-2xl p-8">
        <h1 className="text-2xl font-semibold">Capture</h1>
        <p className="mt-2 text-zinc-600">Sign in to create a capture session and upload media.</p>
        {error && <p role="alert" className="mt-4 whitespace-pre-line text-red-700">{error}</p>}
        {savedElsewhere > 0 && (
          <p className="mt-4 rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900" data-testid="queue-signed-out">
            {savedElsewhere} capture session(s) are saved on this device and have not all been uploaded. They upload after the
            account that recorded them signs in (signing in needs a connection).
          </p>
        )}
        <button className="mt-6 rounded bg-black px-4 py-2 text-white" onClick={() => signIn("/capture")}>
          Sign in
        </button>
      </main>
    );
  }

  return (
    <main className="mx-auto max-w-3xl space-y-8 p-8">
      <header className="flex items-baseline justify-between">
        <h1 className="text-2xl font-semibold">Capture</h1>
        {capture && (
          <span className="rounded bg-zinc-100 px-2 py-1 font-mono text-sm" data-testid="capture-status">
            {capture.status}
          </span>
        )}
      </header>

      {error && (
        <p role="alert" className="whitespace-pre-line rounded border border-red-300 bg-red-50 p-3 text-red-800">
          {error}
        </p>
      )}

      <CaptureQueuePanel
        queue={captureQueue()}
        summaries={summaries}
        online={online}
        storage={storage}
        onSync={() => void syncNow()}
        onSignIn={() => void signIn("/capture")}
        activeSessionId={activeSessionId}
        onOpen={(s) => {
          setCapture(null);
          setProcessing(null);
          setActiveSessionId(s.session.localId);
        }}
      />

      {!capture && !active && (
        <section aria-labelledby="new-session" className="space-y-4">
          <h2 id="new-session" className="text-lg font-medium">1. New capture session</h2>
          <label className="block">
            <span className="text-sm">Venue</span>
            <select className="mt-1 block w-full rounded border p-2" value={venueId} onChange={(e) => {
              if (e.target.value === venueId) return; // re-selecting the same venue keeps its floors
              setVenueId(e.target.value); setFloorId(""); setFloors([]);
            }}>
              <option value="">Select a venue…</option>
              {venues.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
            </select>
          </label>
          {venues.length === 0 && <p className="text-sm text-zinc-600">No venues are assigned to your account.</p>}
          <label className="block">
            <span className="text-sm">Floor (optional)</span>
            <select className="mt-1 block w-full rounded border p-2" value={floorId} onChange={(e) => setFloorId(e.target.value)} disabled={!venueId}>
              <option value="">No specific floor</option>
              {floors.map((f) => <option key={f.id} value={f.id}>{floorLabel(f)}</option>)}
            </select>
          </label>
          {venueId && <FloorManager venueId={venueId} floors={floors} onChanged={() => listFloors(venueId).then(setFloors, fail)} />}
          {floorId === "" && venueId && (
            <p className="text-sm text-amber-800">Without a floor, no calibration measurements can be recorded for this capture.</p>
          )}
          <p className="text-sm text-zinc-600">
            What this browser reports about the device (user agent, screen, platform) is recorded with the session. Camera
            intrinsics are not visible to a browser and are not recorded unless you upload a camera calibration metadata file.
          </p>
          <button className="rounded bg-black px-4 py-2 text-white disabled:opacity-50" disabled={!venueId || busy} onClick={onCreate}>
            Create capture session
          </button>
          {openCaptures.length > 0 && (
            <div className="space-y-2 border-t pt-4">
              <h3 className="text-sm font-medium">Or continue an unfinished capture</h3>
              <ul className="space-y-1 text-sm">
                {openCaptures.map((c) => (
                  <li key={c.id} className="flex items-center justify-between gap-2">
                    <span>
                      {new Date(c.startedAt).toLocaleString()} · {floors.find((f) => f.id === c.floorId)?.name ?? "no floor"} ·{" "}
                      <span className="font-mono">{c.status}</span> · {c.acceptedMediaCount}/{c.mediaCount} files accepted
                    </span>
                    <button className="underline" disabled={busy} onClick={() => onResumeCapture(c)}>Continue</button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>
      )}

      {active && (!capture || ["CREATED", "UPLOADING"].includes(capture.status)) && (
        <section aria-labelledby="upload" className="space-y-4">
          <h2 id="upload" className="text-lg font-medium">2. Upload media</h2>
          <p className="text-sm" data-testid="active-session-status">
            {active.session.conflict
              ? "This session needs your decision (see Uploads on this device)."
              : !active.session.serverCaptureId
                ? online
                  ? "Creating the session on the server…"
                  : "Recorded on this device. It is created on the server when you are back online."
                : "Created on the server. Files upload as they are added."}
          </p>
          <p className="text-sm text-zinc-600">
            Video (mp4, mov, webm, mkv), images (jpeg, png, heic) and JSON metadata. Every file is checked, scanned and
            verified by the server before it is accepted.
          </p>
          <input
            ref={fileInput}
            type="file"
            multiple
            accept="video/mp4,video/quicktime,video/webm,video/x-matroska,image/jpeg,image/png,image/heic,application/json"
            onChange={(e) => onFiles(e.target.files)}
            aria-label="Choose files to upload"
          />
          {unfinishedOnServer.length > 0 && (
            <div role="status" className="rounded border border-amber-400 bg-amber-50 p-3 text-sm text-amber-900">
              <p className="font-medium">Unfinished on the server</p>
              <ul className="mt-1 list-disc pl-5">
                {unfinishedOnServer.map((m) => (
                  <li key={m.id}>
                    {m.filename} — {m.status === "PENDING" ? `${m.uploadedParts.length} of ${m.totalParts} parts received` : explainCode(m.rejectionCode, m.status)}
                  </li>
                ))}
              </ul>
              <p className="mt-1">Choose the same file again to resume: its checksum is matched and only the missing parts are sent.</p>
            </div>
          )}
          {hashing && (
            <p className="text-sm" aria-live="polite">Computing checksum of {hashing.name}: {Math.round(hashing.fraction * 100)}%</p>
          )}
          {notices.length > 0 && (
            <ul role="status" className="list-disc rounded border border-amber-300 bg-amber-50 p-2 pl-6 text-sm text-amber-900" data-testid="storage-notices">
              {notices.map((n) => <li key={n}>{n}</li>)}
            </ul>
          )}
          <ul className="space-y-2" data-testid="active-files">
            {activeFiles.map((f) => (
              <FileRowView key={f.localId} f={f} progress={captureQueue().progress.get(f.localId)}
                onRetry={() => void captureQueue().retryFile(f.localId, active.session.localId).then(syncNow)}
                onDiscard={() => void captureQueue().discardFile(f.localId, active.session.localId)} />
            ))}
          </ul>
          <button className="rounded bg-black px-4 py-2 text-white disabled:opacity-50"
            disabled={!capture || !settled || !anyAccepted || busy} onClick={onFinish}>
            Finish upload
          </button>
          {!settled && activeFiles.length > 0 && (
            <p className="text-sm text-zinc-600">Waiting for the server to accept or reject every file.</p>
          )}
          <button className="ml-3 text-sm underline" onClick={() => { setActiveSessionId(null); setCapture(null); setProcessing(null); }}>
            Back to sessions
          </button>
        </section>
      )}

      {capture && ["CREATED", "UPLOADING"].includes(capture.status) && (
        <CaptureHud venueId={capture.venueId} captureId={capture.id} />
      )}

      {capture && ["READY_FOR_PROCESSING", "PROCESSING", "COMPLETED", "FAILED"].includes(capture.status) && (
        <section aria-labelledby="done" className="space-y-4">
          <h2 id="done" className="text-lg font-medium">3. Upload complete</h2>
          <dl className="grid grid-cols-[10rem_1fr] gap-1 text-sm">
            <dt>Accepted files</dt><dd>{capture.acceptedMediaCount} of {capture.mediaCount}</dd>
            <dt>Duration</dt><dd>{capture.durationSeconds == null ? "—" : `${Math.round(capture.durationSeconds)} s`}</dd>
            <dt>Quality assessment</dt><dd>{capture.qualityState === "NOT_ASSESSED" ? "Not assessed yet" : capture.qualityState}</dd>
          </dl>
          {capture.status === "FAILED" && (
            <p role="alert" className="text-red-700">Capture failed: {capture.failureMessage ?? capture.failureCode}</p>
          )}
          {capture.status === "READY_FOR_PROCESSING" && (
            <div className="space-y-3">
              <label className="block text-sm">
                Time budget (minutes)
                <input
                  type="number"
                  min={1}
                  max={1440}
                  value={timeBudgetMinutes}
                  onChange={(e) => setTimeBudgetMinutes(Math.max(1, Math.min(1440, Number(e.target.value) || 1)))}
                  className="ml-2 w-24 rounded border p-1"
                />
              </label>
              <p className="text-xs text-zinc-600">
                If the budget runs out after a reconstruction exists, the result is kept but clearly marked partial quality, never finalized.
              </p>
              <button className="rounded bg-black px-4 py-2 text-white disabled:opacity-50" disabled={busy} onClick={onProcess}>
                Start processing
              </button>
            </div>
          )}
        </section>
      )}

      {capture && (
        <CalibrationPanel venueId={capture.venueId} captureId={capture.id} captureStatus={capture.status} localFiles={localFiles} />
      )}

      {processing && (
        <section aria-labelledby="processing" className="space-y-3">
          <h2 id="processing" className="text-lg font-medium">4. Processing</h2>
          <ul className="grid gap-2 sm:grid-cols-2" data-testid="workflow-status">
            {workflow.map((item) => (
              <li key={item.key} className={`rounded border p-2 text-sm ${WORKFLOW_TONE[item.state]}`} data-state={item.state}>
                <p className="font-medium">{item.label}: {WORKFLOW_LABEL[item.state]}</p>
                <p className="text-xs">{item.detail}</p>
              </li>
            ))}
          </ul>
          {viewerHref && (
            <p className="rounded border border-green-300 bg-green-50 p-3 text-sm text-green-900">
              This run is published.{" "}
              <Link className="underline" href={viewerHref}>View this reconstruction</Link>
            </p>
          )}
          <ProcessingPanel status={processing} busy={busy} onRetry={onRetryProcessing} onCancel={onCancelProcessing} />
        </section>
      )}
    </main>
  );
}
