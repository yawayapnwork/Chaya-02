#if canImport(ARKit)
import ChayaARCore
import SceneKit
import UIKit
import simd

/// Draws the server's route in the ARSCNView scene (docs/ar.md, "iOS: Swift/ARKit").
///
/// The route's geometry is built once, in canonical venue coordinates (metres, +Z up), under `venueRoot`. Each frame
/// `venueRoot` is placed with the canonical -> ARKit world pose (VenueFrames.venueToWorld of the world-anchor corrected
/// transform), which also turns +Z up into ARKit's +Y up. The camera is ARKit's own: ARSCNView drives it from
/// ARFrame.camera, so the route stays fixed in the room as the phone moves.
///
/// Only the real route is drawn, and only while the session is localized (or holding through a short limited spell):
/// a tube along the route on this floor, a sphere per waypoint, the next one enlarged and highlighted, passed ones grey.
public final class RouteSceneRenderer {

    public let venueRoot = SCNNode()
    private var waypointNodes: [SCNNode] = []
    private var points: [Vec3] = []

    static let routeColor = UIColor(red: 0.12, green: 0.53, blue: 0.90, alpha: 0.9)
    static let nextColor = UIColor(red: 1.0, green: 0.70, blue: 0.0, alpha: 1)
    static let doneColor = UIColor(white: 0.62, alpha: 0.9)

    public init() {
        venueRoot.name = "chaya.venue"
        venueRoot.isHidden = true
    }

    public func attach(to scene: SCNScene) {
        if venueRoot.parent == nil { scene.rootNode.addChildNode(venueRoot) }
    }

    /// The route on this floor, canonical venue metres. Rebuilt only when it changes.
    public func setRoute(_ points: [Vec3]) {
        guard points != self.points else { return }
        self.points = points
        venueRoot.childNodes.forEach { $0.removeFromParentNode() }
        waypointNodes = []
        let vectors = points.map { SIMD3<Float>(Float($0.x), Float($0.y), Float($0.z)) }
        for i in 0..<max(0, vectors.count - 1) {
            if let segment = Self.segment(from: vectors[i], to: vectors[i + 1]) { venueRoot.addChildNode(segment) }
        }
        for (i, v) in vectors.enumerated() {
            let sphere = SCNSphere(radius: 0.08)
            sphere.firstMaterial?.diffuse.contents = Self.routeColor
            sphere.firstMaterial?.lightingModel = .constant
            let node = SCNNode(geometry: sphere)
            node.simdPosition = v
            node.name = "chaya.waypoint.\(i)"
            venueRoot.addChildNode(node)
            waypointNodes.append(node)
        }
    }

    /// Places the venue frame in the ARKit world for this frame, and highlights `nextIndex`.
    public func update(venueToWorld: Pose, nextIndex: Int, visible: Bool) {
        venueRoot.simdTransform = venueToWorld.simdTransform
        venueRoot.isHidden = !visible || points.isEmpty
        for (i, node) in waypointNodes.enumerated() {
            node.geometry?.firstMaterial?.diffuse.contents = i < nextIndex ? Self.doneColor : i == nextIndex ? Self.nextColor : Self.routeColor
            node.simdScale = SIMD3<Float>(repeating: i == nextIndex ? 1.8 : 1)
        }
    }

    public func hide() {
        venueRoot.isHidden = true
    }

    public var waypointCount: Int { waypointNodes.count }
    public var segmentCount: Int { venueRoot.childNodes.count - waypointNodes.count }

    /// A tube from a to b. SCNCylinder's axis is its local +Y.
    static func segment(from a: SIMD3<Float>, to b: SIMD3<Float>) -> SCNNode? {
        let d = b - a
        let length = simd_length(d)
        guard length > 1e-4 else { return nil }
        let tube = SCNCylinder(radius: 0.03, height: CGFloat(length))
        tube.firstMaterial?.diffuse.contents = routeColor
        tube.firstMaterial?.lightingModel = .constant
        let node = SCNNode(geometry: tube)
        node.simdPosition = (a + b) / 2
        node.simdOrientation = simd_quatf(from: SIMD3<Float>(0, 1, 0), to: d / length)
        node.name = "chaya.segment"
        return node
    }
}
#endif
