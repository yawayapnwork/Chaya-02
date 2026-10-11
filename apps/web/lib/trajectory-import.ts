// Importing a reconnaissance trajectory recorded by another tracker (docs/capture-hud.md section 2, "operator-trajectory").
// Pure: parses the file's text, checks it, and returns wire samples or the reasons it cannot be used.
//
// Accepted formats:
//   JSON  { "units": "m", "frameId"?: string, "startedAtMs"?: number,
//           "samples": [{ "t": seconds, "x": m, "y": m, "yawDegrees"?: deg }],
//           "surfaces"?: [{ "t": s, "originX", "originY", "hitX", "hitY", "kind": "WALL"|"FLOOR", "normalDegrees"? }] }
//   CSV   a header "t,x,y" or "t,x,y,yawDegrees" and one row per sample; the units must be stated in a first comment
//         line "# units: m".
// Metres must be stated explicitly: a trajectory in unknown units would silently make every distance wrong. A video file is
// refused with the reason (it has no positions to import).

import type { PoseSample, SurfaceSampleWire } from "./hud-types.ts";

export const MAX_IMPORT_SAMPLES = 20_000;

export interface ImportedTrajectory {
  frameId: string;
  samples: PoseSample[];
  surfaces: SurfaceSampleWire[];
  durationSeconds: number;
  pathLengthMeters: number;
  warnings: string[];
}

export type ImportResult = { ok: true; value: ImportedTrajectory } | { ok: false; errors: string[] };

const METRE_UNITS = new Set(["m", "metre", "metres", "meter", "meters"]);

export function isVideoFile(name: string, type: string): boolean {
  return type.startsWith("video/") || /\.(mp4|mov|m4v|webm|mkv|avi)$/i.test(name);
}

/**
 * @param nowMs used to place the samples in time when the file has no startedAtMs: the trajectory is taken to have just
 *              ended (stated as a warning). It is never presented as live.
 */
export function parseTrajectory(name: string, type: string, text: string, nowMs: number, newFrameId: () => string): ImportResult {
  if (isVideoFile(name, type)) {
    return { ok: false, errors: ["A video file has no camera positions to import. Video alone cannot give metric camera poses during the walk; "
      + "import a trajectory exported by a tracker (t, x, y in metres) instead."] };
  }
  const trimmed = text.trim();
  if (trimmed.startsWith("{")) return parseJson(trimmed, nowMs, newFrameId);
  return parseCsv(trimmed, nowMs, newFrameId);
}

interface RawSample { t: number; x: number; y: number; yawDegrees: number | null }

function finish(raw: RawSample[], surfacesRaw: (SurfaceSampleWire & { t: number })[], frameId: string, startedAtMs: number | null,
                nowMs: number, warnings: string[], errors: string[]): ImportResult {
  if (raw.length === 0) errors.push("the file contains no samples");
  if (raw.length > MAX_IMPORT_SAMPLES) errors.push(`at most ${MAX_IMPORT_SAMPLES} samples can be imported (the file has ${raw.length})`);
  for (let i = 1; i < raw.length && errors.length < 10; i++) {
    if (!(raw[i].t > raw[i - 1].t)) errors.push(`sample ${i + 1}: time does not increase (${raw[i].t} after ${raw[i - 1].t})`);
  }
  if (errors.length) return { ok: false, errors };
  const duration = raw[raw.length - 1].t - raw[0].t;
  let start = startedAtMs;
  if (start === null) {
    start = nowMs - Math.round(duration * 1000) - Math.round(raw[0].t * 1000);
    warnings.push("the file has no startedAtMs: its samples are placed as if the walk just ended");
  }
  let length = 0;
  for (let i = 1; i < raw.length; i++) length += Math.hypot(raw[i].x - raw[i - 1].x, raw[i].y - raw[i - 1].y);
  const s0 = start;
  return {
    ok: true,
    value: {
      frameId,
      samples: raw.map((r) => ({ capturedAtMs: s0 + Math.round(r.t * 1000), x: r.x, y: r.y, yawDegrees: r.yawDegrees,
        source: "operator-trajectory", frameId, frameAlignment: "NATIVE" })),
      surfaces: surfacesRaw.map(({ t, ...s }) => ({ ...s, capturedAtMs: s0 + Math.round(t * 1000) })),
      durationSeconds: duration,
      pathLengthMeters: length,
      warnings,
    },
  };
}

function num(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

function parseJson(text: string, nowMs: number, newFrameId: () => string): ImportResult {
  let doc: unknown;
  try {
    doc = JSON.parse(text);
  } catch (e) {
    return { ok: false, errors: [`not valid JSON: ${e instanceof Error ? e.message : String(e)}`] };
  }
  const d = doc as Record<string, unknown>;
  const errors: string[] = [];
  const warnings: string[] = [];
  const units = typeof d.units === "string" ? d.units.toLowerCase() : null;
  if (!units || !METRE_UNITS.has(units)) errors.push(`"units" must state metres ("m"); got ${JSON.stringify(d.units ?? null)}`);
  if (!Array.isArray(d.samples)) errors.push(`"samples" must be an array of { t, x, y, yawDegrees? }`);
  const raw: RawSample[] = [];
  for (const [i, s] of (Array.isArray(d.samples) ? d.samples : []).entries()) {
    const o = s as Record<string, unknown>;
    const t = num(o.t), x = num(o.x), y = num(o.y);
    const yaw = o.yawDegrees === undefined || o.yawDegrees === null ? null : num(o.yawDegrees);
    if (t === null || x === null || y === null || (o.yawDegrees != null && yaw === null)) {
      if (errors.length < 10) errors.push(`sample ${i + 1}: t, x and y must be finite numbers`);
      continue;
    }
    raw.push({ t, x, y, yawDegrees: yaw });
  }
  const frameId = typeof d.frameId === "string" && d.frameId.length > 0 && d.frameId.length <= 100 ? d.frameId : newFrameId();
  const surfaces: (SurfaceSampleWire & { t: number })[] = [];
  for (const [i, s] of (Array.isArray(d.surfaces) ? d.surfaces : []).entries()) {
    const o = s as Record<string, unknown>;
    const vals = [o.t, o.originX, o.originY, o.hitX, o.hitY].map(num);
    if (vals.some((v) => v === null) || (o.kind !== "WALL" && o.kind !== "FLOOR")) {
      if (errors.length < 10) errors.push(`surface ${i + 1}: t, originX, originY, hitX, hitY must be numbers and kind WALL or FLOOR`);
      continue;
    }
    const [t, originX, originY, hitX, hitY] = vals as number[];
    surfaces.push({ t, capturedAtMs: 0, originX, originY, hitX, hitY, kind: o.kind as "WALL" | "FLOOR",
      normalDegrees: num(o.normalDegrees), source: "operator-trajectory", frameId });
  }
  const startedAtMs = num(d.startedAtMs);
  return finish(raw, surfaces, frameId, startedAtMs, nowMs, warnings, errors);
}

function parseCsv(text: string, nowMs: number, newFrameId: () => string): ImportResult {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter((l) => l.length > 0);
  const errors: string[] = [];
  const comments = lines.filter((l) => l.startsWith("#"));
  const unitLine = comments.map((l) => /^#\s*units\s*:\s*(\S+)/i.exec(l)?.[1]?.toLowerCase()).find((u) => u !== undefined);
  if (!unitLine || !METRE_UNITS.has(unitLine)) errors.push(`the units must be stated as metres in a first line "# units: m"`);
  const rows = lines.filter((l) => !l.startsWith("#"));
  const header = (rows.shift() ?? "").split(",").map((h) => h.trim());
  const it = header.indexOf("t"), ix = header.indexOf("x"), iy = header.indexOf("y"), iyaw = header.indexOf("yawDegrees");
  if (it < 0 || ix < 0 || iy < 0) errors.push(`the header must name the columns t, x, y (and optionally yawDegrees); got "${header.join(",")}"`);
  const raw: RawSample[] = [];
  if (errors.length === 0) {
    rows.forEach((row, i) => {
      const c = row.split(",").map((v) => v.trim());
      const t = Number(c[it]), x = Number(c[ix]), y = Number(c[iy]);
      const yaw = iyaw >= 0 && c[iyaw] !== undefined && c[iyaw] !== "" ? Number(c[iyaw]) : null;
      if (![t, x, y].every(Number.isFinite) || (yaw !== null && !Number.isFinite(yaw))) {
        if (errors.length < 10) errors.push(`row ${i + 2}: t, x and y must be numbers`);
        return;
      }
      raw.push({ t, x, y, yawDegrees: yaw });
    });
  }
  return finish(raw, [], newFrameId(), null, nowMs, [], errors);
}
