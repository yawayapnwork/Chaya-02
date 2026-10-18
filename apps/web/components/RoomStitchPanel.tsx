"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { ApiError, cancelProcessing, type Floor, retryProcessing } from "@/lib/capture-api";
import { explainStageError, retryBlockedText } from "@/lib/pipeline-view";
import { createRoom, finalizeRoom, listStitches, startStitch } from "@/lib/stitch-api";
import {
  measuredFacts,
  outcomeText,
  type Room,
  roomState,
  type Stitch,
  stitchProblem,
  summarizeStitch,
  type Tone,
  viewerHref,
} from "@/lib/stitch-view";

const TONE: Record<Tone, string> = {
  info: "text-zinc-700",
  success: "text-green-800",
  warning: "text-amber-800",
  error: "text-red-800",
};

const REFRESH_MS = 5000;

/**
 * Rooms of a floor and their stitching into the floor's reconstruction (docs/multi-room.md). Each room is captured on its
 * own (choose it as the session's room above), processed and calibrated; once at least two rooms are finalized they can
 * be stitched. A stitch registers the rooms into the anchor room's coordinates, combines them, re-derives the floor's
 * navigation, and is published only if every room was placed consistently and every stage succeeded. Progress is
 * refreshed while anything is processing; nothing here decides what is valid -- the server does, and its answers are
 * shown as they are.
 */
export default function RoomStitchPanel({ venueId, floor, rooms, online, onRoomsChanged, onCaptureRoom }: {
  venueId: string;
  floor: Floor;
  rooms: Room[];
  online: boolean;
  onRoomsChanged: () => Promise<void>;
  onCaptureRoom: (roomId: string) => void;
}) {
  const [stitches, setStitches] = useState<Stitch[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [name, setName] = useState("");
  const [kind, setKind] = useState("ROOM");
  const [selected, setSelected] = useState<string[]>([]);
  const [anchor, setAnchor] = useState<string | null>(null);
  const [allowUnconfirmed, setAllowUnconfirmed] = useState(false);

  const reload = useCallback(async () => {
    try {
      setStitches(await listStitches(venueId, floor.id));
      await onRoomsChanged();
    } catch (e) {
      setError(describe(e));
    }
  }, [venueId, floor.id, onRoomsChanged]);

  // The parent keys this panel by floor, so a selection never carries over to another floor.
  useEffect(() => {
    void listStitches(venueId, floor.id).then(setStitches, (e) => setError(describe(e)));
  }, [venueId, floor.id]);

  // Refresh while something is processing: a stitch that is running, or a room whose capture is being reconstructed.
  const active = stitches.some((s) => s.run?.status === "RUNNING") || rooms.some((r) => roomState(r).state === "PROCESSING");
  useEffect(() => {
    if (!active || !online) return;
    const t = window.setTimeout(() => void reload(), REFRESH_MS);
    return () => window.clearTimeout(t);
  }, [active, online, reload, stitches, rooms]);

  async function act(action: () => Promise<unknown>) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await action();
      await reload();
    } catch (e) {
      setError(describe(e));
    } finally {
      setBusy(false);
    }
  }

  const problem = stitchProblem(rooms, selected, anchor);
  const toggle = (id: string) => {
    const next = selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id];
    setSelected(next);
    if (anchor && !next.includes(anchor)) setAnchor(null);
    if (!anchor && next.length) setAnchor(next[0]);
  };

  return (
    <details className="rounded border p-3 text-sm" data-testid="room-stitch">
      <summary className="cursor-pointer py-2 font-medium">Rooms of this floor and stitching</summary>
      <p className="mt-2 text-zinc-600">
        A large floor can be captured room by room. Capture each room so the camera also sees a little through its doors:
        rooms are placed against each other by the surfaces two captures share. Each room must be processed and calibrated
        before it can be stitched.
      </p>
      {error && <p role="alert" className="mt-2 whitespace-pre-line rounded border border-red-300 bg-red-50 p-2 text-red-800">{error}</p>}

      <ul className="mt-3 space-y-2" data-testid="room-list">
        {rooms.length === 0 && <li className="text-zinc-600">No rooms yet.</li>}
        {rooms.map((r) => {
          const st = roomState(r);
          const ready = r.latestFinalizedVersion;
          return (
            <li key={r.id} className="flex flex-wrap items-center justify-between gap-2 rounded border p-2" data-testid="room-row">
              <span>
                <span className="font-medium">{r.name}</span> <span className="text-xs text-zinc-500">{r.kind.toLowerCase()}</span>
                <span className={`ml-2 ${TONE[st.tone]}`} data-testid="room-state">{st.label}</span>
              </span>
              <span className="flex flex-wrap gap-3">
                <button type="button" className="min-h-9 underline disabled:opacity-50" disabled={busy}
                  onClick={() => onCaptureRoom(r.id)}>Capture this room</button>
                {st.state === "AWAITING_CALIBRATION" && (
                  <button type="button" className="min-h-9 underline disabled:opacity-50" disabled={busy || !online}
                    onClick={() => void act(() => finalizeRoom(venueId, floor.id, r.id))}>Finalize (after calibrating)</button>
                )}
                {ready?.pipelineRunId && (
                  <Link className="min-h-9 content-center underline" href={viewerHref(venueId, floor.id, ready.pipelineRunId)}>
                    View this room
                  </Link>
                )}
              </span>
            </li>
          );
        })}
      </ul>

      <form className="mt-3 flex flex-wrap items-end gap-2" onSubmit={(e) => {
        e.preventDefault();
        if (!name.trim()) return;
        void act(() => createRoom(venueId, floor.id, { name: name.trim(), kind })).then(() => setName(""));
      }}>
        <label>Room name <input className="ml-1 rounded border p-1" value={name} required onChange={(e) => setName(e.target.value)} /></label>
        <label>Kind{" "}
          <select className="rounded border p-1" value={kind} onChange={(e) => setKind(e.target.value)}>
            <option value="ROOM">Room</option>
            <option value="CORRIDOR">Corridor</option>
            <option value="HALL">Hall</option>
            <option value="OTHER">Other</option>
          </select>
        </label>
        <button type="submit" className="min-h-9 rounded bg-black px-3 py-1 text-white disabled:opacity-50" disabled={busy || !online}>Add room</button>
      </form>

      <fieldset className="mt-4 space-y-2 border-t pt-3" data-testid="stitch-form">
        <legend className="font-medium">Stitch rooms into this floor</legend>
        {rooms.map((r) => (
          <div key={r.id} className="flex flex-wrap items-center gap-3">
            <label className="flex items-center gap-1">
              <input type="checkbox" checked={selected.includes(r.id)} disabled={!r.latestFinalizedVersion} onChange={() => toggle(r.id)} />
              {r.name}{!r.latestFinalizedVersion && <span className="text-xs text-zinc-500"> (not finalized)</span>}
            </label>
            {selected.includes(r.id) && (
              <label className="flex items-center gap-1 text-xs">
                <input type="radio" name="stitch-anchor" checked={anchor === r.id} onChange={() => setAnchor(r.id)} /> anchor
              </label>
            )}
          </div>
        ))}
        <label className="flex items-start gap-2">
          <input type="checkbox" className="mt-1" checked={allowUnconfirmed} onChange={(e) => setAllowUnconfirmed(e.target.checked)}
            data-testid="allow-unconfirmed" />
          <span>
            Accept rooms that only one registration places.{" "}
            <span className="text-zinc-600">
              Rooms are cross-checked through loops (A sees B, B sees C, C sees A). A room joined to the others by one doorway
              only cannot be cross-checked: in a corridor of identical doors it can be placed at the wrong door without any
              test noticing. Leave this off unless the rooms form no loop (two rooms, or rooms that each open onto one
              corridor), and then check the stitched floor in the viewer.
            </span>
          </span>
        </label>
        <p className={problem ? "text-zinc-600" : "text-green-800"} aria-live="polite">
          {problem ?? "The stitched floor is placed in the anchor room's coordinates; it replaces what the floor shows only if every room is placed and every stage succeeds."}
        </p>
        <button type="button" className="min-h-9 rounded bg-black px-3 py-1 text-white disabled:opacity-50"
          disabled={busy || !online || problem !== null}
          onClick={() => void act(() => startStitch(venueId, floor.id, {
            rooms: selected.map((roomId) => ({ roomId })), anchorRoomId: anchor!, allowUnconfirmedRooms: allowUnconfirmed,
          })).then(() => { setSelected([]); setAnchor(null); })}>
          {busy ? "Starting…" : "Start stitching"}
        </button>
      </fieldset>

      {stitches.length > 0 && (
        <ol className="mt-4 space-y-3 border-t pt-3" data-testid="stitch-list">
          {stitches.map((s) => <StitchItem key={s.id} venueId={venueId} floorId={floor.id} stitch={s} busy={busy || !online}
            onRetry={() => void act(() => retryProcessing(venueId, s.captureId))}
            onCancel={() => void act(() => cancelProcessing(venueId, s.captureId))} />)}
        </ol>
      )}
      {active && <p className="mt-2 text-xs text-zinc-500">Refreshing every {REFRESH_MS / 1000} s while processing.</p>}
    </details>
  );
}

function StitchItem({ venueId, floorId, stitch, busy, onRetry, onCancel }: {
  venueId: string; floorId: string; stitch: Stitch; busy: boolean; onRetry: () => void; onCancel: () => void;
}) {
  const summary = summarizeStitch(stitch);
  const run = stitch.run;
  const names = new Map(stitch.rooms.map((r) => [r.roomId, r.name]));
  const facts = measuredFacts(stitch);
  const blocked = run ? retryBlockedText({ ...run, quality: null }) : null;
  return (
    <li className="space-y-2 rounded border p-2" data-testid="stitch-item">
      <p>
        <span className={`rounded px-2 py-0.5 text-xs font-medium ${TONE[summary.tone]} border`} data-testid="stitch-badge">{summary.badge}</span>{" "}
        <span className="font-medium">v{stitch.versionNumber}</span> · {summary.progress} · <span>{summary.headline}</span>
      </p>
      <ul className="flex flex-wrap gap-x-4 gap-y-1" aria-label="Rooms in this stitch">
        {stitch.rooms.map((r) => (
          <li key={r.roomId} data-testid="stitch-room">
            {r.registered === true ? "✓" : r.registered === false ? "✗" : "…"} {r.name} (v{r.roomVersionNumber}){r.anchor ? " · anchor" : ""}
            {r.registered === true && r.loopConfirmed === false && (
              <span className="block text-xs text-amber-800">placed by a single registration: no loop confirms it</span>
            )}
            {r.registered === false && <span className="block text-xs text-red-800">{r.reason}</span>}
          </li>
        ))}
      </ul>
      {stitch.pairs.length > 0 && (
        <details>
          <summary className="cursor-pointer text-xs">How each pair of rooms was registered</summary>
          <ul className="mt-1 list-disc pl-5 text-xs">
            {stitch.pairs.map((p, i) => (
              <li key={i} className={p.outcome === "REGISTERED" ? "" : "text-zinc-600"}>
                {names.get(p.sourceRoomId ?? "") ?? "?"} → {names.get(p.targetRoomId ?? "") ?? "?"}: {outcomeText(p.outcome)}
                {p.failedGates.length > 0 && ` (${p.failedGates.join(", ")})`}
                {p.edgeStatus && p.edgeStatus !== "ACCEPTED" && ` · dropped by loop closure (${p.edgeStatus.toLowerCase()})`}
              </li>
            ))}
          </ul>
        </details>
      )}
      {run?.status === "FAILED" && (
        <p className="text-red-800" data-testid="stitch-failure">{explainStageError(run.failureCode, run.failureMessage, null)}
          {run.failureMessage && <span className="block text-xs text-zinc-600">{run.failureMessage}</span>}
        </p>
      )}
      {summary.unconfirmed.length > 0 && summary.viewable && (
        <p className="text-xs text-amber-800" data-testid="stitch-unconfirmed">
          {summary.unconfirmed.join(", ")} {summary.unconfirmed.length === 1 ? "was" : "were"} placed by a single registration that no
          loop could check. Look at the doorways in the viewer before relying on routes through them.
        </p>
      )}
      {facts.length > 0 && <ul className="list-disc pl-5 text-xs text-zinc-700" data-testid="stitch-facts">{facts.map((f) => <li key={f}>{f}</li>)}</ul>}
      {blocked && <p className="text-xs text-zinc-600">{blocked}</p>}
      <div className="flex flex-wrap gap-3">
        {summary.viewable && run && (
          <Link className="min-h-9 content-center underline" href={viewerHref(venueId, floorId, run.id)} data-testid="open-stitched">
            Open the stitched floor
          </Link>
        )}
        {summary.canRetry && <button type="button" className="min-h-9 underline disabled:opacity-50" disabled={busy} onClick={onRetry}>Retry</button>}
        {run?.status === "RUNNING" && (
          <button type="button" className="min-h-9 text-red-700 underline disabled:opacity-50" disabled={busy} onClick={onCancel}>Cancel</button>
        )}
      </div>
    </li>
  );
}

function describe(e: unknown): string {
  if (e instanceof ApiError) return `${e.code}: ${e.message}`;
  return e instanceof Error ? e.message : String(e);
}
