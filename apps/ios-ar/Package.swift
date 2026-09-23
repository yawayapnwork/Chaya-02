// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "ChayaAR",
    platforms: [.iOS(.v16)],
    products: [
        // Pure Swift: models, the API client, and the anchor/relocalization math. No ARKit or UIKit
        // import, so `swift test` runs ChayaARCoreTests anywhere Swift runs (Linux CI included) -- see
        // README.md and docs/ar.md "Testing".
        .library(name: "ChayaARCore", targets: ["ChayaARCore"]),
        // The real ARKit session wrapper. iOS-only; requires a physical device with a camera to run.
        .library(name: "ChayaARKitSession", targets: ["ChayaARKitSession"]),
    ],
    targets: [
        .target(name: "ChayaARCore"),
        .target(name: "ChayaARKitSession", dependencies: ["ChayaARCore"]),
        .testTarget(name: "ChayaARCoreTests", dependencies: ["ChayaARCore"]),
    ]
)
