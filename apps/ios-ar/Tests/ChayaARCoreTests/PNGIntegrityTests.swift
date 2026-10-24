import Foundation
import XCTest
@testable import ChayaARCore

/// A reference image must be the complete printed artwork: truncated or corrupt target PNGs are refused on the file
/// itself, before ImageIO (which may report a truncated PNG as complete).
final class PNGIntegrityTests: XCTestCase {

    /// The server's real target artwork (packages/contracts/fixtures/ar, kept pixel-identical to ImageTarget.render by
    /// services/api ImageTargetFixtureTest).
    func fixture(file: StaticString = #filePath, line: UInt = #line) throws -> Data {
        let dir = URL(fileURLWithPath: "\(file)").deletingLastPathComponent()
            .appendingPathComponent("../../../../packages/contracts/fixtures/ar").standardizedFileURL
        let name = try XCTUnwrap(try FileManager.default.contentsOfDirectory(atPath: dir.path).first { $0.hasSuffix(".png") },
                                 "no target fixture in \(dir.path)", file: file, line: line)
        return try Data(contentsOf: dir.appendingPathComponent(name))
    }

    func testTheIntactFixtureIsComplete() throws {
        XCTAssertNil(PNGIntegrity.problem(in: try fixture()))
    }

    func testTruncatedCorruptAndForeignDataAreRefused() throws {
        let png = try fixture()
        var flipped = png
        flipped[png.count / 2] ^= 0x01
        let cases: [(String, Data)] = [
            ("empty", Data()),
            ("text", Data("not a png".utf8)),
            ("signature only", png.prefix(8)),
            ("truncated to its header", png.prefix(64)),
            ("truncated halfway", png.prefix(png.count / 2)),
            ("missing only its last byte", png.prefix(png.count - 1)),
            ("one bit flipped in the image data", flipped),
            ("trailing data after IEND", png + Data([0])),
        ]
        for (label, data) in cases {
            XCTAssertNotNil(PNGIntegrity.problem(in: data), label)
        }
    }

    func testTheCRCIsPNGsCRC32() {
        // The IEND chunk's CRC is fixed by the PNG specification: AE 42 60 82.
        XCTAssertEqual(PNGIntegrity.crc32(ArraySlice(Array("IEND".utf8))), 0xAE42_6082)
    }
}
