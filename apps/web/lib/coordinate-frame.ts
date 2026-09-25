// The Chaya canonical venue coordinate system and the similarity transform into it (docs/coordinate-frames.md).
// Mirrors chaya_worker.frames (Python) and dev.chaya.api.frame.Similarity (Java); the shared synthetic fixture
// packages/contracts/fixtures/synthetic-calibration.json checks that all three agree.
//
//   units metres; up +Z (opposite gravity); X, Y horizontal; right-handed; rotations as unit quaternions (w, x, y, z)
//   X_canonical = scale * R(rotation) * X_reconstruction + translation
//
// A reconstruction's .ksplat is in its own reconstruction frame (arbitrary scale, rotation, origin). The viewer may
// place it in canonical metres -- and overlay canonical POIs and routes on it -- only through a canonical frame.

export const CANONICAL_UNITS = "m";
export const CANONICAL_UP_AXIS = "+Z";

export type Vec3 = [number, number, number];

export interface Quaternion {
  w: number;
  x: number;
  y: number;
  z: number;
}

/** Mirrors dev.chaya.api.frame.FrameDtos.FrameView (the fields a client uses). */
export interface CoordinateFrame {
  id: string;
  sourceRunId: string;
  floorId: string | null;
  version: number;
  status: "ACTIVE" | "SUPERSEDED";
  canonical: boolean;
  metricStatus: "METRIC" | "NOT_CALIBRATED";
  gravityStatus: "ALIGNED" | "NOT_ALIGNED";
  horizontalDatum: "NONE" | "FLOOR_LOCAL" | "VENUE_CONTROL_POINTS";
  scale: number | null;
  rotation: Quaternion | null;
  translation: { x: number; y: number; z: number } | null;
  method: string;
  calibratedAt: string;
}

export interface Similarity {
  scale: number;
  rotation: Quaternion;
  translation: Vec3;
}

export class NotCalibratedError extends Error {}

export function normalizeQuaternion(q: Quaternion): Quaternion {
  const n = Math.hypot(q.w, q.x, q.y, q.z);
  if (!Number.isFinite(n) || n < 1e-12) throw new Error("a rotation quaternion must be finite and non-zero");
  return { w: q.w / n, x: q.x / n, y: q.y / n, z: q.z / n };
}

/** Hamilton product a * b (apply b first, then a). */
export function multiplyQuaternions(a: Quaternion, b: Quaternion): Quaternion {
  return {
    w: a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
    x: a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
    y: a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
    z: a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
  };
}

export function conjugate(q: Quaternion): Quaternion {
  return { w: q.w, x: -q.x, y: -q.y, z: -q.z };
}

export function rotate(q: Quaternion, v: Vec3): Vec3 {
  const { w, x, y, z } = normalizeQuaternion(q);
  const [vx, vy, vz] = v;
  return [
    (1 - 2 * (y * y + z * z)) * vx + 2 * (x * y - w * z) * vy + 2 * (x * z + w * y) * vz,
    2 * (x * y + w * z) * vx + (1 - 2 * (x * x + z * z)) * vy + 2 * (y * z - w * x) * vz,
    2 * (x * z - w * y) * vx + 2 * (y * z + w * x) * vy + (1 - 2 * (x * x + y * y)) * vz,
  ];
}

export function similarity(scale: number, rotation: Quaternion, translation: Vec3): Similarity {
  if (!Number.isFinite(scale) || scale <= 0) throw new Error(`scale must be finite and positive, got ${scale}`);
  if (!translation.every(Number.isFinite)) throw new Error("translation must be finite");
  return { scale, rotation: normalizeQuaternion(rotation), translation };
}

export const IDENTITY: Similarity = similarity(1, { w: 1, x: 0, y: 0, z: 0 }, [0, 0, 0]);

export function applySimilarity(t: Similarity, p: Vec3): Vec3 {
  const r = rotate(t.rotation, p);
  return [t.scale * r[0] + t.translation[0], t.scale * r[1] + t.translation[1], t.scale * r[2] + t.translation[2]];
}

export function invertSimilarity(t: Similarity): Similarity {
  const inv = conjugate(t.rotation);
  const r = rotate(inv, t.translation);
  return similarity(1 / t.scale, inv, [-r[0] / t.scale, -r[1] / t.scale, -r[2] / t.scale]);
}

/** outer after inner: p -> outer(inner(p)). */
export function composeSimilarity(outer: Similarity, inner: Similarity): Similarity {
  const r = rotate(outer.rotation, inner.translation);
  return similarity(outer.scale * inner.scale, multiplyQuaternions(outer.rotation, inner.rotation), [
    outer.scale * r[0] + outer.translation[0],
    outer.scale * r[1] + outer.translation[1],
    outer.scale * r[2] + outer.translation[2],
  ]);
}

/** The reconstruction -> canonical transform of a frame. Throws NotCalibratedError unless the frame is canonical:
 * a client never treats reconstruction units as metres or any reconstruction axis as up. */
export function toCanonical(frame: CoordinateFrame | null | undefined): Similarity {
  if (!frame || !frame.canonical || frame.scale == null || !frame.rotation || !frame.translation) {
    throw new NotCalibratedError(
      frame ? `coordinate frame ${frame.id} is not canonical (${frame.metricStatus}, ${frame.gravityStatus})`
        : "this reconstruction has no calibrated coordinate frame",
    );
  }
  const { x, y, z } = frame.translation;
  return similarity(frame.scale, frame.rotation, [x, y, z]);
}

export function isCanonical(frame: CoordinateFrame | null | undefined): frame is CoordinateFrame {
  return !!frame && frame.canonical && frame.scale != null && !!frame.rotation && !!frame.translation;
}
