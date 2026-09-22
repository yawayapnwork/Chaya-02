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
  createCapture,
  getCapture,
  getProcessing,
  listFloors,
  listVenues,
  retryProcessing,
  startProcessing,
} from "@/lib/capture-api";
import ProcessingPanel from "@/components/ProcessingPanel";
import CaptureHud from "@/components/CaptureHud";
import { type UploadState, retryValidation, uploadFile } from "@/lib/uploader";
import { checkFileLocally, explainCode, formatBytes } from "@/lib/upload-plan";

interface FileRow {
  key: string;
  file: File;
  state: UploadState;
}

const PHASE_LABEL: Record<UploadState["phase"], string> = {
  hashing: "Computing checksum",
  uploading: "Uploading",
  validating: "Validating on the server",
  accepted: "Accepted",
  rejected: "Rejected",
  quarantined: "Quarantined",
  error: "Failed",
};

function deviceMetadata(): Record<string, unknown> {
  // Real values reported by this browser; nothing is invented.
  return {
    userAgent: navigator.userAgent,
    platform: navigator.platform,
    language: navigator.language,
    screen: `${window.screen.width}x${window.screen.height}`,
    devicePixelRatio: window.devicePixelRatio,
  };
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
  const [rows, setRows] = useState<FileRow[]>([]);
  const [processing, setProcessing] = useState<ProcessingStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [timeBudgetMinutes, setTimeBudgetMinutes] = useState(60);
  const fileInput = useRef<HTMLInputElement>(null);

  const fail = useCallback((e: unknown) => {
    if (e instanceof NotSignedInError) setSignedIn(false);
    setError(message(e));
  }, []);

  // Sign-in state and venues.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const user = await userManager().getUser();
        if (cancelled) return;
        if (!user || user.expired) return setSignedIn(false);
        setSignedIn(true);
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
  }, [venueId, fail]);

  // Poll processing status while processing is going on (or has failed and may be retried).
  const captureId = capture?.id;
  const venueOfCapture = capture?.venueId;
  const captureStatus = capture?.status;
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

  const patchRow = (key: string, state: UploadState) =>
    setRows((rs) => rs.map((r) => (r.key === key ? { ...r, state } : r)));

  async function onCreate() {
    setError(null);
    setBusy(true);
    try {
      setCapture(
        await createCapture(venueId, {
          floorId: floorId || undefined,
          device: deviceMetadata(),
          startedAt: new Date().toISOString(),
        }),
      );
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }

  async function onFiles(list: FileList | null) {
    if (!capture || !list) return;
    setError(null);
    const problems: string[] = [];
    const accepted: File[] = [];
    for (const f of Array.from(list)) {
      const p = checkFileLocally(f);
      if (p) problems.push(p);
      else accepted.push(f);
    }
    if (problems.length) setError(problems.join("\n"));
    const added: FileRow[] = accepted.map((file) => ({
      key: `${file.name}-${file.size}-${crypto.randomUUID()}`,
      file,
      state: { phase: "hashing", progress: 0 },
    }));
    setRows((rs) => [...rs, ...added]);
    if (fileInput.current) fileInput.current.value = "";
    // One file at a time keeps memory and bandwidth predictable for large videos.
    for (const row of added) {
      await uploadFile(capture.venueId, capture.id, row.file, (s) => patchRow(row.key, s));
    }
  }

  const resume = (row: FileRow) =>
    capture && uploadFile(capture.venueId, capture.id, row.file, (s) => patchRow(row.key, s), row.state.mediaId);

  const retry = (row: FileRow) =>
    capture && row.state.mediaId && retryValidation(capture.venueId, capture.id, row.state.mediaId, (s) => patchRow(row.key, s));

  const settled = rows.length > 0 && rows.every((r) => ["accepted", "rejected"].includes(r.state.phase));
  const anyAccepted = rows.some((r) => r.state.phase === "accepted");

  // A real reconstruction exists once ARTIFACT_GENERATION has succeeded -- independent of the run's own
  // overall status, which is currently never SUCCEEDED end-to-end (NAVIGATION_BAKING/SEMANTIC_INDEXING are
  // not implemented yet; see services/reconstruction/docs and ReconstructionService on the backend).
  const hasViewableReconstruction = processing?.run?.stages.some((s) => s.stage === "ARTIFACT_GENERATION" && s.lastRun?.status === "SUCCEEDED") ?? false;
  const viewerHref = capture
    ? `/viewer?venue=${capture.venueId}${capture.floorId ? `&floor=${capture.floorId}` : ""}`
    : "/viewer";

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

      {!capture && (
        <section aria-labelledby="new-session" className="space-y-4">
          <h2 id="new-session" className="text-lg font-medium">1. New capture session</h2>
          <label className="block">
            <span className="text-sm">Venue</span>
            <select className="mt-1 block w-full rounded border p-2" value={venueId} onChange={(e) => { setVenueId(e.target.value); setFloorId(""); setFloors([]); }}>
              <option value="">Select a venue…</option>
              {venues.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
            </select>
          </label>
          {venues.length === 0 && <p className="text-sm text-zinc-600">No venues are assigned to your account.</p>}
          <label className="block">
            <span className="text-sm">Floor (optional)</span>
            <select className="mt-1 block w-full rounded border p-2" value={floorId} onChange={(e) => setFloorId(e.target.value)} disabled={!venueId}>
              <option value="">No specific floor</option>
              {floors.map((f) => <option key={f.id} value={f.id}>{f.name} (level {f.level})</option>)}
            </select>
          </label>
          <p className="text-sm text-zinc-600">This device&apos;s details are recorded with the session.</p>
          <button className="rounded bg-black px-4 py-2 text-white disabled:opacity-50" disabled={!venueId || busy} onClick={onCreate}>
            Create capture session
          </button>
        </section>
      )}

      {capture && ["CREATED", "UPLOADING"].includes(capture.status) && (
        <section aria-labelledby="upload" className="space-y-4">
          <h2 id="upload" className="text-lg font-medium">2. Upload media</h2>
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
          <ul className="space-y-3">
            {rows.map((r) => (
              <li key={r.key} className="rounded border p-3">
                <div className="flex justify-between gap-4 text-sm">
                  <span className="truncate font-medium">{r.file.name}</span>
                  <span className="shrink-0 text-zinc-600">{formatBytes(r.file.size)}</span>
                </div>
                {["hashing", "uploading"].includes(r.state.phase) && (
                  <div className="mt-2">
                    <div
                      role="progressbar"
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-valuenow={Math.round(r.state.progress * 100)}
                      aria-label={`${PHASE_LABEL[r.state.phase]} ${r.file.name}`}
                      className="h-2 w-full overflow-hidden rounded bg-zinc-200"
                    >
                      <div className="h-full bg-black transition-[width]" style={{ width: `${Math.round(r.state.progress * 100)}%` }} />
                    </div>
                  </div>
                )}
                <p className="mt-1 text-sm" aria-live="polite">
                  <span className={r.state.phase === "accepted" ? "text-green-700" : ["rejected", "error"].includes(r.state.phase) ? "text-red-700" : r.state.phase === "quarantined" ? "text-amber-700" : ""}>
                    {PHASE_LABEL[r.state.phase]}
                    {["hashing", "uploading"].includes(r.state.phase) && ` ${Math.round(r.state.progress * 100)}%`}
                  </span>
                  {r.state.message && <> — {r.state.phase === "error" ? r.state.message : explainCode(r.state.message, r.state.message)}</>}
                </p>
                {r.state.phase === "error" && r.state.mediaId && (
                  <button className="mt-2 text-sm underline" onClick={() => resume(r)}>Resume upload</button>
                )}
                {r.state.phase === "quarantined" && (
                  <button className="mt-2 text-sm underline" onClick={() => retry(r)}>Retry validation</button>
                )}
              </li>
            ))}
          </ul>
          <button className="rounded bg-black px-4 py-2 text-white disabled:opacity-50" disabled={!settled || !anyAccepted || busy} onClick={onFinish}>
            Finish upload
          </button>
          {!settled && rows.length > 0 && <p className="text-sm text-zinc-600">Waiting for all files to be accepted or rejected.</p>}
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

      {processing && (
        <section aria-labelledby="processing" className="space-y-3">
          <h2 id="processing" className="text-lg font-medium">4. Processing</h2>
          {hasViewableReconstruction && (
            <p className="rounded border border-green-300 bg-green-50 p-3 text-sm text-green-900">
              A reconstruction is ready.{" "}
              <Link className="underline" href={viewerHref}>View reconstruction</Link>
            </p>
          )}
          <ProcessingPanel status={processing} busy={busy} onRetry={onRetryProcessing} onCancel={onCancelProcessing} />
        </section>
      )}
    </main>
  );
}
