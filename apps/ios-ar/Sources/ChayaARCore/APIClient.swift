import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Thin client around the shared backend path/anchor API (docs/ar.md, docs/navigation.md, docs/security.md).
/// It never talks to Postgres/MinIO directly and never computes its own route -- exactly the same
/// `/api/v1` surface the web app's apps/web/lib/ar-api.ts and lib/navigation-api.ts call.
public struct APIError: Error, Equatable, Sendable {
    public let status: Int
    public let code: String
    public let detail: String

    public init(status: Int, code: String, detail: String) {
        self.status = status; self.code = code; self.detail = detail
    }
}

/// Adds credentials to each request.
public protocol RequestAuthorizer: Sendable {
    func authorize(_ request: inout URLRequest) async throws
}

/// Supplies an OIDC bearer token (an operator's session). Token acquisition (auth-code + PKCE via the system browser,
/// ARCHITECTURE.md #7) is outside this package.
public protocol AccessTokenProvider: Sendable {
    func currentAccessToken() async throws -> String
}

public struct BearerAuthorizer: RequestAuthorizer {
    let tokenProvider: AccessTokenProvider

    public init(tokenProvider: AccessTokenProvider) { self.tokenProvider = tokenProvider }

    public func authorize(_ request: inout URLRequest) async throws {
        request.setValue("Bearer \(try await tokenProvider.currentAccessToken())", forHTTPHeaderField: "Authorization")
    }
}

/// A venue-bound public viewer token (POST /public/viewer-token), sent as `X-Chaya-Viewer-Token` exactly as the web
/// viewer sends it (apps/web/lib/session.ts). It allows reading the venue's floors, POIs, anchors and target images,
/// planning routes and relocalizing, and nothing else (docs/ar.md "Security").
public struct ViewerTokenAuthorizer: RequestAuthorizer {
    public let token: PublicViewerToken

    public init(token: PublicViewerToken) { self.token = token }

    public func authorize(_ request: inout URLRequest) async throws {
        guard token.expiresAt > Date() else {
            throw APIError(status: 401, code: "VIEWER_TOKEN_EXPIRED", detail: "the viewing link's token expired; open the link again")
        }
        request.setValue(token.token, forHTTPHeaderField: "X-Chaya-Viewer-Token")
    }
}

/// Immutable after init; URL and URLSession are Sendable on Apple platforms but not yet annotated in Linux Foundation.
public final class APIClient: @unchecked Sendable {
    private let baseURL: URL
    private let authorizer: RequestAuthorizer?
    private let session: URLSession

    public init(baseURL: URL, authorizer: RequestAuthorizer?, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.authorizer = authorizer
        self.session = session
    }

    public func with(authorizer: RequestAuthorizer) -> APIClient {
        APIClient(baseURL: baseURL, authorizer: authorizer, session: session)
    }

    func url(_ path: String) -> URL {
        baseURL.appendingPathComponent("api/v1" + path)
    }

    private func send(_ path: String, method: String = "GET", body: Data? = nil, authorized: Bool = true) async throws -> Data {
        var request = URLRequest(url: url(path))
        request.httpMethod = method
        if authorized {
            guard let authorizer else { throw APIError(status: 401, code: "NOT_SIGNED_IN", detail: "no credentials") }
            try await authorizer.authorize(&request)
        }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        let (data, response) = try await session.chayaData(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw APIError(status: 0, code: "NO_HTTP_RESPONSE", detail: "response was not an HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw Self.decodeError(status: http.statusCode, data: data)
        }
        return data
    }

    private func request<T: Decodable>(_ path: String, method: String = "GET", body: Data? = nil, authorized: Bool = true) async throws -> T {
        let data = try await send(path, method: method, body: body, authorized: authorized)
        if data.isEmpty {
            // Callers only ask for Decodable results where the server always returns a body.
            throw APIError(status: 200, code: "EMPTY_BODY", detail: "expected a JSON body")
        }
        return try JSONDecoder.chayaAR.decode(T.self, from: data)
    }

    static func decodeError(status: Int, data: Data) -> APIError {
        struct Problem: Decodable { let code: String?; let detail: String?; let message: String? }
        let problem = try? JSONDecoder().decode(Problem.self, from: data)
        return APIError(status: status, code: problem?.code ?? "HTTP_\(status)",
                        detail: problem?.detail ?? problem?.message ?? "HTTP \(status)")
    }

    // ---- public viewer link -----------------------------------------------------------------------------

    /// Unauthenticated: trades a public viewer link secret for a short-lived, venue-bound token.
    public func exchangeViewerLink(secret: String) async throws -> PublicViewerToken {
        try await request("/public/viewer-token", method: "POST",
                          body: try JSONEncoder.chayaAR.encode(["secret": secret]), authorized: false)
    }

    // ---- venue ------------------------------------------------------------------------------------------

    public func getVenue(venueId: UUID) async throws -> Venue {
        try await request("/venues/\(venueId.apiString)")
    }

    public func listFloors(venueId: UUID) async throws -> [Floor] {
        try await request("/venues/\(venueId.apiString)/floors")
    }

    public func listPois(venueId: UUID) async throws -> [Poi] {
        try await request("/venues/\(venueId.apiString)/pois")
    }

    // ---- anchors ----------------------------------------------------------------------------------------

    public func listAnchors(venueId: UUID, floorId: UUID) async throws -> [Anchor] {
        try await request("/venues/\(venueId.apiString)/floors/\(floorId.apiString)/anchors")
    }

    /// The IMAGE_TARGET's server-generated target image (PNG): the exact image the operator printed.
    public func targetImage(venueId: UUID, floorId: UUID, anchorId: UUID) async throws -> Data {
        try await send("/venues/\(venueId.apiString)/floors/\(floorId.apiString)/anchors/\(anchorId.apiString)/target.png")
    }

    /// `scanVersionId`: the version this session pinned for the floor at its first localization (nil before it). The server
    /// refuses the request (409 VERSION_MISMATCH) when the floor publishes another version, so the device is never placed
    /// in one version's frame while it draws another version's route.
    public func relocalize(venueId: UUID, floorId: UUID, observations: [AnchorObservation], scanVersionId: UUID?) async throws -> RelocalizationResponse {
        try await request("/venues/\(venueId.apiString)/floors/\(floorId.apiString)/anchors/relocalize", method: "POST",
                          body: try Self.relocalizeBody(observations, scanVersionId: scanVersionId))
    }

    static func relocalizeBody(_ observations: [AnchorObservation], scanVersionId: UUID?) throws -> Data {
        struct Body: Encodable { let observations: [AnchorObservation]; let scanVersionId: String? }
        return try JSONEncoder.chayaAR.encode(Body(observations: observations, scanVersionId: scanVersionId?.apiString))
    }

    // ---- routing ----------------------------------------------------------------------------------------

    /// `start` is canonical venue metres (+Z up) on `floorId` -- the device's position as solved by relocalization.
    /// `scanVersionId`: route on exactly that FINALIZED version of the floor (its pinned navmesh, its frame, the destination
    /// as it is in that version) instead of whatever the floor publishes when the request lands. The AR app passes the
    /// version its localization was solved in. Such a route stays on that floor (the server refuses another floor's
    /// destination with VERSION_WRONG_FLOOR).
    public func planRoute(venueId: UUID, floorId: UUID, start: Vec3, destinationPoiId: UUID, scanVersionId: UUID?,
                          accessibility: String? = nil) async throws -> RouteResponse {
        try await request("/navigation/routes", method: "POST",
                          body: try Self.routeBody(venueId: venueId, floorId: floorId, start: start, destinationPoiId: destinationPoiId,
                                                   scanVersionId: scanVersionId, accessibility: accessibility))
    }

    static func routeBody(venueId: UUID, floorId: UUID, start: Vec3, destinationPoiId: UUID, scanVersionId: UUID?,
                          accessibility: String?) throws -> Data {
        struct Body: Encodable {
            let venueId: String
            let floorId: String
            let start: [Double]
            let destinationPoiId: String
            let scanVersionId: String?
            let accessibility: String?
        }
        return try JSONEncoder.chayaAR.encode(Body(venueId: venueId.apiString, floorId: floorId.apiString,
                                                   start: [start.x, start.y, start.z], destinationPoiId: destinationPoiId.apiString,
                                                   scanVersionId: scanVersionId?.apiString, accessibility: accessibility))
    }
}

extension URLSession {
    func chayaData(for request: URLRequest) async throws -> (Data, URLResponse) {
        #if canImport(FoundationNetworking)
        // Linux Foundation has no async data(for:) yet.
        return try await withCheckedThrowingContinuation { continuation in
            dataTask(with: request) { data, response, error in
                if let error { continuation.resume(throwing: error) }
                else if let response { continuation.resume(returning: (data ?? Data(), response)) }
                else { continuation.resume(throwing: URLError(.badServerResponse)) }
            }.resume()
        }
        #else
        return try await data(for: request)
        #endif
    }
}

extension UUID {
    /// Lower-case, as the server prints ids. Foundation's uuidString is upper-case; the server accepts either, but a
    /// reference image named after an anchor must match the id the server returned byte for byte.
    public var apiString: String { uuidString.lowercased() }
}

extension JSONDecoder {
    /// ISO-8601 with or without fractional seconds: Java's Instant writes them whenever they are non-zero.
    public static let chayaAR: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            if let date = ChayaDates.parse(text) { return date }
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "not an ISO-8601 instant: \(text)"))
        }
        return decoder
    }()
}

extension JSONEncoder {
    public static let chayaAR: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()
}

enum ChayaDates {
    static func parse(_ text: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: text) { return date }
        let whole = ISO8601DateFormatter()
        whole.formatOptions = [.withInternetDateTime]
        return whole.date(from: text)
    }
}
