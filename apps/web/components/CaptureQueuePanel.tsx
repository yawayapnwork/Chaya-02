"use client";

import type { CaptureQueue, ConflictKind, FileState, LocalFile, SessionSummary } from "@/lib/capture-queue";
import type { StorageStatus } from "@/lib/capture-queue-idb";
import { explainCode, formatBytes } from "@/lib/upload-plan";

const FILE_LABEL: Record<FileState, string> = {
  QUEUED: "Saved, waiting to upload",
  UPLOADING: "Uploading",
  VALIDATING: "Uploaded, the server is checking it",
  RETRY_WAIT: "Upload interrupted, retrying",
  FAILED: "Upload failed",
  ACCEPTED: "Accepted by the server",
  REJECTED: "Rejected by the server",
  QUARANTINED: "Held by the server (not checked yet)",
  CORRUPTED: "Local copy unusable",
  CONFLICT: "Conflict",
};

const CONFLICT_TEXT: Record<ConflictKind, string> = {
  VENUE_UNAVAILABLE: "The venue was deleted, or your account no longer has access to it.",
  CAPTURE_NOT_FOUND: "This capture session no longer exists on the server.",
  CAPTURE_ERASED: "This capture session was erased on the server. Erased captures are never uploaded again.",
  SESSION_CLOSED: "This capture session no longer accepts files (it was finished or failed on the server).",
  SCAN_VERSION_CHANGED: "The floor was republished (a new scan version) since this session was recorded.",
  FLOOR_UNAVAILABLE: "The floor this session was recorded for no longer exists.",
  ID_CONFLICT: "The server holds different data under this session's identifiers.",
};

function fileTone(state: FileState): string {
  if (state === "ACCEPTED") return "text-green-700";
  if (["REJECTED", "FAILED", "CORRUPTED", "CONFLICT"].includes(state)) return "text-red-700";
  if (["QUARANTINED", "RETRY_WAIT"].includes(state)) return "text-amber-700";
  return "";
}

function sessionLabel(s: SessionSummary): { text: string; tone: string } {
  if (s.session.conflict) return { text: "Needs your decision", tone: "bg-red-100 text-red-900" };
  if (s.session.authRequired) return { text: "Sign in to continue", tone: "bg-amber-100 text-amber-900" };
  if (s.synced) return { text: "Synchronized", tone: "bg-green-100 text-green-900" };
  if (!s.session.serverCaptureId) return { text: "Saved on this device", tone: "bg-zinc-100 text-zinc-800" };
  return { text: s.pending > 0 ? "Uploading" : "Partly uploaded", tone: "bg-blue-100 text-blue-900" };
}

export function FileRowView({ f, progress, onRetry, onDiscard }: {
  f: LocalFile; progress: number | undefined; onRetry: () => void; onDiscard: () => void;
}) {
  const pct = Math.round((progress ?? 0) * 100);
  return (
    <li className="rounded border p-2 text-sm" data-testid="queue-file" data-state={f.state}>
      <div className="flex justify-between gap-3">
        <span className="truncate font-medium">{f.name}</span>
        <span className="shrink-0 text-zinc-600">{formatBytes(f.size)}</span>
      </div>
      {f.state === "UPLOADING" && (
        <div role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-label={`Uploading ${f.name}`}
          className="mt-1 h-2 w-full overflow-hidden rounded bg-zinc-200">
          <div className="h-full bg-black" style={{ width: `${pct}%` }} />
        </div>
      )}
      <p className="mt-1" aria-live="polite">
        <span className={fileTone(f.state)}>{FILE_LABEL[f.state]}{f.state === "UPLOADING" && ` ${pct}%`}</span>
        {f.message && <span className="text-zinc-700"> — {explainCode(f.code, f.message)}</span>}
      </p>
      {!f.durable && !["ACCEPTED", "REJECTED"].includes(f.state) && (
        <p className="text-xs text-amber-800" data-testid="queue-file-not-durable">
          Not saved on this device: it is lost if this page closes before the upload finishes.
        </p>
      )}
      <div className="mt-1 flex gap-3 text-xs">
        {["FAILED", "RETRY_WAIT"].includes(f.state) && <button className="underline" onClick={onRetry}>Retry now</button>}
        {f.state === "QUARANTINED" && <button className="underline" onClick={onRetry}>Retry validation</button>}
        {["CORRUPTED", "REJECTED", "CONFLICT", "FAILED"].includes(f.state) && (
          <button className="underline" onClick={onDiscard}>Remove from this device</button>
        )}
      </div>
    </li>
  );
}

export default function CaptureQueuePanel({ queue, summaries, online, storage, onSync, onSignIn, activeSessionId, onOpen }: {
  queue: CaptureQueue;
  summaries: SessionSummary[];
  online: boolean;
  storage: StorageStatus | null;
  onSync: () => void;
  onSignIn: () => void;
  activeSessionId: string | null;
  onOpen: (s: SessionSummary) => void;
}) {
  const pendingFiles = summaries.reduce((n, s) => n + s.pending, 0);
  const failedFiles = summaries.reduce((n, s) => n + s.failed, 0);
  const authRequired = summaries.some((s) => s.session.authRequired);
  const act = (p: Promise<unknown>) => void p.then(onSync, (e) => window.alert(e instanceof Error ? e.message : String(e)));

  return (
    <section aria-labelledby="queue" className="space-y-3 rounded border p-3" data-testid="capture-queue">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="queue" className="text-lg font-medium">Uploads on this device</h2>
        <span className={`rounded px-2 py-0.5 text-sm ${online ? "bg-green-100 text-green-900" : "bg-amber-100 text-amber-900"}`}
          data-testid="queue-online">
          {online ? "Online" : "Offline: changes are saved on this device"}
        </span>
      </div>
      <p className="text-sm" data-testid="queue-counts">
        {pendingFiles} file(s) waiting to upload · {failedFiles} need attention ·{" "}
        {summaries.filter((s) => s.synced).length} session(s) synchronized
      </p>
      {storage && (
        <p className="text-xs text-zinc-600" data-testid="queue-storage">
          Storage: {storage.usageBytes !== null && storage.quotaBytes !== null
            ? `${formatBytes(storage.usageBytes)} used of about ${formatBytes(storage.quotaBytes)} this browser allows`
            : "this browser does not report its limit"}
          {". "}
          {storage.persisted === true
            ? "Kept even when the device runs low on space."
            : "The browser may clear it when the device runs low on space; upload as soon as you can."}
        </p>
      )}
      {authRequired && (
        <div role="alert" className="rounded border border-amber-400 bg-amber-50 p-2 text-sm text-amber-900">
          Your sign-in expired. Everything waiting stays on this device.{" "}
          <button className="underline" onClick={onSignIn}>Sign in again</button> to continue uploading.
        </div>
      )}
      {online && pendingFiles > 0 && <button className="text-sm underline" onClick={onSync}>Upload now</button>}
      <ul className="space-y-2">
        {summaries.map((s) => {
          const label = sessionLabel(s);
          const c = s.session.conflict;
          return (
            <li key={s.session.localId} className="rounded border p-2 text-sm" data-testid="queue-session"
              data-status={label.text} data-conflict={c?.kind ?? ""}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span>
                  {new Date(s.session.createdAtMs).toLocaleString()} · {s.session.venueName}
                  {s.session.floorName ? ` · ${s.session.floorName}` : ""} · {s.accepted}/{s.files.length} accepted
                </span>
                <span className={`rounded px-2 py-0.5 text-xs ${label.tone}`}>{label.text}</span>
              </div>
              {s.overdue && (
                <p className="text-xs text-amber-800">Recorded more than 14 days ago and not synchronized: upload or discard it.</p>
              )}
              {s.session.lastError && !c && <p className="text-xs text-zinc-600">Last problem: {s.session.lastError}</p>}
              {c && (
                <div role="alert" className="mt-1 space-y-1 rounded border border-red-300 bg-red-50 p-2 text-red-900" data-testid="queue-conflict">
                  <p>{CONFLICT_TEXT[c.kind]} Your local data is kept until you choose.</p>
                  <div className="flex flex-wrap gap-3">
                    {c.kind === "SCAN_VERSION_CHANGED" && (
                      <button className="underline" onClick={() => act(queue.resolve(s.session.localId, "continue"))}>Upload anyway</button>
                    )}
                    {["CAPTURE_NOT_FOUND", "SESSION_CLOSED", "ID_CONFLICT"].includes(c.kind) && (
                      <button className="underline" onClick={() => act(queue.resolve(s.session.localId, "new-session"))}>
                        Upload into a new session
                      </button>
                    )}
                    {c.kind === "VENUE_UNAVAILABLE" && (
                      <button className="underline" onClick={() => act(queue.resolve(s.session.localId, "check-again"))}>Check again</button>
                    )}
                    <button className="underline" onClick={() => {
                      if (window.confirm("Delete this session's files from this device? Anything not uploaded is lost.")) {
                        act(queue.discardSession(s.session.localId));
                      }
                    }}>Discard local data</button>
                  </div>
                </div>
              )}
              {s.session.localId !== activeSessionId && !c && (
                <div className="mt-1 flex gap-3 text-xs">
                  {!s.synced && <button className="underline" onClick={() => onOpen(s)}>Open</button>}
                  {s.synced && (
                    <button className="underline" onClick={() => act(queue.discardSession(s.session.localId))}>
                      Remove from this list (the server keeps it)
                    </button>
                  )}
                </div>
              )}
              {s.session.localId !== activeSessionId && s.files.some((f) => !["ACCEPTED"].includes(f.state)) && (
                <ul className="mt-2 space-y-1">
                  {s.files.filter((f) => f.state !== "ACCEPTED").map((f) => (
                    <FileRowView key={f.localId} f={f} progress={queue.progress.get(f.localId)}
                      onRetry={() => act(queue.retryFile(f.localId, s.session.localId))}
                      onDiscard={() => act(queue.discardFile(f.localId, s.session.localId))} />
                  ))}
                </ul>
              )}
            </li>
          );
        })}
      </ul>
      {summaries.length === 0 && <p className="text-sm text-zinc-600">Nothing is waiting on this device.</p>}
    </section>
  );
}
