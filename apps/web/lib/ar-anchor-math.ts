// Rigid-transform math for AR relocalization, run client-side at frame rate so the WebXR session doesn't
// wait on a network round trip to smooth between fixes. Mirrors (independently, not by codegen)
// dev.chaya.api.ar.CoordinateTransform on the backend and apps/ios-ar/Sources/ChayaARCore/AnchorMath.swift
// on iOS -- all three are exercised against the same cases in their own test suite; see docs/ar.md.
//
// Never claim centimeter accuracy without measurement: `solve` reports each anchor's measured residual (its observation
// mapped through the solved transform, against its registered pose), not an assumed bound.

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
 * observedPose (the anchor's marker pose as this device's own tracking session measured it -- on Android, a
 * WebXR image-tracking result for that anchor's registered image; never a hit test, never fabricated). */
export function deviceToVenueFromAnchor(digitalPose: Pose, observedPose: Pose): Pose {
  return compose(digitalPose, invert(observedPose));
}

/** One anchor's contribution to a multi-anchor solve: its registered canonical pose and the device's observation of it. */
export interface AnchorPair {
  digitalPose: Pose;
  observedPose: Pose;
}

export interface AnchorResidual {
  index: number;
  translationMeters: number;
  rotationDegrees: number;
  /** false: set aside as inconsistent with the other anchors (moved, misidentified or mis-registered). */
  used: boolean;
}

export interface Solution {
  transform: Pose;
  residuals: AnchorResidual[];
  maxResidualMeters: number;
  maxResidualDegrees: number;
  /** false when the anchors still in use disagree beyond the limits (only possible with two left). */
  consistent: boolean;
}

function translationDistance(a: Pose, b: Pose): number {
  return Math.sqrt((a.x - b.x) ** 2 + (a.y - b.y) ** 2 + (a.z - b.z) ** 2);
}

function fit(anchors: readonly AnchorPair[], used: readonly boolean[]): Pose {
  let reference: Pose | null = null;
  let sqx = 0, sqy = 0, sqz = 0, sqw = 0, dx = 0, dy = 0, dz = 0, ox = 0, oy = 0, oz = 0, n = 0;
  for (let i = 0; i < anchors.length; i++) {
    if (!used[i]) continue;
    const a = anchors[i];
    const c = normalize(deviceToVenueFromAnchor(a.digitalPose, a.observedPose));
    if (reference === null) reference = c;
    // q and -q are the same rotation: bring every candidate into the reference's hemisphere before averaging.
    const sign = c.qx * reference.qx + c.qy * reference.qy + c.qz * reference.qz + c.qw * reference.qw < 0 ? -1 : 1;
    sqx += sign * c.qx; sqy += sign * c.qy; sqz += sign * c.qz; sqw += sign * c.qw;
    dx += a.digitalPose.x; dy += a.digitalPose.y; dz += a.digitalPose.z;
    ox += a.observedPose.x; oy += a.observedPose.y; oz += a.observedPose.z;
    n++;
  }
  const rotation = normalize({ x: 0, y: 0, z: 0, qx: sqx / n, qy: sqy / n, qz: sqz / n, qw: sqw / n });
  const [rx, ry, rz] = rotate(rotation, ox / n, oy / n, oz / n);
  return { x: dx / n - rx, y: dy / n - ry, z: dz / n - rz, qx: rotation.qx, qy: rotation.qy, qz: rotation.qz, qw: rotation.qw };
}

function residualsOf(anchors: readonly AnchorPair[], transform: Pose): [number, number][] {
  return anchors.map((a) => {
    const predicted = normalize(compose(transform, a.observedPose));
    const digital = normalize(a.digitalPose);
    const dot = Math.abs(predicted.qx * digital.qx + predicted.qy * digital.qy + predicted.qz * digital.qz + predicted.qw * digital.qw);
    return [translationDistance(predicted, digital), (2 * Math.acos(Math.min(1, dot)) * 180) / Math.PI];
  });
}

/**
 * The device -> venue transform from one or more anchors seen at once. Mirrors dev.chaya.api.ar.CoordinateTransform#solve
 * (the server is authoritative; this copy lets the client check an answer and run the same cases in its own tests).
 * Rotation: the candidates' rotations (digital * observed^-1), averaged. Translation: the least-squares one for that
 * rotation, mapping the observed markers' centroid onto their registered centroid. Each anchor's residual is its
 * observation mapped through the transform, compared with its registered pose. With three or more anchors the worst one
 * beyond the limits is set aside and the rest re-solved while two remain; two that disagree are returned inconsistent.
 */
export function solve(anchors: readonly AnchorPair[], maxResidualMeters: number, maxResidualDegrees: number): Solution {
  if (anchors.length === 0) throw new Error("at least one anchor observation is required");
  const used = anchors.map(() => true);
  for (;;) {
    const transform = fit(anchors, used);
    const err = residualsOf(anchors, transform);
    let worst = -1, worstScore = 0, usedCount = 0, maxM = 0, maxDeg = 0;
    for (let i = 0; i < err.length; i++) {
      if (!used[i]) continue;
      const [m, deg] = err[i];
      usedCount++;
      maxM = Math.max(maxM, m);
      maxDeg = Math.max(maxDeg, deg);
      const score = Math.max(m / maxResidualMeters, deg / maxResidualDegrees);
      if (score > worstScore) {
        worstScore = score;
        worst = i;
      }
    }
    const within = worstScore <= 1;
    if (within || usedCount <= 2) {
      return {
        transform,
        residuals: err.map(([m, deg], i) => ({ index: i, translationMeters: m, rotationDegrees: deg, used: used[i] })),
        maxResidualMeters: maxM,
        maxResidualDegrees: maxDeg,
        consistent: within,
      };
    }
    used[worst] = false;
  }
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
