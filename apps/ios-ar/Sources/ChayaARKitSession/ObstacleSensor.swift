#if canImport(ARKit)
import ARKit
import ChayaARCore
import Foundation
import Vision

/// Camera obstacle sensing on iOS (docs/navigation.md, "Camera obstacle sensing"): ARFrame -> ObstacleSensing -> ObstacleTracker.
///
///   depth      LiDAR devices: ARKit scene depth (`.sceneDepth`: a depth map aligned with the camera image, with ARKit's
///              per-pixel confidence). A12+ devices without LiDAR: ARKit person segmentation with depth
///              (`.personSegmentationWithDepth`: `estimatedDepthData`, depth on person pixels only). Neither: no sensing.
///   detection  Vision's on-device VNDetectHumanRectanglesRequest on the camera image: people only. Other objects are
///              found from depth alone (ObstacleSensing.corridorOccupancy), which needs LiDAR's full depth map.
///   placement  only from the same frame's depth inside the box (ObstacleSensing.localize); a box without depth is dropped.
///
/// Work runs on a background queue, at most once per scheduler interval (3 Hz, backing off to every 2 s when slow), one
/// run at a time, so ARKit's rendering and the delegate queue are never blocked. Pixel buffers are read in place and
/// released when the run ends: no camera image is copied, stored or sent. Only footprints leave the device, in route
/// requests. It needs a physical device; nothing here runs in the Simulator or in CI.
@available(iOS 16.0, *)
public final class ObstacleSensor {

    public enum Availability: Equatable {
        case available(depth: ObstacleSensing.DepthSource, people: Bool)
        case unavailable(String)
    }

    /// Adds the depth this device can give to the configuration, and says what sensing is possible.
    public static func configure(_ configuration: ARWorldTrackingConfiguration) -> Availability {
        if ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) {
            configuration.frameSemantics.insert(.sceneDepth)
            return .available(depth: .lidar, people: true)
        }
        if ARWorldTrackingConfiguration.supportsFrameSemantics(.personSegmentationWithDepth) {
            configuration.frameSemantics.insert(.personSegmentationWithDepth)
            return .available(depth: .arkitPersonDepth, people: true)
        }
        return .unavailable("this device has neither LiDAR nor ARKit person depth")
    }

    public let availability: Availability
    /// Main queue only.
    public private(set) var blocking: [ObstacleReroute.Observation] = []
    public private(set) var movingPeople = 0
    public private(set) var scheduler = ObstacleSensing.Scheduler(minInterval: 1.0 / 3, maxInterval: 2, budget: 0.15)
    /// Called on the main queue after each run.
    public var onResult: (() -> Void)?

    private let queue = DispatchQueue(label: "dev.chaya.ar.obstacles", qos: .userInitiated)
    /// Owned by `queue`.
    private var tracker = ObstacleTracker()

    public init(availability: Availability) {
        self.availability = availability
    }

    public var statsText: String? {
        guard let p90 = scheduler.p90 else { return nil }
        return String(format: "Obstacle sensing every %.0f ms, %.0f ms per run (p90)%@", scheduler.interval * 1000, p90 * 1000,
                      scheduler.lowDetail ? ", reduced detail" : "")
    }

    /// Main queue. `context` must be the frame's own camera pose and the corrected localization; `pathAhead` the route still
    /// ahead on this floor (canonical).
    public func process(_ frame: ARFrame, context: ObstacleSensing.CameraContext, pathAhead: [Vec3]) {
        guard case .available(let source, let people) = availability,
              scheduler.tryStart(at: ProcessInfo.processInfo.systemUptime) else { return }
        let depthMap: CVPixelBuffer?, confidenceMap: CVPixelBuffer?
        switch source {
        case .lidar: depthMap = frame.sceneDepth?.depthMap; confidenceMap = frame.sceneDepth?.confidenceMap
        case .arkitPersonDepth: depthMap = frame.estimatedDepthData; confidenceMap = nil
        }
        let image = frame.capturedImage
        let k = frame.camera.intrinsics, res = frame.camera.imageResolution
        let intrinsics = ObstacleSensing.Intrinsics(fx: Double(k[0][0]), fy: Double(k[1][1]), cx: Double(k[2][0]), cy: Double(k[2][1]),
                                                    width: Double(res.width), height: Double(res.height))
        let lowDetail = scheduler.lowDetail
        queue.async { [weak self] in
            guard let self else { return }
            let started = ProcessInfo.processInfo.systemUptime
            var located: [ObstacleSensing.Located] = []
            var visibility: (Vec3) -> ObstacleSensing.Visibility = { _ in .notInView }
            if let depthMap {
                let reader = DepthReader(depthBuffer: depthMap, confidenceBuffer: confidenceMap)
                let grid = lowDetail ? (16, 12) : (32, 24)
                // People: Vision boxes, placed with the depth inside them.
                if people {
                    for det in Self.detectPeople(in: image, frameTime: context.frameTime) {
                        let depth = ObstacleSensing.DepthFrame(source: source, frameTime: context.frameTime,
                                                               samples: reader.samples(in: (det.u0, det.v0, det.u1, det.v1), cols: 12, rows: 16, intrinsics: intrinsics))
                        if case .located(let o) = ObstacleSensing.localize(det, depth: depth, ctx: context, ground: pathAhead) { located.append(o) }
                    }
                }
                // Anything else in the corridor: only from LiDAR's full depth map (person depth covers people only).
                if source == .lidar {
                    let all = ObstacleSensing.DepthFrame(source: source, frameTime: context.frameTime,
                                                         samples: reader.samples(in: (0, 0, 1, 1), cols: grid.0, rows: grid.1, intrinsics: intrinsics))
                    located += ObstacleSensing.corridorOccupancy(all, ctx: context, pathAhead: pathAhead)
                    visibility = { p in
                        ObstacleSensing.visibility(at: p, ctx: context) { c in
                            guard let at = intrinsics.project(c), let d = reader.depth(u: at.u, v: at.v) else { return nil }
                            return d * (c.x * c.x + c.y * c.y + c.z * c.z).squareRoot() / -c.z
                        }
                    }
                }
            }
            self.tracker.update(located, now: context.wallClock, floorId: context.floorId, coordinateFrameId: context.coordinateFrameId,
                                visibility: visibility)
            let blocking = self.tracker.blocking, moving = self.tracker.movingPeople
            let latency = ProcessInfo.processInfo.systemUptime - started
            DispatchQueue.main.async {
                self.blocking = blocking
                self.movingPeople = moving
                self.scheduler.finish(latency: latency)
                self.onResult?()
            }
        }
    }

    /// Not localized or tracking not normal: nothing is observed; tracks only age out.
    public func pause(now: Date) {
        queue.async { [weak self] in
            guard let self else { return }
            self.tracker.expire(now: now)
            let blocking = self.tracker.blocking, moving = self.tracker.movingPeople
            DispatchQueue.main.async { self.blocking = blocking; self.movingPeople = moving }
        }
    }

    /// Vision's on-device person detector, on the camera image in its native (landscape) orientation, so its boxes share
    /// the intrinsics' pixel frame. Vision boxes have their origin at the bottom left; ObstacleSensing's at the top left.
    static func detectPeople(in image: CVPixelBuffer, frameTime: TimeInterval) -> [ObstacleSensing.Detection] {
        let request = VNDetectHumanRectanglesRequest()
        request.upperBodyOnly = false
        do {
            try VNImageRequestHandler(cvPixelBuffer: image, orientation: .up, options: [:]).perform([request])
        } catch {
            return []
        }
        return (request.results ?? []).map { o in
            let b = o.boundingBox
            return ObstacleSensing.Detection(category: .person, detector: "VNDetectHumanRectanglesRequest", score: Double(o.confidence),
                                             frameTime: frameTime, u0: Double(b.minX), v0: Double(1 - b.maxY), u1: Double(b.maxX), v1: Double(1 - b.minY))
        }
    }
}

/// Reads an ARKit Float32 depth map (and, for scene depth, its confidence map: 0 low, 1 medium, 2 high) in place.
@available(iOS 16.0, *)
struct DepthReader {
    let depthBuffer: CVPixelBuffer
    let confidenceBuffer: CVPixelBuffer?

    func depth(u: Double, v: Double) -> Double? {
        guard let d = read(depthBuffer, u: u, v: v, as: Float32.self).map({ Double($0) }), d > 0, d.isFinite else { return nil }
        return d
    }

    func confidence(u: Double, v: Double) -> Double? {
        guard let confidenceBuffer else { return nil }
        return read(confidenceBuffer, u: u, v: v, as: UInt8.self).map { Double($0) / 2 }
    }

    /// A cols x rows grid over the normalized box (u0, v0, u1, v1), unprojected with the camera image's intrinsics (the
    /// depth map is aligned with the camera image, at a lower resolution).
    func samples(in box: (Double, Double, Double, Double), cols: Int, rows: Int, intrinsics: ObstacleSensing.Intrinsics) -> [ObstacleSensing.DepthSample] {
        var out: [ObstacleSensing.DepthSample] = []
        for r in 0..<rows { for c in 0..<cols {
            let u = box.0 + (box.2 - box.0) * (Double(c) + 0.5) / Double(cols)
            let v = box.1 + (box.3 - box.1) * (Double(r) + 0.5) / Double(rows)
            guard let d = depth(u: u, v: v), let p = intrinsics.unproject(u: u, v: v, depth: d) else { continue }
            out.append(ObstacleSensing.DepthSample(u: u, v: v, point: p, confidence: confidence(u: u, v: v)))
        } }
        return out
    }

    private func read<T>(_ buffer: CVPixelBuffer, u: Double, v: Double, as: T.Type) -> T? {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let w = CVPixelBufferGetWidth(buffer), h = CVPixelBufferGetHeight(buffer), row = CVPixelBufferGetBytesPerRow(buffer)
        let x = min(w - 1, max(0, Int(u * Double(w)))), y = min(h - 1, max(0, Int(v * Double(h))))
        return base.advanced(by: y * row + x * MemoryLayout<T>.stride).assumingMemoryBound(to: T.self).pointee
    }
}
#endif
