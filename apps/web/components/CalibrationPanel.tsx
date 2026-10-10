"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ApiError,
  type CalibrationStatus,
  type GravitySource,
  type MeasurementView,
  type MediaItem,
  calibrateCapture,
  getCalibration,
  listMeasurements,
  listMedia,
  recordMeasurement,
  withdrawMeasurement,
} from "@/lib/capture-api";
import {
  type LengthUnit,
  type MeasurementDraft,
  type MeasurementKind,
  type MeasurementMethod,
  type ObservationDraft,
  type PointName,
  type StateBadge,
  METHODS,
  UNIT_METRES,
  calibrationBadge,
  captureStageBadge,
  checkMeasurementDraft,
  clickToPixel,
  explainCalibrationCode,
  measurementBody,
  pointsOf,
  reconstructionFrameLabel,
} from "@/lib/calibration";

const TONE: Record<StateBadge["tone"], string> = {
  neutral: "border-zinc-300 bg-zinc-50 text-zinc-900",
  pending: "border-amber-400 bg-amber-50 text-amber-900",
  ok: "border-green-300 bg-green-50 text-green-900",
  error: "border-red-300 bg-red-50 text-red-900",
};

function emptyDraft(kind: MeasurementKind): MeasurementDraft {
  return { kind, label: "", method: kind === "DISTANCE" ? "TAPE" : "TOTAL_STATION", unit: "m", value: null, uncertainty: null, datum: "", venue: null, observations: [] };
}

function num(s: string): number | null {
  if (s.trim() === "") return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : NaN;
}

function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    const hint = explainCalibrationCode(e.code);
    return hint ? `${hint} (${e.code}: ${e.message})` : `${e.code}: ${e.message}`;
  }
  return e instanceof Error ? e.message : String(e);
}

/** Marks a point by clicking a local copy of an uploaded image or video; video marks also record the frame time. */
function PixelPicker({ file, onPick }: { file: File; onPick: (u: number, v: number, time: number | null) => void }) {
  const url = useMemo(() => URL.createObjectURL(file), [file]);
  useEffect(() => () => URL.revokeObjectURL(url), [url]);
  const isVideo = file.type.startsWith("video/");
  const onClick = (e: React.MouseEvent<HTMLImageElement | HTMLVideoElement>) => {
    const el = e.currentTarget;
    const natural = el instanceof HTMLVideoElement ? { width: el.videoWidth, height: el.videoHeight } : { width: el.naturalWidth, height: el.naturalHeight };
    const p = clickToPixel({ offsetX: e.nativeEvent.offsetX, offsetY: e.nativeEvent.offsetY }, { width: el.clientWidth, height: el.clientHeight }, natural);
    if (p) onPick(p.u, p.v, el instanceof HTMLVideoElement ? Math.round(el.currentTime * 1000) / 1000 : null);
  };
  return (
    <div className="mt-2 space-y-1">
      {isVideo ? (
        <video src={url} controls className="h-auto w-full cursor-crosshair" onClick={onClick} />
      ) : (
        // eslint-disable-next-line @next/next/no-img-element -- a local object URL; next/image cannot optimise it
        <img src={url} alt={`Uploaded ${file.name}`} className="h-auto w-full cursor-crosshair" onClick={onClick} />
      )}
      <p className="text-xs text-zinc-600">
        {isVideo ? "Pause on a frame where the point is visible, then click it." : "Click the point in the image."} Pixel coordinates are recorded in the file&apos;s own pixels.
      </p>
    </div>
  );
}

export default function CalibrationPanel({
  venueId,
  captureId,
  captureStatus,
  localFiles,
}: {
  venueId: string;
  captureId: string;
  captureStatus: string;
  /** Files uploaded from this page, by media id: only they can be shown for point picking. */
  localFiles: ReadonlyMap<string, File>;
}) {
  const [status, setStatus] = useState<CalibrationStatus | null>(null);
  const [measurements, setMeasurements] = useState<MeasurementView[]>([]);
  const [media, setMedia] = useState<MediaItem[]>([]);
  const [draft, setDraft] = useState<MeasurementDraft>(emptyDraft("DISTANCE"));
  const [venueText, setVenueText] = useState(["", "", ""]);
  const [obsPoint, setObsPoint] = useState<PointName>("A");
  const [obsMedia, setObsMedia] = useState("");
  const [obsTime, setObsTime] = useState("");
  const [obsU, setObsU] = useState("");
  const [obsV, setObsV] = useState("");
  const [resolved, setResolved] = useState<Record<string, string>>({});
  const [gravity, setGravity] = useState<GravitySource>("RECONSTRUCTED_FLOOR_PLANE");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [formKey, setFormKey] = useState(0);

  const refresh = useCallback(
    () =>
      Promise.all([getCalibration(venueId, captureId), listMeasurements(venueId, captureId), listMedia(venueId, captureId)]).then(
        ([s, ms, md]) => {
          setStatus(s);
          setMeasurements(ms);
          setMedia(md);
        },
        (e) => setError(errorText(e)),
      ),
    [venueId, captureId],
  );

  useEffect(() => {
    void refresh();
  }, [refresh, captureStatus]);

  // A reconstruction appears while processing runs; look again now and then.
  const waiting = status?.state === "AWAITING_RECONSTRUCTION" && captureStatus === "PROCESSING";
  useEffect(() => {
    if (!waiting) return;
    const id = setInterval(refresh, 10000);
    return () => clearInterval(id);
  }, [waiting, refresh]);

  const observable = media.filter((m) => m.status === "ACCEPTED" && m.kind !== "METADATA");
  const selectedMedia = observable.find((m) => m.id === obsMedia);
  const draftWithVenue: MeasurementDraft = {
    ...draft,
    venue: draft.kind === "CONTROL_POINT" ? (venueText.map((t) => num(t) ?? NaN) as [number, number, number]) : null,
  };
  const problems = checkMeasurementDraft(
    draftWithVenue,
    media.map((m) => ({ id: m.id, kind: m.kind, status: m.status, pixelWidth: m.pixelWidth, pixelHeight: m.pixelHeight })),
  );
  const canRecord = !["FAILED"].includes(captureStatus) && status?.floorId != null;

  function addObservation(u: number, v: number, time: number | null) {
    if (!selectedMedia) return;
    const o: ObservationDraft = { point: obsPoint, mediaId: selectedMedia.id, frameTimeSeconds: selectedMedia.kind === "VIDEO" ? time : null, u, v };
    setDraft((d) => ({ ...d, observations: [...d.observations, o] }));
  }

  async function act(fn: () => Promise<unknown>) {
    setError(null);
    setBusy(true);
    try {
      await fn();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
      await refresh();
    }
  }

  const onRecord = () =>
    act(async () => {
      await recordMeasurement(venueId, captureId, measurementBody(draftWithVenue));
      setDraft(emptyDraft(draft.kind));
      setVenueText(["", "", ""]);
      setFormKey((k) => k + 1);
    });

  const onWithdraw = (m: MeasurementView) => {
    const reason = window.prompt(`Why is "${m.label}" being withdrawn?`);
    if (!reason) return;
    act(() => withdrawMeasurement(venueId, captureId, m.id, reason));
  };

  const active = measurements.filter((m) => m.status === "ACTIVE");
  const resolvable = active.filter((m) => Object.keys(resolved).some((k) => k.startsWith(m.id + ":")));

  const onCalibrate = () =>
    act(async () => {
      const points = [];
      for (const m of resolvable) {
        for (const p of pointsOf(m.kind)) {
          const parts = (resolved[`${m.id}:${p}`] ?? "").split(/[\s,]+/).filter(Boolean).map(Number);
          if (parts.length !== 3 || parts.some((n) => !Number.isFinite(n))) {
            throw new Error(`Enter x, y, z (reconstruction units) for ${m.label} point ${p}.`);
          }
          points.push({ measurementId: m.id, point: p, reconstruction: parts as [number, number, number] });
        }
      }
      await calibrateCapture(venueId, captureId, { resolvedPoints: points, gravity: { source: gravity } });
    });

  const stage = status ? captureStageBadge(status.captureStage) : null;
  const calib = status ? calibrationBadge(status.state, status.reconstructionFrame) : null;

  return (
    <section aria-labelledby="calibration" className="space-y-4" data-testid="calibration-panel">
      <h2 id="calibration" className="text-lg font-medium">Measurements and calibration</h2>
      <p className="text-sm text-zinc-600">
        A reconstruction has no real-world scale on its own. Record at least two measured distances (tape or laser meter) or three
        surveyed control points, and mark each measured point in at least two photos or video frames.
      </p>
      {error && <p role="alert" className="whitespace-pre-line rounded border border-red-300 bg-red-50 p-3 text-sm text-red-800">{error}</p>}

      {status && stage && calib && (
        <div className="grid gap-2 sm:grid-cols-2">
          <div className={`rounded border p-3 ${TONE[stage.tone]}`} data-testid="capture-stage">
            <p className="font-medium">{stage.label}</p>
            <p className="text-sm">{stage.detail}</p>
          </div>
          <div className={`rounded border p-3 ${TONE[calib.tone]}`} data-testid="calibration-state">
            <p className="font-medium">{calib.label}</p>
            <p className="text-sm">{calib.detail}</p>
            <p className="mt-1 text-xs">Reconstruction frame: {reconstructionFrameLabel(status.reconstructionFrame)}</p>
          </div>
        </div>
      )}
      {status && (
        <>
          <p className="text-sm">
            Distances: {status.activeDistances} of {status.requiredDistances} needed · Control points: {status.activeControlPoints} of {status.requiredControlPoints} needed
            {status.activeFrame && (
              <> · Frame v{status.activeFrame.version}: scale {status.activeFrame.scale?.toPrecision(6) ?? "—"} ({status.activeFrame.scaleSource})</>
            )}
          </p>
          {status.requirements.length > 0 && (
            <ul className="list-disc space-y-1 pl-5 text-sm" data-testid="calibration-requirements">
              {status.requirements.map((r) => <li key={r}>{r}</li>)}
            </ul>
          )}
        </>
      )}

      {measurements.length > 0 && (
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="border-b"><th className="py-1">Label</th><th>Measured</th><th>Views</th><th>Status</th><th /></tr>
          </thead>
          <tbody>
            {measurements.map((m) => (
              <tr key={m.id} className={`border-b ${m.status === "WITHDRAWN" ? "text-zinc-400" : ""}`}>
                <td className="py-1">{m.label}<span className="block text-xs text-zinc-500">{m.kind === "DISTANCE" ? "distance" : "control point"} · {m.method}</span></td>
                <td>
                  {m.kind === "DISTANCE" ? `${m.value} ${m.unit}` : `(${m.venue?.join(", ")}) ${m.unit} in ${m.datum}`}
                </td>
                <td>{pointsOf(m.kind).map((p) => `${p}: ${m.observations.filter((o) => o.point === p).length}`).join(" · ")}</td>
                <td>{m.status === "WITHDRAWN" ? `withdrawn: ${m.withdrawnReason}` : m.usedByActiveFrame ? "used by the active frame" : "recorded"}</td>
                <td>
                  {m.status === "ACTIVE" && !m.usedByActiveFrame && (
                    <button className="text-xs underline" disabled={busy} onClick={() => onWithdraw(m)}>Withdraw</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {canRecord && (
        <fieldset key={formKey} className="space-y-3 rounded border p-3" disabled={busy}>
          <legend className="px-1 text-sm font-medium">Record a measurement</legend>
          <div className="flex flex-wrap gap-3 text-sm">
            <label>Kind{" "}
              <select className="rounded border p-1" value={draft.kind} onChange={(e) => { const k = e.target.value as MeasurementKind; setDraft(emptyDraft(k)); setObsPoint(pointsOf(k)[0]); }}>
                <option value="DISTANCE">Measured distance</option>
                <option value="CONTROL_POINT">Surveyed control point</option>
              </select>
            </label>
            <label>Label <input className="rounded border p-1" value={draft.label} onChange={(e) => setDraft({ ...draft, label: e.target.value })} /></label>
            <label>Method{" "}
              <select className="rounded border p-1" value={draft.method} onChange={(e) => setDraft({ ...draft, method: e.target.value as MeasurementMethod })}>
                {METHODS.map((m) => <option key={m} value={m}>{m.replaceAll("_", " ").toLowerCase()}</option>)}
              </select>
            </label>
            <label>Unit{" "}
              <select className="rounded border p-1" value={draft.unit} onChange={(e) => setDraft({ ...draft, unit: e.target.value as LengthUnit })}>
                {Object.keys(UNIT_METRES).map((u) => <option key={u} value={u}>{u}</option>)}
              </select>
            </label>
          </div>
          {draft.kind === "DISTANCE" ? (
            <div className="flex flex-wrap gap-3 text-sm">
              <label>Length A–B <input className="w-28 rounded border p-1" inputMode="decimal" onChange={(e) => setDraft({ ...draft, value: num(e.target.value) })} /></label>
              <label>± uncertainty (optional) <input className="w-24 rounded border p-1" inputMode="decimal" onChange={(e) => setDraft({ ...draft, uncertainty: num(e.target.value) })} /></label>
            </div>
          ) : (
            <div className="flex flex-wrap gap-3 text-sm">
              {["x", "y", "z (up)"].map((axis, i) => (
                <label key={axis}>{axis} <input className="w-24 rounded border p-1" inputMode="decimal" value={venueText[i]} onChange={(e) => setVenueText(venueText.map((t, j) => (j === i ? e.target.value : t)))} /></label>
              ))}
              <label>Survey datum <input className="rounded border p-1" value={draft.datum} onChange={(e) => setDraft({ ...draft, datum: e.target.value })} /></label>
            </div>
          )}

          <div className="space-y-2 rounded bg-zinc-50 p-2 text-sm">
            <p className="font-medium">Mark where each point is seen ({pointsOf(draft.kind).join(", ")}; at least two different photos or video frames each)</p>
            {observable.length === 0 && <p className="text-zinc-600">No accepted image or video yet: upload media first.</p>}
            <div className="flex flex-wrap gap-2">
              <select className="rounded border p-1" value={obsPoint} onChange={(e) => setObsPoint(e.target.value as PointName)}>
                {pointsOf(draft.kind).map((p) => <option key={p} value={p}>Point {p}</option>)}
              </select>
              <select className="rounded border p-1" value={obsMedia} onChange={(e) => setObsMedia(e.target.value)}>
                <option value="">Choose media…</option>
                {observable.map((m) => <option key={m.id} value={m.id}>{m.filename} ({m.kind.toLowerCase()})</option>)}
              </select>
              {selectedMedia?.kind === "VIDEO" && <input className="w-20 rounded border p-1" placeholder="time s" value={obsTime} onChange={(e) => setObsTime(e.target.value)} />}
              <input className="w-20 rounded border p-1" placeholder="u px" value={obsU} onChange={(e) => setObsU(e.target.value)} />
              <input className="w-20 rounded border p-1" placeholder="v px" value={obsV} onChange={(e) => setObsV(e.target.value)} />
              <button
                type="button"
                className="rounded border px-2"
                disabled={!selectedMedia}
                onClick={() => addObservation(num(obsU) ?? NaN, num(obsV) ?? NaN, num(obsTime))}
              >
                Add mark
              </button>
            </div>
            {selectedMedia && localFiles.get(selectedMedia.id) && (
              <PixelPicker file={localFiles.get(selectedMedia.id)!} onPick={addObservation} />
            )}
            {selectedMedia && !localFiles.get(selectedMedia.id) && (
              <p className="text-xs text-zinc-600">This file was not uploaded from this page, so it cannot be shown here; enter the pixel coordinates you read from it.</p>
            )}
            {draft.observations.length > 0 && (
              <ul className="text-xs">
                {draft.observations.map((o, i) => (
                  <li key={i}>
                    {o.point}: {media.find((m) => m.id === o.mediaId)?.filename}{o.frameTimeSeconds != null && ` @ ${o.frameTimeSeconds} s`} ({o.u}, {o.v}){" "}
                    <button type="button" className="underline" onClick={() => setDraft({ ...draft, observations: draft.observations.filter((_, j) => j !== i) })}>remove</button>
                  </li>
                ))}
              </ul>
            )}
          </div>
          {problems.length > 0 && <ul className="list-disc pl-5 text-sm text-amber-800">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
          <button className="rounded bg-black px-4 py-2 text-white disabled:opacity-50" disabled={problems.length > 0 || busy} onClick={onRecord}>
            Record measurement
          </button>
        </fieldset>
      )}

      {status && ["READY_TO_CALIBRATE", "REJECTED", "CALIBRATED"].includes(status.state) && active.length > 0 && (
        <fieldset className="space-y-3 rounded border p-3" disabled={busy}>
          <legend className="px-1 text-sm font-medium">Calibrate the reconstruction</legend>
          <p className="text-sm text-zinc-600">
            The marked pixels are not yet located in the reconstruction automatically. For each measurement you use, enter the
            reconstruction coordinates (x y z, as shown by the viewer) of its points. Measured values come from the recorded
            measurements.
          </p>
          {active.map((m) => (
            <div key={m.id} className="flex flex-wrap items-center gap-2 text-sm">
              <span className="w-40 truncate">{m.label}</span>
              {pointsOf(m.kind).map((p) => (
                <label key={p}>{p}{" "}
                  <input className="w-44 rounded border p-1 font-mono" placeholder="x y z" value={resolved[`${m.id}:${p}`] ?? ""}
                    onChange={(e) => setResolved({ ...resolved, [`${m.id}:${p}`]: e.target.value })} />
                </label>
              ))}
            </div>
          ))}
          <label className="block text-sm">Gravity{" "}
            <select className="rounded border p-1" value={gravity} onChange={(e) => setGravity(e.target.value as GravitySource)}>
              <option value="RECONSTRUCTED_FLOOR_PLANE">Reconstructed floor plane</option>
              <option value="NONE">None (scale only)</option>
            </select>
            <span className="ml-2 text-xs text-zinc-600">Control points carry their own up direction.</span>
          </label>
          <button className="rounded bg-black px-4 py-2 text-white disabled:opacity-50" disabled={busy || resolvable.length === 0} onClick={onCalibrate}>
            Calibrate
          </button>
        </fieldset>
      )}
    </section>
  );
}
