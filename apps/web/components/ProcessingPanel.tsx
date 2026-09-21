"use client";

import { useState } from "react";
import type { ProcessingStatus, StageView } from "@/lib/capture-api";
import { durationSeconds, explainStageError, shortSha, stageLabel, summarizeRun, type Tone } from "@/lib/pipeline-view";

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

export default function ProcessingPanel({
  status,
  busy,
  onRetry,
  onCancel,
}: {
  status: ProcessingStatus;
  busy: boolean;
  onRetry: () => void;
  onCancel: () => void;
}) {
  const [open, setOpen] = useState<string | null>(null);
  const run = status.run;
  if (!run) {
    return <p className="text-sm text-zinc-600">Processing has not started.</p>;
  }
  const summary = summarizeRun(run);

  return (
    <div className="space-y-4">
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
        <p className="mt-2 text-xs opacity-80">
          Time budget {Math.round(run.timeBudgetSeconds / 60)} min
          {run.privacyEnabled ? " · faces and screens are blurred before reconstruction" : " · privacy preprocessing is OFF for this run"}
        </p>
      </div>

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

      <ol className="divide-y rounded border text-sm">
        {run.stages.map((s) => {
          const last = s.lastRun;
          return (
            <li key={s.stage} className="p-3">
              <div className="flex items-center justify-between gap-3">
                <span className="font-medium">{stageLabel(s.stage)}</span>
                <span className={`font-mono text-xs ${STATE_STYLE[s.state]}`}>
                  {s.state}
                  {s.attempts > 1 ? ` · attempt ${s.attempts}` : ""}
                </span>
              </div>
              {last?.status === "FAILED" && (
                <p className="mt-1 text-red-800">
                  <span className="font-mono text-xs">{last.errorCode}</span> — {explainStageError(last.errorCode, last.errorMessage, last.errorDetails)}
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
                      <dt>Duration</dt><dd>{durationSeconds(last.startedAt, last.finishedAt).toFixed(2)} s</dd>
                      <dt>Exit status</dt><dd>{last.exitStatus ?? "— (no external process)"}</dd>
                      <dt>Worker</dt><dd>{last.workerId ?? "—"}</dd>
                      <dt>Command</dt>
                      <dd className="break-all">{Array.isArray(last.command.argv) ? (last.command.argv as unknown[]).join(" ") : "—"}</dd>
                      <dt>Output checksum</dt><dd>{shortSha(last.outputSha256)}</dd>
                      <dt>stdout log</dt><dd className="break-all">{last.stdout ? `${last.stdout.bucket}/${last.stdout.key}` : "—"}</dd>
                      <dt>stderr log</dt><dd className="break-all">{last.stderr ? `${last.stderr.bucket}/${last.stderr.key}` : "—"}</dd>
                      <dt>Artifacts</dt>
                      <dd>{last.artifacts.length ? last.artifacts.map((a) => `${a.kind}${a.partial ? " (partial)" : ""}`).join(", ") : "none"}</dd>
                    </dl>
                  )}
                </>
              )}
            </li>
          );
        })}
      </ol>
    </div>
  );
}
