#if canImport(ARKit)
import ARKit
import ChayaARCore
import ImageIO
import simd

extension TrackingQuality {
    /// ARKit's own camera tracking state, as the core value the state machine reads.
    public init(_ state: ARCamera.TrackingState) {
        switch state {
        case .normal:
            self = .normal
        case .notAvailable:
            self = .notAvailable
        case .limited(let reason):
            switch reason {
            case .initializing: self = .limited(.initializing)
            case .excessiveMotion: self = .limited(.excessiveMotion)
            case .insufficientFeatures: self = .limited(.insufficientFeatures)
            case .relocalizing: self = .limited(.relocalizing)
            @unknown default: self = .limited(.other)
            }
        }
    }
}

extension Pose {
    /// A pose from an ARKit rigid transform (column-major 4x4: rotation in the upper 3x3, translation in column 3).
    /// Values are ARKit's own measurements; nothing is synthesized.
    public init(_ transform: simd_float4x4) {
        let q = simd_quatf(transform)
        let t = transform.columns.3
        self.init(x: Double(t.x), y: Double(t.y), z: Double(t.z),
                  qx: Double(q.imag.x), qy: Double(q.imag.y), qz: Double(q.imag.z), qw: Double(q.real))
    }

    /// The rigid transform this pose describes.
    public var simdTransform: simd_float4x4 {
        let q = simd_quatf(ix: Float(qx), iy: Float(qy), iz: Float(qz), r: Float(qw)).normalized
        var m = simd_float4x4(q)
        m.columns.3 = SIMD4<Float>(Float(x), Float(y), Float(z), 1)
        return m
    }
}

extension ImageDetection {
    /// One frame's report of one detected image: the reference image's name, the ARImageAnchor transform, the frame
    /// timestamp, whether ARKit is tracking it in this frame, and ARKit's estimate of its size relative to the
    /// registered physical width.
    public init(_ anchor: ARImageAnchor, timestamp: TimeInterval) {
        self.init(referenceImageName: anchor.referenceImage.name, pose: Pose(anchor.transform), timestamp: timestamp,
                  isTracked: anchor.isTracked, estimatedScaleFactor: Double(anchor.estimatedScaleFactor))
    }
}

/// Builds ARKit reference images from the server's target images (GET .../anchors/{a}/target.png).
public enum ReferenceImages {

    public struct InvalidImage: Error, LocalizedError {
        public let markerIdentifier: String
        public let reason: String
        public var errorDescription: String? { "\(markerIdentifier): \(reason)" }
    }

    /// One reference image per registered marker: the exact printed artwork, at its registered printed width, named
    /// with the marker's anchor id so a detection identifies the backend anchor.
    public static func make(png: Data, marker: RegisteredMarker) throws -> ARReferenceImage {
        guard let source = CGImageSourceCreateWithData(png as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            throw InvalidImage(markerIdentifier: marker.anchor.markerIdentifier, reason: "the target image is not a decodable image")
        }
        let reference = ARReferenceImage(image, orientation: .up, physicalWidth: CGFloat(marker.physicalWidthMeters))
        reference.name = marker.referenceImageName
        return reference
    }

    /// ARKit's own verdict on whether an image can be tracked (ARReferenceImage.validate). nil when it can.
    public static func validate(_ image: ARReferenceImage) async -> String? {
        await withCheckedContinuation { continuation in
            image.validate { error in continuation.resume(returning: error?.localizedDescription) }
        }
    }
}
#endif
