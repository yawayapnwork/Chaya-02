import XCTest
@testable import ChayaARCore

/// The wire format against the backend DTOs (dev.chaya.api.ar.ArDtos, NavigationDtos): what the server really sends,
/// including null residuals and Java Instant timestamps, and what the client sends.
final class APIClientCodingTests: XCTestCase {

    func testASingleAnchorRelocalizationWithANullResidualDecodes() throws {
        let json = """
        {"deviceToVenueTransform":{"x":1,"y":2,"z":0,"qx":0.7071,"qy":0,"qz":0,"qw":0.7071},"residualMeters":null,
         "anchorsUsed":1,"gravityTiltDegrees":0.4,"deviceFrameConvention":"DEVICE_Y_UP_RIGHT_HANDED_METRES",
         "coordinateFrameId":"6f1c2c1e-1111-4a4a-9999-0123456789ab"}
        """
        let r = try JSONDecoder.chayaAR.decode(RelocalizationResponse.self, from: Data(json.utf8))
        XCTAssertNil(r.residualMeters, "one anchor: residual unknown, never 0")
        XCTAssertEqual(r.anchorsUsed, 1)
        XCTAssertEqual(r.deviceFrameConvention, VenueFrames.deviceFrameConvention)
    }

    func testAnAnchorWithJavaInstantTimestampsAndAPrintedSizeDecodes() throws {
        let json = """
        {"id":"0b8f9d1a-2222-4b4b-8888-0123456789ab","venueId":"0b8f9d1a-3333-4b4b-8888-0123456789ab",
         "floorId":"0b8f9d1a-4444-4b4b-8888-0123456789ab","markerType":"IMAGE_TARGET","markerIdentifier":"entrance",
         "markerSizeMeters":0.3,"physicalPose":{"x":0,"y":0,"z":0,"qx":0,"qy":0,"qz":0,"qw":1},
         "digitalPose":{"x":5,"y":2,"z":1.4,"qx":1,"qy":0,"qz":0,"qw":0},"calibrationStatus":"CALIBRATED",
         "lastCalibratedAt":"2026-09-27T10:15:30.123456Z","coordinateFrameId":"0b8f9d1a-5555-4b4b-8888-0123456789ab"}
        """
        let a = try JSONDecoder.chayaAR.decode(Anchor.self, from: Data(json.utf8))
        XCTAssertEqual(a.markerSizeMeters, 0.3)
        XCTAssertEqual(AnchorSupport.of(a), .trackable)
        XCTAssertEqual(a.lastCalibratedAt!.timeIntervalSince1970, 1790504130.123, accuracy: 0.001)
        let whole = try JSONDecoder.chayaAR.decode(PublicViewerToken.self, from: Data(
            #"{"token":"cvt_x","expiresAt":"2026-09-27T10:15:30Z","venueId":"0b8f9d1a-3333-4b4b-8888-0123456789ab"}"#.utf8))
        XCTAssertEqual(whole.expiresAt.timeIntervalSince1970, 1790504130, accuracy: 0.001)
    }

    func testRelocalizeSendsTheDetectedAnchorsIdAndItsMeasuredPose() throws {
        let anchorId = UUID(uuidString: "0B8F9D1A-2222-4B4B-8888-0123456789AB")!
        let body = try APIClient.relocalizeBody([AnchorObservation(anchorId: anchorId, observedPose: Pose(x: 0.1, y: -1.5, z: -2))])
        let json = try JSONSerialization.jsonObject(with: body) as! [String: Any]
        let first = (json["observations"] as! [[String: Any]])[0]
        XCTAssertEqual((first["anchorId"] as! String).lowercased(), "0b8f9d1a-2222-4b4b-8888-0123456789ab")
        XCTAssertEqual((first["observedPose"] as! [String: Double])["y"], -1.5)
    }

    func testARouteRequestCarriesTheCanonicalStart() throws {
        let body = try APIClient.routeBody(venueId: UUID(), floorId: UUID(), start: Vec3(5, 0, 1.5), destinationPoiId: UUID(), accessibility: nil)
        let json = try JSONSerialization.jsonObject(with: body) as! [String: Any]
        XCTAssertEqual(json["start"] as! [Double], [5, 0, 1.5])
    }

    func testURLsAndErrors() {
        let client = APIClient(baseURL: URL(string: "https://chaya.example")!, authorizer: nil)
        XCTAssertEqual(client.url("/venues/v/floors").absoluteString, "https://chaya.example/api/v1/venues/v/floors")
        let e = APIClient.decodeError(status: 409, data: Data(#"{"code":"ANCHOR_NOT_CALIBRATED","detail":"no"}"#.utf8))
        XCTAssertEqual(e, APIError(status: 409, code: "ANCHOR_NOT_CALIBRATED", detail: "no"))
        XCTAssertEqual(APIClient.decodeError(status: 502, data: Data()).code, "HTTP_502")
    }
}
