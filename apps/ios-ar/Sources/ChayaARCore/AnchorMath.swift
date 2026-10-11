import Foundation

/// Rigid-transform math for AR relocalization, run on-device at frame rate so ARKit doesn't wait on a
/// network round trip to smooth between fixes. Mirrors (independently, not by codegen)
/// dev.chaya.api.ar.CoordinateTransform on the backend and apps/web/lib/ar-anchor-math.ts on Android/WebXR
/// -- all three are exercised against the same cases in their own test suite; see docs/ar.md.
///
/// Never claim centimeter accuracy without measurement: `solve` reports each anchor's measured residual (its
/// observation mapped through the solved transform, against its registered pose), not an assumed bound.
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

    /// One anchor's contribution to a multi-anchor solve: its registered canonical pose and the device's observation of it.
    public struct AnchorPair: Equatable, Sendable {
        public let digitalPose: Pose
        public let observedPose: Pose
        public init(digitalPose: Pose, observedPose: Pose) { self.digitalPose = digitalPose; self.observedPose = observedPose }
    }

    public struct AnchorResidual: Equatable, Sendable {
        public let index: Int
        public let translationMeters: Double
        public let rotationDegrees: Double
        /// false: set aside as inconsistent with the other anchors (moved, misidentified or mis-registered).
        public let used: Bool
    }

    public struct Solution: Equatable, Sendable {
        public let transform: Pose
        public let residuals: [AnchorResidual]
        public let maxResidualMeters: Double
        public let maxResidualDegrees: Double
        /// false when the anchors still in use disagree beyond the limits (only possible with two left).
        public let consistent: Bool
        public var rejected: [Int] { residuals.filter { !$0.used }.map(\.index) }
    }

    private static func translationDistance(_ a: Pose, _ b: Pose) -> Double {
        ((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)).squareRoot()
    }

    static func fit(_ anchors: [AnchorPair], used: [Bool]) -> Pose {
        var reference: Pose?
        var sqx = 0.0, sqy = 0.0, sqz = 0.0, sqw = 0.0, dx = 0.0, dy = 0.0, dz = 0.0, ox = 0.0, oy = 0.0, oz = 0.0, n = 0.0
        for (i, a) in anchors.enumerated() where used[i] {
            let c = normalize(deviceToVenue(fromAnchorDigitalPose: a.digitalPose, observedPose: a.observedPose))
            let ref = reference ?? c
            reference = ref
            // q and -q are the same rotation: bring every candidate into the reference's hemisphere before averaging.
            let sign = c.qx * ref.qx + c.qy * ref.qy + c.qz * ref.qz + c.qw * ref.qw < 0 ? -1.0 : 1.0
            sqx += sign * c.qx; sqy += sign * c.qy; sqz += sign * c.qz; sqw += sign * c.qw
            dx += a.digitalPose.x; dy += a.digitalPose.y; dz += a.digitalPose.z
            ox += a.observedPose.x; oy += a.observedPose.y; oz += a.observedPose.z
            n += 1
        }
        let rotation = normalize(Pose(x: 0, y: 0, z: 0, qx: sqx / n, qy: sqy / n, qz: sqz / n, qw: sqw / n))
        let (rx, ry, rz) = rotate(rotation, ox / n, oy / n, oz / n)
        return Pose(x: dx / n - rx, y: dy / n - ry, z: dz / n - rz, qx: rotation.qx, qy: rotation.qy, qz: rotation.qz, qw: rotation.qw)
    }

    static func residuals(_ anchors: [AnchorPair], _ transform: Pose) -> [(Double, Double)] {
        anchors.map { a in
            let predicted = normalize(compose(transform, a.observedPose))
            let digital = normalize(a.digitalPose)
            let dot = abs(predicted.qx * digital.qx + predicted.qy * digital.qy + predicted.qz * digital.qz + predicted.qw * digital.qw)
            return (translationDistance(predicted, digital), 2 * acos(min(1, dot)) * 180 / Double.pi)
        }
    }

    /// The device -> venue transform from one or more anchors seen at once. Mirrors dev.chaya.api.ar.CoordinateTransform.solve
    /// (the server is authoritative; this copy runs the same cases in its own tests). Rotation: the candidates' rotations
    /// (digital * observed^-1), averaged. Translation: the least-squares one for that rotation, mapping the observed
    /// markers' centroid onto their registered centroid. Each anchor's residual is its observation mapped through the
    /// transform, compared with its registered pose. With three or more anchors the worst one beyond the limits is set aside
    /// and the rest re-solved while two remain; two that disagree are returned inconsistent.
    public static func solve(_ anchors: [AnchorPair], maxResidualMeters: Double, maxResidualDegrees: Double) -> Solution {
        precondition(!anchors.isEmpty, "at least one anchor observation is required")
        var used = Array(repeating: true, count: anchors.count)
        while true {
            let transform = fit(anchors, used: used)
            let err = residuals(anchors, transform)
            var worst = -1, worstScore = 0.0, usedCount = 0, maxM = 0.0, maxDeg = 0.0
            for (i, e) in err.enumerated() where used[i] {
                usedCount += 1
                maxM = max(maxM, e.0)
                maxDeg = max(maxDeg, e.1)
                let score = max(e.0 / maxResidualMeters, e.1 / maxResidualDegrees)
                if score > worstScore { worstScore = score; worst = i }
            }
            let within = worstScore <= 1
            if within || usedCount <= 2 {
                return Solution(transform: transform,
                                residuals: err.enumerated().map { AnchorResidual(index: $0.offset, translationMeters: $0.element.0,
                                                                                 rotationDegrees: $0.element.1, used: used[$0.offset]) },
                                maxResidualMeters: maxM, maxResidualDegrees: maxDeg, consistent: within)
            }
            used[worst] = false
        }
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
