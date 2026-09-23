// Rigid-transform math for AR relocalization, run client-side at frame rate so the WebXR session doesn't
// wait on a network round trip to smooth between fixes. Mirrors (independently, not by codegen)
// dev.chaya.api.ar.CoordinateTransform on the backend and apps/ios-ar/Sources/ChayaARCore/AnchorMath.swift
// on iOS -- all three are exercised against the same cases in their own test suite; see docs/ar.md.
//
// Never claim centimeter accuracy without measurement: `blend`'s residualMeters is the real, measured
// largest pairwise translation disagreement between the anchors that took part, not an assumed bound.

export interface Pose {
  x: number;
  y: number;
  z: number;
  qx: number;
  qy: number;
  qz: number;
  qw: number;
}

function quatMultiply(
  ax: number, ay: number, az: number, aw: number,
  bx: number, by: number, bz: number, bw: number,
): [number, number, number, number] {
  return [
    aw * bx + ax * bw + ay * bz - az * by,
    aw * by - ax * bz + ay * bw + az * bx,
    aw * bz + ax * by - ay * bx + az * bw,
    aw * bw - ax * bx - ay * by - az * bz,
  ];
}

function normalize(p: Pose): Pose {
  const norm = Math.sqrt(p.qx * p.qx + p.qy * p.qy + p.qz * p.qz + p.qw * p.qw);
  if (norm === 0) return { x: p.x, y: p.y, z: p.z, qx: 0, qy: 0, qz: 0, qw: 1 };
  return { x: p.x, y: p.y, z: p.z, qx: p.qx / norm, qy: p.qy / norm, qz: p.qz / norm, qw: p.qw / norm };
}

function rotate(byQuaternion: Pose, x: number, y: number, z: number): [number, number, number] {
  const { qx, qy, qz, qw } = byQuaternion;
  const qv = quatMultiply(qx, qy, qz, qw, x, y, z, 0);
  const result = quatMultiply(qv[0], qv[1], qv[2], qv[3], -qx, -qy, -qz, qw);
  return [result[0], result[1], result[2]];
}

/** Composes two poses: applies `inner` in `outer`'s frame, then `outer` itself. */
export function compose(outer: Pose, inner: Pose): Pose {
  const [rx, ry, rz] = rotate(outer, inner.x, inner.y, inner.z);
  const [qx, qy, qz, qw] = quatMultiply(outer.qx, outer.qy, outer.qz, outer.qw, inner.qx, inner.qy, inner.qz, inner.qw);
  return { x: outer.x + rx, y: outer.y + ry, z: outer.z + rz, qx, qy, qz, qw };
}

export function invert(pose: Pose): Pose {
  const n = normalize(pose);
  const iq: Pose = { x: 0, y: 0, z: 0, qx: -n.qx, qy: -n.qy, qz: -n.qz, qw: n.qw };
  const [x, y, z] = rotate(iq, -n.x, -n.y, -n.z);
  return { x, y, z, qx: iq.qx, qy: iq.qy, qz: iq.qz, qw: iq.qw };
}

/** deviceToVenue = digitalPose (anchor's known venue-frame pose) composed with the inverse of
 * observedPose (the anchor's pose as this device's own tracking session currently reports it -- real
 * WebXR hit-test output, never fabricated). */
export function deviceToVenueFromAnchor(digitalPose: Pose, observedPose: Pose): Pose {
  return compose(digitalPose, invert(observedPose));
}

export interface Blended {
  transform: Pose;
  residualMeters: number;
}

function translationDistance(a: Pose, b: Pose): number {
  return Math.sqrt((a.x - b.x) ** 2 + (a.y - b.y) ** 2 + (a.z - b.z) ** 2);
}

/** Blends several anchors' candidate transforms; residualMeters is the largest real pairwise translation
 * disagreement among them (0 for a single anchor). */
export function blend(candidates: Pose[]): Blended {
  if (candidates.length === 0) throw new Error("at least one anchor observation is required");
  if (candidates.length === 1) return { transform: normalize(candidates[0]), residualMeters: 0 };

  let residual = 0;
  for (let i = 0; i < candidates.length; i++) {
    for (let j = i + 1; j < candidates.length; j++) {
      residual = Math.max(residual, translationDistance(candidates[i], candidates[j]));
    }
  }

  const reference = normalize(candidates[0]);
  let sx = 0, sy = 0, sz = 0, sqx = 0, sqy = 0, sqz = 0, sqw = 0;
  for (const c of candidates) {
    const n = normalize(c);
    const dot = n.qx * reference.qx + n.qy * reference.qy + n.qz * reference.qz + n.qw * reference.qw;
    const sign = dot < 0 ? -1 : 1;
    sx += n.x; sy += n.y; sz += n.z;
    sqx += sign * n.qx; sqy += sign * n.qy; sqz += sign * n.qz; sqw += sign * n.qw;
  }
  const count = candidates.length;
  const averaged: Pose = { x: sx / count, y: sy / count, z: sz / count, qx: sqx / count, qy: sqy / count, qz: sqz / count, qw: sqw / count };
  return { transform: normalize(averaged), residualMeters: residual };
}

/** Linear translation + nlerp quaternion blend between two anchor-derived transforms, `t` clamped to
 * [0, 1]. Used to smooth between relocalization fixes while walking, not to invent new tracking data. */
export function interpolate(a: Pose, b: Pose, t: number): Pose {
  const clamped = Math.max(0, Math.min(1, t));
  const na = normalize(a);
  const nb = normalize(b);
  const dot = na.qx * nb.qx + na.qy * nb.qy + na.qz * nb.qz + na.qw * nb.qw;
  const sign = dot < 0 ? -1 : 1;
  const lerp = (x: number, y: number) => x + (y - x) * clamped;
  return normalize({
    x: lerp(na.x, nb.x),
    y: lerp(na.y, nb.y),
    z: lerp(na.z, nb.z),
    qx: lerp(na.qx, sign * nb.qx),
    qy: lerp(na.qy, sign * nb.qy),
    qz: lerp(na.qz, sign * nb.qz),
    qw: lerp(na.qw, sign * nb.qw),
  });
}
