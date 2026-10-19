import XCTest
@testable import ChayaARCore

/// Camera obstacle sensing, pure (mirrors apps/web/lib/obstacle-*.test.ts). The depth is ray-cast from synthetic boxes
/// through the real camera -> ARKit world -> venue chain, and the "detector" boxes are the exact image boxes of those
/// objects: these tests cover spatial localization, tracking and the reroute rules. They prove nothing about Vision's
/// detection accuracy, LiDAR or ARKit person depth on a device (docs/navigation.md, "Camera obstacle sensing").
final class ObstacleSensingTests: XCTestCase {

    typealias S = ObstacleSensing
    struct Box { var min: Vec3; var max: Vec3 }

    let K = S.Intrinsics(fx: 500, fy: 500, cx: 320, cy: 240, width: 640, height: 480)
    /// ARKit world (+Y up) -> canonical (+Z up), no offset: canonical (x, y, z) = world (x, -z, y).
    let d2v = Pose(x: 0, y: 0, z: 0, qx: 0.5.squareRoot(), qy: 0, qz: 0, qw: 0.5.squareRoot())
    /// 1.5 m above the floor at the venue origin, looking along canonical +Y.
    let camera = Pose(x: 0, y: 1.5, z: 0)
    let route = [Vec3(0, 0, 0), Vec3(0, 10, 0)]
    let floor = UUID(), frame = UUID()
    let person = Box(min: Vec3(-0.25, 3.0, 0), max: Vec3(0.25, 3.3, 1.75))
    let t0 = Date(timeIntervalSince1970: 1_700_000_000)

    func ctx(camera: Pose? = nil, tracking: S.Tracking = .normal, d2v: Pose?? = .none, residual: Double? = 0.05, time: TimeInterval = 1) -> S.CameraContext {
        let transform: Pose? = { if case .some(let v) = d2v { return v } else { return self.d2v } }()
        return S.CameraContext(frameTime: time, wallClock: t0, camera: camera ?? self.camera, tracking: tracking, deviceToVenue: transform,
                               floorId: floor, coordinateFrameId: frame, residualMeters: residual)
    }

    func hit(_ o: Vec3, _ d: Vec3, _ b: Box) -> Double? {
        let oa = [o.x, o.y, o.z], da = [d.x, d.y, d.z], lo = [b.min.x, b.min.y, b.min.z], hi = [b.max.x, b.max.y, b.max.z]
        var t0 = -Double.infinity, t1 = Double.infinity
        for i in 0..<3 {
            if da[i] == 0 { if oa[i] < lo[i] || oa[i] > hi[i] { return nil }; continue }
            let a = (lo[i] - oa[i]) / da[i], c = (hi[i] - oa[i]) / da[i]
            t0 = max(t0, min(a, c)); t1 = min(t1, max(a, c))
        }
        return t1 >= t0 && t0 > 0 ? t0 : nil
    }

    func depth(_ boxes: [Box], cols: Int = 64, rows: Int = 48, camera: Pose? = nil, time: TimeInterval = 1, confidence: Double? = 1) -> S.DepthFrame {
        let cam = camera ?? self.camera
        let o = VenueFrames.cameraPointToVenue(camera: cam, deviceToVenue: d2v, Vec3(0, 0, 0))
        var samples: [S.DepthSample] = []
        for r in 0..<rows { for c in 0..<cols {
            let u = (Double(c) + 0.5) / Double(cols), v = (Double(r) + 0.5) / Double(rows)
            let unit = K.unproject(u: u, v: v, depth: 1)!
            let q = VenueFrames.cameraPointToVenue(camera: cam, deviceToVenue: d2v, unit)
            let d = Vec3(q.x - o.x, q.y - o.y, q.z - o.z)
            var t: Double? = d.z < 0 ? -o.z / d.z : nil
            for b in boxes { if let h = hit(o, d, b), t == nil || h < t! { t = h } }
            guard let t, t <= 8 else { continue }
            samples.append(S.DepthSample(u: u, v: v, point: Vec3(unit.x * t, unit.y * t, -t), confidence: confidence))
        } }
        return S.DepthFrame(source: .lidar, frameTime: time, samples: samples)
    }

    func detection(_ b: Box, score: Double = 0.9, time: TimeInterval = 1, category: S.Category = .person) -> S.Detection {
        var us: [Double] = [], vs: [Double] = []
        for x in [b.min.x, b.max.x] { for y in [b.min.y, b.max.y] { for z in [b.min.z, b.max.z] {
            let world = Vec3(x, z, -y) // canonical -> ARKit world
            let p = Vec3(world.x - camera.x, world.y - camera.y, world.z - camera.z)
            guard p.z < 0 else { continue }
            // a detector's box is clipped at the image border
            us.append(min(1, max(0, (p.x / -p.z * K.fx + K.cx) / K.width)))
            vs.append(min(1, max(0, (-p.y / -p.z * K.fy + K.cy) / K.height)))
        } } }
        return S.Detection(category: category, detector: "test", score: score, frameTime: time, u0: us.min()!, v0: vs.min()!, u1: us.max()!, v1: vs.max()!)
    }

    func reason(_ r: S.Result) -> String {
        if case .rejected(let why, _) = r { return why.rawValue }
        return "OK"
    }

    // MARK: - spatial localization

    func testADetectionIsPlacedFromTheDepthMeasuredInsideItsBox() throws {
        guard case .located(let o) = S.localize(detection(person), depth: depth([person]), ctx: ctx(), ground: route) else {
            return XCTFail("not placed")
        }
        XCTAssertEqual(o.center.x, 0, accuracy: 0.05)
        XCTAssertEqual(o.center.y, 3.0, accuracy: 0.05)
        XCTAssertGreaterThan(o.maxY, 3.25, "extended behind the visible surface by the stated allowance")
        XCTAssertEqual(o.uncertaintyMeters, 2 * (0.01 + 0.005 * o.distanceMeters * o.distanceMeters) + 0.05, accuracy: 1e-9)
        XCTAssertEqual(o.observedAt, t0)
        guard case .located(let unknown) = S.localize(detection(person), depth: depth([person]), ctx: ctx(residual: nil), ground: route) else {
            return XCTFail("not placed")
        }
        XCTAssertFalse(unknown.residualMeasured)
        XCTAssertGreaterThanOrEqual(unknown.uncertaintyMeters, S.unknownResidualMeters)
    }

    func testABoxWithoutDepthIsNeverPlaced() {
        let det = detection(person)
        XCTAssertEqual(reason(S.localize(det, depth: nil, ctx: ctx(), ground: route)), "NO_DEPTH")
        XCTAssertEqual(reason(S.localize(det, depth: depth([person], cols: 6, rows: 4), ctx: ctx(), ground: route)), "TOO_FEW_DEPTH_SAMPLES")
        XCTAssertEqual(reason(S.localize(det, depth: depth([person], confidence: 0.2), ctx: ctx(), ground: route)), "TOO_FEW_DEPTH_SAMPLES")
        let pole = Box(min: Vec3(-0.05, 3.0, 0), max: Vec3(0.05, 3.1, 1.8)), wall = Box(min: Vec3(-3, 5, 0), max: Vec3(3, 5.1, 3))
        let wide = detection(Box(min: Vec3(-0.35, 3.0, 0), max: Vec3(0.35, 3.1, 1.8)))
        XCTAssertEqual(reason(S.localize(wide, depth: depth([pole, wall], cols: 160, rows: 120), ctx: ctx(), ground: route)), "INCONSISTENT_DEPTH")
        XCTAssertEqual(reason(S.localize(detection(person, score: 0.3), depth: depth([person]), ctx: ctx(), ground: route)), "LOW_SCORE")
    }

    func testNothingIsPlacedWhileTrackingIsLostOrFromAnotherFramesDepth() {
        let det = detection(person)
        XCTAssertEqual(reason(S.localize(det, depth: depth([person]), ctx: ctx(tracking: .limited), ground: route)), "TRACKING_NOT_NORMAL")
        XCTAssertEqual(reason(S.localize(det, depth: depth([person]), ctx: ctx(tracking: .lost), ground: route)), "TRACKING_NOT_NORMAL")
        XCTAssertEqual(reason(S.localize(det, depth: depth([person]), ctx: ctx(d2v: .some(nil)), ground: route)), "NOT_LOCALIZED")
        XCTAssertEqual(reason(S.localize(det, depth: depth([person], time: 1.033), ctx: ctx(), ground: route)), "FRAME_MISMATCH")
    }

    func testAWrongTransformPutsTheSurfaceOffTheFloorAndIsRejected() {
        var lifted = d2v
        lifted.z = 2
        XCTAssertEqual(reason(S.localize(detection(person), depth: depth([person]), ctx: ctx(d2v: .some(lifted)), ground: route)), "NOT_ON_FLOOR")
        XCTAssertEqual(reason(S.localize(detection(person), depth: depth([person]), ctx: ctx(d2v: .some(.identity)), ground: route)), "NOT_ON_FLOOR")
    }

    func testCorridorOccupancyFindsWhatStandsInTheRouteNotWallsFloorOrOverhead() {
        let cart = Box(min: Vec3(-0.3, 2.5, 0), max: Vec3(0.3, 3.0, 1.0))
        let wall = Box(min: Vec3(0.6, 1, 0), max: Vec3(0.8, 6, 2.5)), beam = Box(min: Vec3(-1, 1.8, 2.0), max: Vec3(1, 2.0, 2.3))
        let found = S.corridorOccupancy(depth([cart, wall, beam]), ctx: ctx(), pathAhead: route)
        XCTAssertEqual(found.count, 1)
        XCTAssertEqual(found.first?.category, .unclassified)
        XCTAssertEqual(found.first?.center.y ?? 0, 2.5, accuracy: 0.1)
        XCTAssertTrue(S.corridorOccupancy(depth([wall, beam]), ctx: ctx(), pathAhead: route).isEmpty)
        XCTAssertTrue(S.corridorOccupancy(depth([cart]), ctx: ctx(tracking: .lost), pathAhead: route).isEmpty)
    }

    func testIntrinsicsRoundTrip() throws {
        let p = try XCTUnwrap(K.unproject(u: 0.3, v: 0.7, depth: 2.5))
        XCTAssertEqual(p.z, -2.5)
        let back = try XCTUnwrap(K.project(p))
        XCTAssertEqual(back.u, 0.3, accuracy: 1e-9)
        XCTAssertEqual(back.v, 0.7, accuracy: 1e-9)
        XCTAssertLessThan(p.y, 0, "below the centre: v grows downward, camera +y is up")
    }

    // MARK: - tracking

    func located(_ x: Double, _ y: Double, _ c: S.Category = .unclassified) -> S.Located {
        S.Located(floorId: floor, coordinateFrameId: frame, category: c, observedAt: t0, center: Vec3(x, y, 0.9), minX: x - 0.4, minY: y - 0.4,
                  maxX: x + 0.4, maxY: y + 0.4, confidence: 0.8, uncertaintyMeters: 0.1, depthSource: .lidar, depthSamples: 40,
                  distanceMeters: 3, residualMeasured: true)
    }

    func testTrackingConfirmsMovesExpiresAndHandlesOcclusion() {
        var t = ObstacleTracker()
        let at = { (s: Double) in self.t0.addingTimeInterval(s) }
        t.update([located(0, 3)], now: at(0), floorId: floor, coordinateFrameId: frame) { _ in .notInView }
        XCTAssertTrue(t.blocking.isEmpty, "one sighting is tentative")
        t.update([located(0.05, 3)], now: at(0.25), floorId: floor, coordinateFrameId: frame) { _ in .notInView }
        XCTAssertEqual(t.blocking.count, 1)
        XCTAssertEqual(t.tracks.count, 1)
        for i in 1...4 { t.update([], now: at(0.25 + Double(i) * 0.25), floorId: floor, coordinateFrameId: frame) { _ in .occluded } }
        XCTAssertEqual(t.blocking.count, 1, "hidden behind something: kept")
        for i in 1...3 { t.update([], now: at(2 + Double(i) * 0.25), floorId: floor, coordinateFrameId: frame) { _ in .clear } }
        XCTAssertTrue(t.tracks.isEmpty, "measured clear three times: gone")

        var p = ObstacleTracker()
        for (i, x) in [-1.0, -0.5, 0].enumerated() {
            p.update([located(x, 3, .person)], now: at(Double(i) * 0.5), floorId: floor, coordinateFrameId: frame) { _ in .notInView }
        }
        XCTAssertTrue(p.blocking.isEmpty, "walking by at 1 m/s")
        XCTAssertEqual(p.movingPeople, 1)
        p.update([], now: at(1 + ObstacleTracker.ttl(.person) + 0.1), floorId: floor, coordinateFrameId: frame) { _ in .notInView }
        XCTAssertTrue(p.tracks.isEmpty, "a person is never a permanent obstacle")

        var two = ObstacleTracker()
        for s in [0.0, 0.25] {
            two.update([located(0, 3, .person), located(0.1, 3.05)], now: at(s), floorId: floor, coordinateFrameId: frame) { _ in .notInView }
        }
        XCTAssertEqual(two.tracks.count, 1, "a person and a depth cluster at the same place are one obstacle")
        XCTAssertEqual(two.blocking.first?.category, .person)
    }

    // MARK: - rerouting

    func obstacle(_ minX: Double, _ minY: Double, _ maxX: Double, _ maxY: Double) -> ObstacleReroute.Observation {
        ObstacleReroute.Observation(floorId: floor, minX: minX, minY: minY, maxX: maxX, maxY: maxY, observedAt: t0, coordinateFrameId: frame)
    }

    func testRerouteRulesMatchTheWebClient() {
        let path = [Vec3(0, 0, 0), Vec3(10, 0, 0)]
        let cart = obstacle(4, -0.2, 5, 0.2)
        let now = t0.addingTimeInterval(1)
        XCTAssertEqual(ObstacleReroute.decide(floorId: floor, remainingPath: path, routedAround: [], observed: nil, now: now, lastReroute: nil).reason,
                       .noObstacleSource)
        XCTAssertEqual(ObstacleReroute.decide(floorId: floor, remainingPath: path, routedAround: [], observed: [cart], now: now, lastReroute: nil).reason,
                       .obstacleOnPath)
        XCTAssertEqual(ObstacleReroute.decide(floorId: floor, remainingPath: path, routedAround: [], observed: [obstacle(3, 4, 4, 5)], now: now,
                                              lastReroute: nil).reason, .nothingNewOnPath, "outside the route")
        XCTAssertEqual(ObstacleReroute.decide(floorId: floor, remainingPath: path, routedAround: [], observed: [cart], now: now, lastReroute: nil,
                                              failedAround: [cart]).reason, .noAlternative, "no way around: not asked again")
        XCTAssertEqual(ObstacleReroute.decide(floorId: floor, remainingPath: path, routedAround: [], observed: [obstacle(-0.5, -0.5, 0.5, 0.5)],
                                              now: now, lastReroute: nil).obstacles, [], "a box around the traveller is never sent")
        let since = ObstacleReroute.clearedSince(routedAround: [cart], observed: [], previous: nil, now: now)
        XCTAssertEqual(since, now)
        XCTAssertFalse(ObstacleReroute.decide(floorId: floor, remainingPath: path, routedAround: [cart], observed: [], now: now.addingTimeInterval(4),
                                              lastReroute: nil, clearedSince: since).reroute)
        XCTAssertEqual(ObstacleReroute.decide(floorId: floor, remainingPath: path, routedAround: [cart], observed: [], now: now.addingTimeInterval(5),
                                              lastReroute: nil, clearedSince: since).reason, .obstacleCleared)
    }

    func testARouteRequestCarriesTheBlockedRegionsWithTheirFrameAndTime() throws {
        let region = obstacle(1, 2, 3, 4).blockedRegion
        let body = try APIClient.routeBody(venueId: UUID(), floorId: floor, start: Vec3(0, 0, 1.5), destinationPoiId: UUID(), scanVersionId: nil,
                                           accessibility: "STEP_FREE", blockedRegions: [region])
        let json = try JSONSerialization.jsonObject(with: body) as! [String: Any]
        XCTAssertEqual(json["accessibility"] as? String, "STEP_FREE", "a reroute keeps the accessibility profile")
        let r = (json["blockedRegions"] as! [[String: Any]])[0]
        XCTAssertEqual(r["floorId"] as? String, floor.apiString)
        XCTAssertEqual(r["coordinateFrameId"] as? String, frame.apiString)
        XCTAssertEqual(r["observedAt"] as? String, "2023-11-14T22:13:20Z")
        XCTAssertEqual(r["maxY"] as? Double, 4)
        let none = try JSONSerialization.jsonObject(with: try APIClient.routeBody(venueId: UUID(), floorId: floor, start: Vec3(0, 0, 0),
                                                                                destinationPoiId: UUID(), scanVersionId: nil, accessibility: nil)) as! [String: Any]
        XCTAssertNil(none["blockedRegions"])
    }

    // MARK: - scheduling and messages

    func testSchedulingIsBoundedAndBacksOffOnASlowDevice() {
        var s = S.Scheduler(minInterval: 0.25, maxInterval: 2, budget: 0.15)
        XCTAssertTrue(s.tryStart(at: 0))
        XCTAssertFalse(s.tryStart(at: 1), "one run at a time")
        s.finish(latency: 0.01)
        XCTAssertFalse(s.tryStart(at: 0.1))
        var t = 1.0
        for _ in 0..<10 { t += s.interval; XCTAssertTrue(s.tryStart(at: t)); s.finish(latency: 0.4) }
        XCTAssertEqual(s.interval, 2)
        XCTAssertTrue(s.lowDetail)
        for _ in 0..<40 { t += s.interval; XCTAssertTrue(s.tryStart(at: t)); s.finish(latency: 0.01) }
        XCTAssertEqual(s.interval, 0.25)
    }

    func testMessagesShowEstimatesNotVerifiedPositions() {
        var o = obstacle(0, 3, 1, 4)
        o.category = .person
        o.uncertaintyMeters = 0.32
        let m = ObstacleStatus.messages(sensing: .live, onPath: [(o, 3.4)], movingNearby: 0, outcome: .noAlternative(stepFree: true))
        XCTAssertEqual(m.map(\.text), [
            "A person is in the way about 3 m ahead (estimated position, ±0.3 m).",
            "No step-free way around the obstruction was found, so the route is unchanged. Wait for it to clear, or ask staff for help.",
        ])
        XCTAssertEqual(ObstacleStatus.messages(sensing: .paused("tracking is limited"), onPath: [], movingNearby: 0, outcome: .none).first?.text,
                       "Obstacle detection paused (tracking is limited). Watch for people and objects yourself.")
    }
}
