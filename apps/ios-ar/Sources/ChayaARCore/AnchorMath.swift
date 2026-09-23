import Foundation

/// Rigid-transform math for AR relocalization, run on-device at frame rate so ARKit doesn't wait on a
/// network round trip to smooth between fixes. Mirrors (independently, not by codegen)
/// dev.chaya.api.ar.CoordinateTransform on the backend and apps/web/lib/ar-anchor-math.ts on Android/WebXR
/// -- all three are exercised against the same cases in their own test suite; see docs/ar.md.
///
/// Never claim centimeter accuracy without measurement: `blend`'s `residualMeters` is the real, measured
/// largest pairwise translation disagreement between the anchors that took part, not an assumed bound.
public enum AnchorMath {

    static func quatMultiply(_ ax: Double, _ ay: Double, _ az: Double, _ aw: Double,
                              _ bx: Double, _ by: Double, _ bz: Double, _ bw: Double) -> (Double, Double, Double, Double) {
        (
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz
        )
    }

    static func normalize(_ p: Pose) -> Pose {
        let norm = (p.qx * p.qx + p.qy * p.qy + p.qz * p.qz + p.qw * p.qw).squareRoot()
        if norm == 0 { return Pose(x: p.x, y: p.y, z: p.z, qx: 0, qy: 0, qz: 0, qw: 1) }
        return Pose(x: p.x, y: p.y, z: p.z, qx: p.qx / norm, qy: p.qy / norm, qz: p.qz / norm, qw: p.qw / norm)
    }

    static func rotate(_ byQuaternion: Pose, _ x: Double, _ y: Double, _ z: Double) -> (Double, Double, Double) {
        let (qx, qy, qz, qw) = (byQuaternion.qx, byQuaternion.qy, byQuaternion.qz, byQuaternion.qw)
        let qv = quatMultiply(qx, qy, qz, qw, x, y, z, 0)
        let result = quatMultiply(qv.0, qv.1, qv.2, qv.3, -qx, -qy, -qz, qw)
        return (result.0, result.1, result.2)
    }

    /// Composes two poses: applies `inner` in `outer`'s frame, then `outer` itself.
    public static func compose(_ outer: Pose, _ inner: Pose) -> Pose {
        let (rx, ry, rz) = rotate(outer, inner.x, inner.y, inner.z)
        let q = quatMultiply(outer.qx, outer.qy, outer.qz, outer.qw, inner.qx, inner.qy, inner.qz, inner.qw)
        return Pose(x: outer.x + rx, y: outer.y + ry, z: outer.z + rz, qx: q.0, qy: q.1, qz: q.2, qw: q.3)
    }

    public static func invert(_ pose: Pose) -> Pose {
        let n = normalize(pose)
        let inverseRotation = Pose(x: 0, y: 0, z: 0, qx: -n.qx, qy: -n.qy, qz: -n.qz, qw: n.qw)
        let (x, y, z) = rotate(inverseRotation, -n.x, -n.y, -n.z)
        return Pose(x: x, y: y, z: z, qx: inverseRotation.qx, qy: inverseRotation.qy, qz: inverseRotation.qz, qw: inverseRotation.qw)
    }

    /// deviceToVenue = digitalPose (anchor's known venue-frame pose) composed with the inverse of
    /// observedPose (the anchor's pose as ARKit currently reports it -- real detection, never fabricated).
    public static func deviceToVenue(fromAnchorDigitalPose digitalPose: Pose, observedPose: Pose) -> Pose {
        compose(digitalPose, invert(observedPose))
    }

    public struct Blended: Equatable {
        public let transform: Pose
        public let residualMeters: Double
    }

    private static func translationDistance(_ a: Pose, _ b: Pose) -> Double {
        ((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)).squareRoot()
    }

    /// Blends several anchors' candidate transforms; `residualMeters` is the largest real pairwise
    /// translation disagreement among them (0 for a single anchor).
    public static func blend(_ candidates: [Pose]) -> Blended {
        precondition(!candidates.isEmpty, "at least one anchor observation is required")
        if candidates.count == 1 {
            return Blended(transform: normalize(candidates[0]), residualMeters: 0)
        }
        var residual = 0.0
        for i in 0..<candidates.count {
            for j in (i + 1)..<candidates.count {
                residual = max(residual, translationDistance(candidates[i], candidates[j]))
            }
        }
        let reference = normalize(candidates[0])
        var sx = 0.0, sy = 0.0, sz = 0.0, sqx = 0.0, sqy = 0.0, sqz = 0.0, sqw = 0.0
        for c in candidates {
            let n = normalize(c)
            let dot = n.qx * reference.qx + n.qy * reference.qy + n.qz * reference.qz + n.qw * reference.qw
            let sign = dot < 0 ? -1.0 : 1.0
            sx += n.x; sy += n.y; sz += n.z
            sqx += sign * n.qx; sqy += sign * n.qy; sqz += sign * n.qz; sqw += sign * n.qw
        }
        let count = Double(candidates.count)
        let averaged = Pose(x: sx / count, y: sy / count, z: sz / count, qx: sqx / count, qy: sqy / count, qz: sqz / count, qw: sqw / count)
        return Blended(transform: normalize(averaged), residualMeters: residual)
    }

    /// Linear translation + nlerp quaternion blend between two anchor-derived transforms, `t` clamped to
    /// [0, 1]. Used to smooth between relocalization fixes while walking, not to invent new tracking data.
    public static func interpolate(_ a: Pose, _ b: Pose, _ t: Double) -> Pose {
        let clamped = max(0.0, min(1.0, t))
        let na = normalize(a)
        let nb = normalize(b)
        let dot = na.qx * nb.qx + na.qy * nb.qy + na.qz * nb.qz + na.qw * nb.qw
        let sign = dot < 0 ? -1.0 : 1.0
        func lerp(_ x: Double, _ y: Double) -> Double { x + (y - x) * clamped }
        return normalize(Pose(
            x: lerp(na.x, nb.x), y: lerp(na.y, nb.y), z: lerp(na.z, nb.z),
            qx: lerp(na.qx, sign * nb.qx), qy: lerp(na.qy, sign * nb.qy),
            qz: lerp(na.qz, sign * nb.qz), qw: lerp(na.qw, sign * nb.qw)
        ))
    }
}
