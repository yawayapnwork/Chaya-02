"use client";

import { useState } from "react";
import type { ProcessingStatus, StageView } from "@/lib/capture-api";
import {
  availableArtifacts,
  durationSeconds,
  explainStageError,
  failureKind,
  pipelineProgress,
  shortSha,
  stageLabel,
  staleLeaseMessage,
  summarizeRun,
  type Tone,
} from "@/lib/pipeline-view";
import type { LiveState } from "@/lib/processing-live";
import { formatBytes } from "@/lib/upload-plan";

const TONE: Record<Tone, string> = {
  info: "border-blue-300 bg-blue-50 text-blue-900",
  success: "border-green-300 bg-green-50 text-green-900",
  warning: "border-amber-400 bg-amber-50 text-amber-900",
  error: "border-red-300 bg-red-50 text-red-900",
};

const STATE_STYLE: Record<StageView["state"], string> = {
  PENDING: "text-zinc-500",
  QUEUED: "text-blue-700",
  RUNNING: "text-blue-700",
  SUCCEEDED: "text-green-700",
  FAILED: "text-red-700",
  CANCELLED: "text-amber-700",
  NOT_RUN: "text-zinc-500",
};

const SEGMENT: Record<StageView["state"], string> = {
  PENDING: "bg-zinc-200",
  QUEUED: "bg-blue-200",
  RUNNING: "chaya-indeterminate bg-blue-500",
  SUCCEEDED: "bg-green-600",
  FAILED: "bg-red-600",
  CANCELLED: "bg-amber-500",
  NOT_RUN: "bg-zinc-200",
};

const LIVE_TEXT: Record<LiveState, string> = {
  CONNECTING: "Connecting to live updates…",
  LIVE: "Live",
  RECONNECTING: "Reconnecting…",
  POLLING: "Live updates interrupted: refreshing every few seconds until they return",
  SESSION_EXPIRED: "Your sign-in expired: updates stopped. Sign in again to follow processing.",
  GONE: "This capture no longer exists.",
  CLOSED: "",
};

const FAILURE_TEXT = {
  RETRYABLE: "This failure can be retried.",
  TERMINAL: "Retrying cannot fix this: the input itself must change.",
  INTERRUPTED: "The stage was interrupted (the worker was lost or the time budget ran out), not failed by its input.",
  CANCELLED: "Processing was cancelled; nothing after the cancellation ran.",
} as const;

export default function ProcessingPanel({
  status,
  busy,
  onRetry,
  onCancel,
  live,
}: {
  status: ProcessingStatus;
  busy: boolean;
  onRetry: () => void;
  onCancel: () => void;
  /** The live connection's state; undefined when the status is not followed live. */
  live?: LiveState;
}) {
  const [open, setOpen] = useState<string | null>(null);
  const run = status.run;
  if (!run) {
    return <p className="text-sm text-zinc-600">Processing has not started.</p>;
  }
  const summary = summarizeRun(run);
  const progress = pipelineProgress(run);
  const kind = failureKind(run);
  const stale = staleLeaseMessage(status.activeJobs ?? []);
  const artifacts = availableArtifacts(run.stages);
  const since = status.progress?.activeStageSince;

  return (
    <div className="space-y-4">
      {live && live !== "CLOSED" && (
        <p className={`text-xs ${live === "LIVE" ? "text-green-800" : live === "SESSION_EXPIRED" || live === "GONE" ? "text-red-800" : "text-amber-800"}`}
          data-testid="processing-live" data-state={live}>
          {LIVE_TEXT[live]}
          {status.observedAt && <> · as of {new Date(status.observedAt).toLocaleTimeString()}</>}
        </p>
      )}

      <div role="status" data-testid="run-summary" className={`rounded border p-3 ${TONE[summary.tone]}`}>
        <p className="flex flex-wrap items-center gap-2">
          <span className="rounded bg-white/70 px-2 py-0.5 text-xs font-semibold uppercase tracking-wide" data-testid="run-badge">
            {summary.badge}
          </span>
          <span className="font-medium">{summary.headline}</span>
        </p>
        {summary.detail && <p className="mt-1 text-sm">{summary.detail}</p>}
        {run.status === "FAILED" && run.failureCode && (
          <p className="mt-1 text-sm">
            {explainStageError(run.failureCode, run.failureMessage, run.stages.find((s) => s.stage === run.failureStage)?.lastRun?.errorDetails ?? null)}
          </p>
        )}
        {kind && <p className="mt-1 text-sm font-medium" data-testid="failure-kind" data-kind={kind}>{FAILURE_TEXT[kind]}</p>}
        <p className="mt-2 text-xs opacity-80">
          Time budget {Math.round(run.timeBudgetSeconds / 60)} min
          {run.privacyEnabled ? " · faces and screens are blurred before reconstruction" : " · privacy preprocessing is OFF for this run"}
        </p>
      </div>

      {/* The pipeline: one segment per stage. A finished stage is solid; the running one is indeterminate, because no stage
          reports progress within itself. This is stage completion, not a share of the total time. */}
      <div>
        <div className="flex h-3 gap-0.5 overflow-hidden rounded" role="img" aria-label={progress.label} data-testid="pipeline-bar">
          {run.stages.map((s) => (
            <div key={s.stage} className={`flex-1 ${SEGMENT[s.state]}`} title={`${stageLabel(s.stage)}: ${s.state}`} data-state={s.state} />
          ))}
        </div>
        <p className="mt-1 text-sm" data-testid="pipeline-progress">
          {progress.label}
          {progress.activeRunning && since && <> · running since {new Date(since).toLocaleTimeString()}</>}
        </p>
        {progress.activeRunning && (
          <p className="text-xs text-zinc-600">The active stage does not report how far along it is, so no percentage is shown.</p>
        )}
      </div>

      {stale && <p role="alert" className="rounded border border-amber-400 bg-amber-50 p-2 text-sm text-amber-900" data-testid="stale-lease">{stale}</p>}

      <div className="flex gap-3">
        {summary.canRetry && (
          <button className="rounded bg-black px-3 py-1.5 text-sm text-white disabled:opacity-50" disabled={busy} onClick={onRetry}>
            Retry {run.failureStage ? stageLabel(run.failureStage) : "failed stage"}
          </button>
        )}
        {summary.canCancel && (
          <button className="rounded border px-3 py-1.5 text-sm disabled:opacity-50" disabled={busy} onClick={onCancel}>
            Cancel processing
          </button>
        )}
      </div>

      <ol className="divide-y rounded border text-sm" data-testid="pipeline-stages">
        {run.stages.map((s) => {
          const last = s.lastRun;
          const active = s.stage === progress.activeStage;
          return (
            <li key={s.stage} className={`p-3 ${active ? "bg-blue-50 ring-2 ring-inset ring-blue-400" : ""}`} data-testid="pipeline-stage"
              data-stage={s.stage} data-state={s.state} aria-current={active ? "step" : undefined}>
              <div className="flex items-center justify-between gap-3">
                <span className="font-medium">{stageLabel(s.stage)}</span>
                <span className={`font-mono text-xs ${STATE_STYLE[s.state]}`}>
                  {s.state}
                  {s.attempts > 1 || (active && (status.progress?.activeStageAttempt ?? 1) > 1)
                    ? ` · attempt ${active ? status.progress?.activeStageAttempt ?? s.attempts : s.attempts}` : ""}
                </span>
              </div>
              {last?.status === "FAILED" && (
                <p className="mt-1 text-red-800">
                  <span className="font-mono text-xs">{last.errorCode}</span> — {explainStageError(last.errorCode, last.errorMessage, last.errorDetails)}
                </p>
              )}
              {s.state === "SUCCEEDED" && last && last.artifacts.length > 0 && (
                <p className="mt-1 text-xs text-zinc-700">
                  Outputs: {last.artifacts.map((a) => `${a.kind}${a.partial ? " (partial)" : ""}`).join(", ")}
                </p>
              )}
              {last && (
                <>
                  <button className="mt-1 text-xs underline" aria-expanded={open === s.stage} onClick={() => setOpen(open === s.stage ? null : s.stage)}>
                    {open === s.stage ? "Hide" : "Show"} execution record
                  </button>
                  {open === s.stage && (
                    <dl className="mt-2 grid grid-cols-[9rem_1fr] gap-x-3 gap-y-1 rounded bg-zinc-50 p-2 font-mono text-xs">
                      <dt>Started</dt><dd>{last.startedAt}</dd>
                      <dt>Finished</dt><dd>{last.finishedAt}</dd>
                      <dt>Duration</dt><dd>{durationSeconds(last.startedAt, last.finishedAt).toFixed(2)} s</dd>
                      <dt>Exit status</dt><dd>{last.exitStatus ?? "— (no external process)"}</dd>
                      <dt>Worker</dt><dd>{last.workerId ?? "—"}</dd>
                      <dt>Command</dt>
                      <dd className="break-all">{Array.isArray(last.command.argv) ? (last.command.argv as unknown[]).join(" ") : "—"}</dd>
                      <dt>Output checksum</dt><dd>{shortSha(last.outputSha256)}</dd>
                      <dt>stdout log</dt><dd className="break-all">{last.stdout ? `${last.stdout.bucket}/${last.stdout.key}` : "—"}</dd>
                      <dt>stderr log</dt><dd className="break-all">{last.stderr ? `${last.stderr.bucket}/${last.stderr.key}` : "—"}</dd>
                    </dl>
                  )}
                </>
              )}
            </li>
          );
        })}
      </ol>

      <details className="rounded border p-2 text-sm" data-testid="artifacts">
        <summary className="cursor-pointer">Available outputs ({artifacts.length})</summary>
        {artifacts.length === 0 ? <p className="mt-1 text-zinc-600">No stage has published an output yet.</p> : (
          <ul className="mt-1 space-y-0.5 font-mono text-xs">
            {artifacts.map((a, i) => (
              <li key={`${a.stage}-${a.kind}-${i}`}>{stageLabel(a.stage)} · {a.kind}{a.partial ? " (partial)" : ""} · {formatBytes(a.sizeBytes)}</li>
            ))}
          </ul>
        )}
      </details>
    </div>
  );
}
