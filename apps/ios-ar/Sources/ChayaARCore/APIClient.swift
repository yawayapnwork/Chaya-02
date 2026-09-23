import Foundation

/// Thin client around the shared backend path/anchor API (docs/ar.md, docs/navigation.md, docs/security.md).
/// It never talks to Postgres/MinIO/Redis directly and never computes its own route -- exactly the same
/// `/api/v1` surface the web app's apps/web/lib/ar-api.ts and lib/navigation-api.ts call.
public struct APIError: Error, Sendable {
    public let status: Int
    public let code: String
    public let detail: String
}

/// Supplies the bearer token for each request. Real token acquisition (OIDC auth-code + PKCE via the
/// system browser, per ARCHITECTURE.md #7) is an app-level concern outside this package; APIClient only
/// needs something that can hand it a current, valid access token.
public protocol AccessTokenProvider: Sendable {
    func currentAccessToken() async throws -> String
}

public final class APIClient: Sendable {
    private let baseURL: URL
    private let tokenProvider: AccessTokenProvider
    private let session: URLSession

    public init(baseURL: URL, tokenProvider: AccessTokenProvider, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.tokenProvider = tokenProvider
        self.session = session
    }

    private func request<T: Decodable>(_ path: String, method: String = "GET", body: Encodable? = nil) async throws -> T {
        var url = baseURL
        url.append(path: "/api/v1" + path)
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Bearer \(try await tokenProvider.currentAccessToken())", forHTTPHeaderField: "Authorization")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder.chayaAR.encode(AnyEncodable(body))
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw APIError(status: 0, code: "NO_HTTP_RESPONSE", detail: "response was not an HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw decodeError(status: http.statusCode, data: data)
        }
        if data.isEmpty {
            // Callers only ask for Decodable results where the server always returns a body; an empty
            // body here would be a caller bug against a void endpoint, not a runtime case to paper over.
            throw APIError(status: http.statusCode, code: "EMPTY_BODY", detail: "expected a JSON body")
        }
        return try JSONDecoder.chayaAR.decode(T.self, from: data)
    }

    private func decodeError(status: Int, data: Data) -> APIError {
        struct Problem: Decodable { let code: String?; let detail: String? }
        let problem = try? JSONDecoder().decode(Problem.self, from: data)
        return APIError(status: status, code: problem?.code ?? "HTTP_\(status)", detail: problem?.detail ?? "HTTP \(status)")
    }

    // ---- anchors --------------------------------------------------------------------------------------

    public func listAnchors(venueId: UUID, floorId: UUID) async throws -> [Anchor] {
        try await request("/venues/\(venueId)/floors/\(floorId)/anchors")
    }

    public func relocalize(venueId: UUID, floorId: UUID, observations: [AnchorObservation]) async throws -> RelocalizationResponse {
        struct Body: Encodable { let observations: [AnchorObservation] }
        return try await request("/venues/\(venueId)/floors/\(floorId)/anchors/relocalize", method: "POST", body: Body(observations: observations))
    }

    // ---- routing ----------------------------------------------------------------------------------------

    public func planRoute(venueId: UUID, floorId: UUID, start: Pose, destinationPoiId: UUID, accessibility: String? = nil) async throws -> RouteResponse {
        struct Body: Encodable {
            let venueId: UUID
            let floorId: UUID
            let start: [Double]
            let destinationPoiId: UUID
            let accessibility: String?
        }
        let body = Body(venueId: venueId, floorId: floorId, start: [start.x, start.y, start.z],
                         destinationPoiId: destinationPoiId, accessibility: accessibility)
        return try await request("/navigation/routes", method: "POST", body: body)
    }
}

extension JSONDecoder {
    static let chayaAR: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()
}

extension JSONEncoder {
    static let chayaAR: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }()
}

/// Type-erasing box so `request` can accept any Encodable body without becoming generic over it too.
private struct AnyEncodable: Encodable {
    private let encodeFn: (Encoder) throws -> Void
    init(_ wrapped: Encodable) { self.encodeFn = wrapped.encode }
    func encode(to encoder: Encoder) throws { try encodeFn(encoder) }
}
