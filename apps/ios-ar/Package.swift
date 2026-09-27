// swift-tools-version:5.9
import PackageDescription

// The iOS AR client's libraries. The runnable app that embeds them is App/ (XcodeGen: App/project.yml); see README.md.
let package = Package(
    name: "ChayaAR",
    platforms: [.iOS(.v16)],
    products: [
        // Pure Swift: backend models and API client, the device/canonical frame chain, marker registry (reference image
        // -> anchor id), route geometry, and the tracking/relocalization state machine. No ARKit or UIKit import, so
        // `swift test` runs ChayaARCoreTests anywhere Swift runs (Linux CI included).
        .library(name: "ChayaARCore", targets: ["ChayaARCore"]),
        // The ARKit integration: ARSessionManager, reference images, SceneKit route renderer. iOS-only (guarded with
        // #if canImport(ARKit)); its behaviour with a camera needs a physical device.
        .library(name: "ChayaARKitSession", targets: ["ChayaARKitSession"]),
    ],
    targets: [
        .target(name: "ChayaARCore"),
        .target(name: "ChayaARKitSession", dependencies: ["ChayaARCore"]),
        .testTarget(name: "ChayaARCoreTests", dependencies: ["ChayaARCore"]),
    ]
)
