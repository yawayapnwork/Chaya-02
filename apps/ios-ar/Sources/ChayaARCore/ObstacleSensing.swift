import Foundation

/// Camera observations -> obstacles placed in the venue frame, tracked over frames, and the reroute rules (docs/navigation.md,
/// "Camera obstacle sensing"). Pure. Mirrors apps/web/lib/obstacle-sensing.ts, obstacle-tracker.ts, obstacle-reroute.ts and
/// obstacle-status.ts; the same cases are unit-tested on both sides (ObstacleSensingTests.swift).
///
/// A detection is a box in the camera image. A box alone says nothing about where the thing is in 3-D, so it is placed
/// only with depth measured in the same ARFrame, inside the box (LiDAR sceneDepth, or ARKit's person-segmentation depth).
/// Nothing here estimates depth from a box's size or position; an observation that cannot be placed is rejected with the
/// reason. Camera-space points: metres, +x right, +y up, -z forward (ARKit camera space).
public enum ObstacleSensing {

    public enum Category: String, Equatable, Sendable { case person = "PERSON", unclassified = "UNCLASSIFIED" }
    public enum DepthSource: String, Equatable, Sendable { case lidar = "LIDAR", arkitPersonDepth = "ARKIT_PERSON_DEPTH" }

    /// One depth measurement: its normalized image position (0..1, origin top-left), the camera-space point the platform's
    /// depth puts there, and the platform's confidence (0..1) when it reports one.
    public struct DepthSample: Equatable, Sendable {
        public var u: Double, v: Double
        public var point: Vec3
        public var confidence: Double?
        public init(u: Double, v: Double, point: Vec3, confidence: Double?) { self.u = u; self.v = v; self.point = point; self.confidence = confidence }
    }

    public struct DepthFrame: Equatable, Sendable {
        public var source: DepthSource
        /// ARFrame.timestamp of the frame the depth belongs to.
        public var frameTime: TimeInterval
        public var samples: [DepthSample]
        public init(source: DepthSource, frameTime: TimeInterval, samples: [DepthSample]) { self.source = source; self.frameTime = frameTime; self.samples = samples }
    }

    public struct Detection: Equatable, Sendable {
        public var category: Category
        public var detector: String
        public var score: Double
        public var frameTime: TimeInterval
        /// Normalized image box, origin top-left.
        public var u0: Double, v0: Double, u1: Double, v1: Double
        public init(category: Category, detector: String, score: Double, frameTime: TimeInterval, u0: Double, v0: Double, u1: Double, v1: Double) {
            self.category = category; self.detector = detector; self.score = score; self.frameTime = frameTime
            self.u0 = u0; self.v0 = v0; self.u1 = u1; self.v1 = v1
        }
    }

    public enum Tracking: Equatable, Sendable { case normal, limited, lost }

    public struct CameraContext: Equatable, Sendable {
        public var frameTime: TimeInterval
        /// Wall-clock time of the frame: what an observation is stamped with for the server's age check.
        public var wallClock: Date
        public var camera: Pose
        public var tracking: Tracking
        public var deviceToVenue: Pose?
        public var floorId: UUID
        public var coordinateFrameId: UUID?
        public var residualMeters: Double?
        public init(frameTime: TimeInterval, wallClock: Date, camera: Pose, tracking: Tracking, deviceToVenue: Pose?, floorId: UUID,
                    coordinateFrameId: UUID?, residualMeters: Double?) {
            self.frameTime = frameTime; self.wallClock = wallClock; self.camera = camera; self.tracking = tracking
            self.deviceToVenue = deviceToVenue; self.floorId = floorId; self.coordinateFrameId = coordinateFrameId; self.residualMeters = residualMeters
        }
    }

    /// An obstacle placed from one frame's evidence. The footprint already includes `uncertaintyMeters`.
    public struct Located: Equatable, Sendable {
        public var floorId: UUID
        public var coordinateFrameId: UUID?
        public var category: Category
        public var observedAt: Date
        public var center: Vec3
        public var minX: Double, minY: Double, maxX: Double, maxY: Double
        public var confidence: Double
        public var uncertaintyMeters: Double
        public var depthSource: DepthSource
        public var depthSamples: Int
        public var distanceMeters: Double
        public var residualMeasured: Bool
    }

    public enum Rejection: String, Equatable, Sendable {
        case lowScore = "LOW_SCORE", notLocalized = "NOT_LOCALIZED", trackingNotNormal = "TRACKING_NOT_NORMAL", noDepth = "NO_DEPTH"
        case frameMismatch = "FRAME_MISMATCH", tooFewDepthSamples = "TOO_FEW_DEPTH_SAMPLES", inconsistentDepth = "INCONSISTENT_DEPTH"
        case outOfRange = "OUT_OF_RANGE", notOnFloor = "NOT_ON_FLOOR", tooUncertain = "TOO_UNCERTAIN"
    }

    public enum Result: Equatable, Sendable {
        case located(Located)
        case rejected(Rejection, String)
    }

    public static let minDetectionScore = 0.5
    public static let minDepthSamples = 8
    public static let minDepthConfidence = 0.5
    public static let frameMatchTolerance: TimeInterval = 0.001
    public static let minRangeMeters = 0.3
    public static let maxRangeMeters = 5.0
    public static let depthInlierMeters = 0.25
    public static let minInlierFraction = 0.5
    public static let maxNearerFraction = 0.1
    public static let boxInset = 0.15
    /// 1-sigma depth error: base + perM2 x range^2. Conservative orders of magnitude per sensor class, not a calibration.
    public static func depthError(_ s: DepthSource) -> (base: Double, perM2: Double) {
        switch s {
        case .lidar: return (0.01, 0.005)
        case .arkitPersonDepth: return (0.03, 0.02)
        }
    }
    public static let unknownResidualMeters = 0.25
    public static let maxUncertaintyMeters = 0.75
    /// The unobserved far side: a stated size allowance, not a measurement.
    public static let hiddenDepthAllowanceMeters = 0.25
    public static let lowestAboveGroundMeters = 1.2
    public static let minHeightMeters = 0.3
    public static let belowGroundToleranceMeters = 0.4
    public static let maxTopAboveGroundMeters = 2.5
    public static let corridorHalfWidthMeters = 0.25
    public static let corridorMaxRangeMeters = 4.0
    public static let corridorBandLowMeters = 0.3
    public static let corridorBandHighMeters = 1.8
    public static let clusterCellMeters = 0.25

    static func quantile(_ sorted: [Double], _ q: Double) -> Double {
        let i = Double(sorted.count - 1) * q
        let lo = Int(i.rounded(.down)), hi = Int(i.rounded(.up))
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (i - Double(lo))
    }

    static func range(_ p: Vec3) -> Double { (p.x * p.x + p.y * p.y + p.z * p.z).squareRoot() }

    /// The floor height under (x, y): the nearest route point (route waypoints lie on the navmesh).
    public static func groundZ(_ ground: [Vec3], x: Double, y: Double) -> Double? {
        ground.min { hypot($0.x - x, $0.y - y) < hypot($1.x - x, $1.y - y) }?.z
    }

    static func contextProblem(_ ctx: CameraContext, _ depth: DepthFrame?, _ detectionTime: TimeInterval?) -> Result? {
        guard ctx.deviceToVenue != nil else { return .rejected(.notLocalized, "the device is not localized in the venue") }
        guard ctx.tracking == .normal else { return .rejected(.trackingNotNormal, "tracking is \(ctx.tracking)") }
        guard let depth, !depth.samples.isEmpty else { return .rejected(.noDepth, "the frame has no depth measurement") }
        let off = max(abs(depth.frameTime - ctx.frameTime), detectionTime.map { abs($0 - ctx.frameTime) } ?? 0)
        if off > frameMatchTolerance { return .rejected(.frameMismatch, "detection, depth and camera pose are \(Int(off * 1000)) ms apart") }
        return nil
    }

    static func place(_ points: [Vec3], distance: Double, inlierFraction: Double, score: Double, category: Category,
                      ctx: CameraContext, depth: DepthFrame, ground: [Vec3]) -> Result {
        let d2v = ctx.deviceToVenue!
        let venue = points.map { VenueFrames.cameraPointToVenue(camera: ctx.camera, deviceToVenue: d2v, $0) }
        let xs = venue.map(\.x).sorted(), ys = venue.map(\.y).sorted(), zs = venue.map(\.z).sorted()
        let center = Vec3(quantile(xs, 0.5), quantile(ys, 0.5), quantile(zs, 0.5))
        guard let g = groundZ(ground, x: center.x, y: center.y) else { return .rejected(.notOnFloor, "no route ground on this floor to compare with") }
        let low = quantile(zs, 0.1) - g, high = quantile(zs, 0.9) - g
        if low < -belowGroundToleranceMeters || low > lowestAboveGroundMeters || high < minHeightMeters || high > maxTopAboveGroundMeters {
            return .rejected(.notOnFloor, String(format: "the measured surface spans %.2f..%.2f m above the route's floor: not something standing "
                                                  + "on it (overhead, or the device -> venue transform is wrong)", low, high))
        }
        let err = depthError(depth.source)
        let sigma = err.base + err.perM2 * distance * distance
        let uncertainty = 2 * sigma + (ctx.residualMeters ?? unknownResidualMeters)
        if uncertainty > maxUncertaintyMeters {
            return .rejected(.tooUncertain, String(format: "position uncertain by %.2f m at %.1f m", uncertainty, distance))
        }
        var minX = quantile(xs, 0.1), maxX = quantile(xs, 0.9), minY = quantile(ys, 0.1), maxY = quantile(ys, 0.9)
        let cam = VenueFrames.cameraPointToVenue(camera: ctx.camera, deviceToVenue: d2v, Vec3(0, 0, 0))
        let dx = center.x - cam.x, dy = center.y - cam.y
        let n = max(hypot(dx, dy), 1e-9)
        let bx = dx / n * hiddenDepthAllowanceMeters, by = dy / n * hiddenDepthAllowanceMeters
        minX += min(0, bx); maxX += max(0, bx); minY += min(0, by); maxY += max(0, by)
        return .located(Located(floorId: ctx.floorId, coordinateFrameId: ctx.coordinateFrameId, category: category, observedAt: ctx.wallClock,
                                center: center, minX: minX - uncertainty, minY: minY - uncertainty, maxX: maxX + uncertainty,
                                maxY: maxY + uncertainty, confidence: min(1, max(0, score * inlierFraction)), uncertaintyMeters: uncertainty,
                                depthSource: depth.source, depthSamples: points.count, distanceMeters: distance,
                                residualMeasured: ctx.residualMeters != nil))
    }

    /// A detector's box placed with the same frame's measured depth. `ground`: the route's points on this floor.
    public static func localize(_ det: Detection, depth: DepthFrame?, ctx: CameraContext, ground: [Vec3]) -> Result {
        guard det.score >= minDetectionScore else { return .rejected(.lowScore, "detector score \(det.score)") }
        if let problem = contextProblem(ctx, depth, det.frameTime) { return problem }
        let depth = depth!
        let w = det.u1 - det.u0, h = det.v1 - det.v0
        let u0 = det.u0 + w * boxInset, u1 = det.u1 - w * boxInset, v0 = det.v0 + h * boxInset, v1 = det.v1 - h * boxInset
        let inside = depth.samples.filter { s in
            s.u >= u0 && s.u <= u1 && s.v >= v0 && s.v <= v1 && (s.confidence.map { $0 >= minDepthConfidence } ?? true)
                && s.point.z < 0 && range(s.point).isFinite
        }
        guard inside.count >= minDepthSamples else {
            return .rejected(.tooFewDepthSamples, "\(inside.count) confident depth sample(s) inside the box (need \(minDepthSamples))")
        }
        let median = quantile(inside.map { range($0.point) }.sorted(), 0.5)
        let tol = max(depthInlierMeters, 0.1 * median)
        let inliers = inside.filter { abs(range($0.point) - median) <= tol }
        let fraction = Double(inliers.count) / Double(inside.count)
        guard fraction >= minInlierFraction, inliers.count >= minDepthSamples else {
            return .rejected(.inconsistentDepth, "only \(Int(fraction * 100))% of the depth inside the box is one surface")
        }
        let nearer = Double(inside.filter { range($0.point) < median - tol }.count) / Double(inside.count)
        guard nearer < maxNearerFraction else {
            return .rejected(.inconsistentDepth, "\(Int(nearer * 100))% of the box is a nearer surface than most of its depth")
        }
        guard median >= minRangeMeters, median <= maxRangeMeters else {
            return .rejected(.outOfRange, String(format: "%.2f m away (placed only from %.1f to %.1f m)", median, minRangeMeters, maxRangeMeters))
        }
        return place(inliers.map(\.point), distance: median, inlierFraction: fraction, score: det.score, category: det.category,
                     ctx: ctx, depth: depth, ground: ground)
    }

    static func distanceToPath(_ path: [Vec3], x: Double, y: Double) -> Double {
        if path.count == 1 { return hypot(path[0].x - x, path[0].y - y) }
        var best = Double.infinity
        for i in 1..<path.count {
            let a = path[i - 1], b = path[i]
            let dx = b.x - a.x, dy = b.y - a.y, len2 = dx * dx + dy * dy
            let t = len2 == 0 ? 0 : max(0, min(1, ((x - a.x) * dx + (y - a.y) * dy) / len2))
            best = min(best, hypot(a.x + t * dx - x, a.y + t * dy - y))
        }
        return best
    }

    /// Measured surfaces standing in the route corridor ahead (UNCLASSIFIED), one per connected cluster.
    public static func corridorOccupancy(_ depth: DepthFrame?, ctx: CameraContext, pathAhead: [Vec3]) -> [Located] {
        guard contextProblem(ctx, depth, nil) == nil, !pathAhead.isEmpty, let depth, let d2v = ctx.deviceToVenue else { return [] }
        struct Key: Hashable { let x: Int, y: Int }
        var cells: [Key: (points: [Vec3], ranges: [Double])] = [:]
        for s in depth.samples {
            if let c = s.confidence, c < minDepthConfidence { continue }
            let r = range(s.point)
            guard s.point.z < 0, r >= minRangeMeters, r <= corridorMaxRangeMeters else { continue }
            let p = VenueFrames.cameraPointToVenue(camera: ctx.camera, deviceToVenue: d2v, s.point)
            guard let g = groundZ(pathAhead, x: p.x, y: p.y) else { continue }
            let z = p.z - g
            guard z >= corridorBandLowMeters, z <= corridorBandHighMeters,
                  distanceToPath(pathAhead, x: p.x, y: p.y) <= corridorHalfWidthMeters else { continue }
            let k = Key(x: Int((p.x / clusterCellMeters).rounded(.down)), y: Int((p.y / clusterCellMeters).rounded(.down)))
            cells[k, default: ([], [])].points.append(s.point)
            cells[k, default: ([], [])].ranges.append(r)
        }
        var seen = Set<Key>(), out: [Located] = []
        for key in cells.keys.sorted(by: { ($0.x, $0.y) < ($1.x, $1.y) }) where !seen.contains(key) {
            var stack = [key], points: [Vec3] = [], ranges: [Double] = []
            seen.insert(key)
            while let k = stack.popLast() {
                points += cells[k]!.points
                ranges += cells[k]!.ranges
                for ix in -1...1 { for iy in -1...1 {
                    let n = Key(x: k.x + ix, y: k.y + iy)
                    if cells[n] != nil && !seen.contains(n) { seen.insert(n); stack.append(n) }
                } }
            }
            guard points.count >= minDepthSamples else { continue }
            if case .located(let o) = place(points, distance: quantile(ranges.sorted(), 0.5), inlierFraction: 1,
                                            score: min(1, Double(points.count) / Double(3 * minDepthSamples)), category: .unclassified,
                                            ctx: ctx, depth: depth, ground: pathAhead) {
                out.append(o)
            }
        }
        return out
    }

    public enum Visibility: Equatable, Sendable { case clear, occupied, occluded, notInView }
    public static let visibilityMarginMeters = 0.5

    /// `depthAlong`: the measured range along a camera-space direction, nil outside the depth image or without confident depth.
    public static func visibility(at venuePoint: Vec3, ctx: CameraContext, depthAlong: (Vec3) -> Double?) -> Visibility {
        guard let d2v = ctx.deviceToVenue, ctx.tracking == .normal else { return .notInView }
        let world = VenueFrames.venuePointToWorld(deviceToVenue: d2v, venuePoint)
        let p = VenueFrames.translation(AnchorMath.compose(AnchorMath.invert(ctx.camera), VenueFrames.point(world)))
        guard p.z < 0 else { return .notInView }
        let expected = range(p)
        guard expected <= maxRangeMeters, let measured = depthAlong(p) else { return .notInView }
        if measured > expected + visibilityMarginMeters { return .clear }
        if measured < expected - visibilityMarginMeters { return .occluded }
        return .occupied
    }

    /// Pinhole camera intrinsics in pixels of an image `width` x `height` (ARCamera.intrinsics, imageResolution).
    public struct Intrinsics: Equatable, Sendable {
        public var fx: Double, fy: Double, cx: Double, cy: Double, width: Double, height: Double
        public init(fx: Double, fy: Double, cx: Double, cy: Double, width: Double, height: Double) {
            self.fx = fx; self.fy = fy; self.cx = cx; self.cy = cy; self.width = width; self.height = height
        }

        /// Camera-space point (+y up, -z forward) from a normalized image position (origin top-left) and a depth along -z.
        public func unproject(u: Double, v: Double, depth: Double) -> Vec3? {
            guard depth > 0, depth.isFinite else { return nil }
            return Vec3((u * width - cx) / fx * depth, -((v * height - cy) / fy) * depth, -depth)
        }

        public func project(_ p: Vec3) -> (u: Double, v: Double)? {
            guard p.z < 0 else { return nil }
            let d = -p.z
            let u = (p.x / d * fx + cx) / width, v = (-p.y / d * fy + cy) / height
            return (0...1).contains(u) && (0...1).contains(v) ? (u, v) : nil
        }
    }

    /// Bounds how often sensing runs: one run at a time, at most every `interval`, doubling (to `maxInterval`) while the
    /// 90th-percentile latency of recent runs exceeds `budget`, halving back once under half of it.
    public struct Scheduler: Equatable, Sendable {
        public let minInterval: TimeInterval, maxInterval: TimeInterval, budget: TimeInterval
        public private(set) var interval: TimeInterval
        private var lastStart = -TimeInterval.infinity
        public private(set) var running = false
        private var latencies: [TimeInterval] = []
        public private(set) var runs = 0

        public init(minInterval: TimeInterval, maxInterval: TimeInterval, budget: TimeInterval) {
            self.minInterval = minInterval; self.maxInterval = maxInterval; self.budget = budget; self.interval = minInterval
        }

        public mutating func tryStart(at now: TimeInterval) -> Bool {
            guard now - lastStart >= interval, !running else { return false }
            running = true
            lastStart = now
            return true
        }

        public mutating func finish(latency: TimeInterval) {
            running = false
            runs += 1
            latencies.append(latency)
            if latencies.count > 30 { latencies.removeFirst() }
            guard latencies.count >= 5, let p90 = p90 else { return }
            if p90 > budget { interval = min(maxInterval, interval * 2) } else if p90 < budget / 2 { interval = max(minInterval, interval / 2) }
        }

        public var p90: TimeInterval? { latencies.isEmpty ? nil : ObstacleSensing.quantile(latencies.sorted(), 0.9) }
        public var lowDetail: Bool { interval > minInterval }
    }
}

// MARK: - Tracking

/// Obstacles over time. Mirrors apps/web/lib/obstacle-tracker.ts.
public struct ObstacleTracker: Equatable, Sendable {
    public static let associationMeters = 0.75
    public static let confirmHits = 2
    public static let movingSpeed = 0.4
    public static let clearAfterMisses = 3
    public static func ttl(_ c: ObstacleSensing.Category) -> TimeInterval { c == .person ? 10 : 30 }

    public enum Status: Equatable, Sendable { case tentative, moving, stationary }

    public struct Track: Equatable, Sendable {
        public var id: Int
        public var category: ObstacleSensing.Category
        public var last: ObstacleSensing.Located
        public var lastSeen: Date
        public var hits: Int
        public var clearMisses: Int
        public var velocity: (Double, Double)?
        public var status: Status

        public static func == (a: Track, b: Track) -> Bool {
            a.id == b.id && a.category == b.category && a.last == b.last && a.lastSeen == b.lastSeen && a.hits == b.hits
                && a.clearMisses == b.clearMisses && a.velocity?.0 == b.velocity?.0 && a.velocity?.1 == b.velocity?.1 && a.status == b.status
        }
    }

    public private(set) var tracks: [Track] = []
    private var nextId = 1
    private var floorId: UUID?
    private var coordinateFrameId: UUID?

    public init() {}

    static func compatible(_ a: ObstacleSensing.Category, _ b: ObstacleSensing.Category) -> Bool { a == b || a == .unclassified || b == .unclassified }
    static func dist(_ a: Vec3, _ b: Vec3) -> Double { hypot(a.x - b.x, a.y - b.y) }

    static func status(_ t: Track) -> Status {
        if t.hits < confirmHits { return .tentative }
        let speed = t.velocity.map { hypot($0.0, $0.1) } ?? 0
        return t.category == .person && speed > movingSpeed ? .moving : .stationary
    }

    public mutating func update(_ observations: [ObstacleSensing.Located], now: Date, floorId: UUID, coordinateFrameId: UUID?,
                                visibility: (Vec3) -> ObstacleSensing.Visibility) {
        if self.floorId != floorId || self.coordinateFrameId != coordinateFrameId { tracks = [] }
        self.floorId = floorId
        self.coordinateFrameId = coordinateFrameId
        var matched = Set<Int>()
        var updated: [Int: Track] = [:]
        var order: [Int] = []
        for o in observations.sorted(by: { $0.confidence > $1.confidence }) {
            guard o.floorId == floorId, o.coordinateFrameId == coordinateFrameId else { continue }
            let candidate = tracks.filter { !matched.contains($0.id) && Self.compatible($0.category, o.category)
                && Self.dist($0.last.center, o.center) <= Self.associationMeters + $0.last.uncertaintyMeters + o.uncertaintyMeters }
                .min { Self.dist($0.last.center, o.center) < Self.dist($1.last.center, o.center) }
            guard let best = candidate else {
                if let dup = order.compactMap({ updated[$0] }).first(where: { $0.lastSeen == now && Self.compatible($0.category, o.category)
                    && Self.dist($0.last.center, o.center) <= Self.associationMeters }) {
                    if o.category == .person {
                        var t = dup
                        t.category = .person
                        t.last = o
                        t.status = Self.status(t)
                        updated[t.id] = t
                    }
                    continue
                }
                let t = Track(id: nextId, category: o.category, last: o, lastSeen: now, hits: 1, clearMisses: 0, velocity: nil, status: .tentative)
                nextId += 1
                updated[t.id] = t
                order.append(t.id)
                continue
            }
            matched.insert(best.id)
            let dt = now.timeIntervalSince(best.lastSeen)
            var velocity = best.velocity
            if dt >= 0.2 {
                let v = ((o.center.x - best.last.center.x) / dt, (o.center.y - best.last.center.y) / dt)
                velocity = velocity.map { ($0.0 + 0.5 * (v.0 - $0.0), $0.1 + 0.5 * (v.1 - $0.1)) } ?? v
            }
            var t = best
            t.category = best.category == .person || o.category == .person ? .person : .unclassified
            t.last = o
            t.lastSeen = now
            t.hits += 1
            t.clearMisses = 0
            t.velocity = velocity
            t.status = Self.status(t)
            updated[t.id] = t
            order.append(t.id)
        }
        var out: [Track] = []
        for t in tracks {
            if let u = updated.removeValue(forKey: t.id) { out.append(u); continue }
            guard now.timeIntervalSince(t.lastSeen) <= Self.ttl(t.category) else { continue }
            var kept = t
            if visibility(t.last.center) == .clear { kept.clearMisses += 1 }
            guard kept.clearMisses < Self.clearAfterMisses else { continue }
            out.append(kept)
        }
        out += order.compactMap { updated[$0] }
        tracks = out
    }

    public mutating func expire(now: Date) {
        tracks.removeAll { now.timeIntervalSince($0.lastSeen) > Self.ttl($0.category) }
    }

    public var blocking: [ObstacleReroute.Observation] {
        tracks.filter { $0.status == .stationary }.map {
            ObstacleReroute.Observation(floorId: $0.last.floorId, minX: $0.last.minX, minY: $0.last.minY, maxX: $0.last.maxX, maxY: $0.last.maxY,
                                        observedAt: $0.lastSeen, coordinateFrameId: $0.last.coordinateFrameId, category: $0.category,
                                        uncertaintyMeters: $0.last.uncertaintyMeters)
        }
    }

    public var movingPeople: Int { tracks.filter { $0.status == .moving }.count }
}

// MARK: - Rerouting

/// When an observed obstacle should change the route. Mirrors apps/web/lib/obstacle-reroute.ts.
public enum ObstacleReroute {
    public static let maxAge: TimeInterval = 120
    public static let minInterval: TimeInterval = 5
    public static let significantShiftMeters = 0.3
    public static let pathBufferMeters = 0.35
    public static let clearHold: TimeInterval = 5

    public struct Observation: Equatable, Sendable {
        public var floorId: UUID
        public var minX: Double, minY: Double, maxX: Double, maxY: Double
        public var observedAt: Date
        public var coordinateFrameId: UUID?
        public var category: ObstacleSensing.Category?
        public var uncertaintyMeters: Double?
        public init(floorId: UUID, minX: Double, minY: Double, maxX: Double, maxY: Double, observedAt: Date, coordinateFrameId: UUID?,
                    category: ObstacleSensing.Category? = nil, uncertaintyMeters: Double? = nil) {
            self.floorId = floorId; self.minX = minX; self.minY = minY; self.maxX = maxX; self.maxY = maxY
            self.observedAt = observedAt; self.coordinateFrameId = coordinateFrameId; self.category = category; self.uncertaintyMeters = uncertaintyMeters
        }

        public var blockedRegion: BlockedRegion {
            BlockedRegion(floorId: floorId, minX: minX, minY: minY, maxX: maxX, maxY: maxY, observedAt: observedAt, coordinateFrameId: coordinateFrameId)
        }
    }

    public enum Reason: String, Equatable, Sendable {
        case obstacleOnPath = "OBSTACLE_ON_PATH", obstacleCleared = "OBSTACLE_CLEARED"
        case nothingNewOnPath = "NOTHING_NEW_ON_PATH", tooSoon = "TOO_SOON", noObstacleSource = "NO_OBSTACLE_SOURCE", noAlternative = "NO_ALTERNATIVE"
    }

    public struct Decision: Equatable, Sendable {
        public var reroute: Bool
        public var reason: Reason
        public var obstacles: [Observation]
    }

    public static func fresh(_ o: [Observation], now: Date) -> [Observation] {
        o.filter { now.timeIntervalSince($0.observedAt) <= maxAge && $0.observedAt.timeIntervalSince(now) <= 10 }
    }

    public static func same(_ a: Observation, _ b: Observation) -> Bool {
        a.floorId == b.floorId && abs(a.minX - b.minX) <= significantShiftMeters && abs(a.maxX - b.maxX) <= significantShiftMeters
            && abs(a.minY - b.minY) <= significantShiftMeters && abs(a.maxY - b.maxY) <= significantShiftMeters
    }

    static func segmentTouchesBox(_ a: Vec3, _ b: Vec3, _ o: Observation, grow: Double) -> Bool {
        let minX = o.minX - grow, minY = o.minY - grow, maxX = o.maxX + grow, maxY = o.maxY + grow
        let dx = b.x - a.x, dy = b.y - a.y
        let p = [-dx, dx, -dy, dy], q = [a.x - minX, maxX - a.x, a.y - minY, maxY - a.y]
        var t0 = 0.0, t1 = 1.0
        for i in 0..<4 {
            if p[i] == 0 {
                if q[i] < 0 { return false }
            } else {
                let t = q[i] / p[i]
                if p[i] < 0 { t0 = max(t0, t) } else { t1 = min(t1, t) }
            }
        }
        return t0 <= t1
    }

    public static func touchesPath(_ path: [Vec3], _ o: Observation) -> Bool {
        if path.count == 1 { return segmentTouchesBox(path[0], path[0], o, grow: pathBufferMeters) }
        return (1..<max(1, path.count)).contains { segmentTouchesBox(path[$0 - 1], path[$0], o, grow: pathBufferMeters) }
    }

    /// `observed`: nil when the device has no obstacle source. `remainingPath` starts at the traveller.
    public static func decide(floorId: UUID, remainingPath: [Vec3], routedAround: [Observation], observed: [Observation]?, now: Date,
                              lastReroute: Date?, failedAround: [Observation] = [], clearedSince: Date? = nil) -> Decision {
        guard let observed else { return Decision(reroute: false, reason: .noObstacleSource, obstacles: []) }
        let at = remainingPath.first
        let freshNow = fresh(observed, now: now).filter { o in
            guard let at else { return true }
            return !(at.x >= o.minX && at.x <= o.maxX && at.y >= o.minY && at.y <= o.maxY)
        }
        let tooSoon = lastReroute.map { now.timeIntervalSince($0) < minInterval } ?? false
        let ahead = freshNow.filter { o in o.floorId == floorId && touchesPath(remainingPath, o) && !routedAround.contains { same($0, o) } }
        let onPath = ahead.filter { o in !failedAround.contains { same($0, o) } }
        if !onPath.isEmpty { return Decision(reroute: !tooSoon, reason: tooSoon ? .tooSoon : .obstacleOnPath, obstacles: freshNow) }
        if !ahead.isEmpty { return Decision(reroute: false, reason: .noAlternative, obstacles: freshNow) }
        if !routedAround.isEmpty, let since = clearedSince, now.timeIntervalSince(since) >= clearHold {
            return Decision(reroute: !tooSoon, reason: tooSoon ? .tooSoon : .obstacleCleared, obstacles: freshNow)
        }
        return Decision(reroute: false, reason: .nothingNewOnPath, obstacles: freshNow)
    }

    public static func clearedSince(routedAround: [Observation], observed: [Observation]?, previous: Date?, now: Date) -> Date? {
        guard !routedAround.isEmpty, let observed else { return nil }
        let f = fresh(observed, now: now)
        return routedAround.contains { r in f.contains { same(r, $0) } } ? nil : previous ?? now
    }
}

// MARK: - What the UI says

/// The overlay's obstacle lines. Mirrors apps/web/lib/obstacle-status.ts: positions are estimates with their uncertainty,
/// a moving person is a heads-up, and paused sensing never implies the way is clear.
public enum ObstacleStatus {
    public enum Sensing: Equatable, Sendable { case unavailable(String), live, paused(String) }
    public enum Outcome: Equatable, Sendable { case none, rerouting, rerouted(Int), restored, noAlternative(stepFree: Bool), failed(String) }

    public struct Message: Equatable, Sendable, Identifiable {
        public var id: String { "\(kind)-\(text)" }
        public var warning: Bool
        public var kind: String
        public var text: String
    }

    public static func messages(sensing: Sensing, onPath: [(ObstacleReroute.Observation, Double?)], movingNearby: Int, outcome: Outcome) -> [Message] {
        var out: [Message] = []
        switch sensing {
        case .unavailable(let why):
            out.append(Message(warning: false, kind: "unavailable",
                               text: "Obstacle detection is not available on this device (\(why)): the route does not adapt to people or objects in the way."))
        case .paused(let why):
            out.append(Message(warning: true, kind: "paused", text: "Obstacle detection paused (\(why)). Watch for people and objects yourself."))
        case .live:
            break
        }
        for (o, ahead) in onPath.prefix(2) {
            let what = o.category == .person ? "A person" : "Something"
            let whereText = ahead.map { "about \(Int(max(0, $0).rounded())) m ahead" } ?? "on the route"
            let pm = o.uncertaintyMeters.map { String(format: " (estimated position, ±%.1f m)", $0) } ?? " (estimated position)"
            out.append(Message(warning: true, kind: "obstruction", text: "\(what) is in the way \(whereText)\(pm)."))
        }
        if onPath.count > 2 { out.append(Message(warning: true, kind: "obstruction", text: "\(onPath.count - 2) more in the way.")) }
        if movingNearby > 0 {
            out.append(Message(warning: false, kind: "moving",
                               text: "\(movingNearby == 1 ? "A person is" : "\(movingNearby) people are") moving near the route. The route is not changed for people walking by."))
        }
        switch outcome {
        case .none: break
        case .rerouting: out.append(Message(warning: false, kind: "reroute", text: "Finding a way around…"))
        case .rerouted(let n): out.append(Message(warning: false, kind: "reroute", text: "Route changed to avoid \(n == 1 ? "an obstruction" : "\(n) obstructions")."))
        case .restored: out.append(Message(warning: false, kind: "reroute", text: "The way is clear again: back on the usual route."))
        case .noAlternative(let stepFree):
            out.append(Message(warning: true, kind: "reroute", text: stepFree
                ? "No step-free way around the obstruction was found, so the route is unchanged. Wait for it to clear, or ask staff for help."
                : "No way around the obstruction was found, so the route is unchanged. Wait for it to clear or pass with care."))
        case .failed(let detail):
            out.append(Message(warning: true, kind: "reroute", text: "The route could not be recalculated (\(detail)); the current route is kept."))
        }
        return out
    }
}
