import { createHash } from "node:crypto";
import type { Page, Route } from "@playwright/test";

// A stateful stand-in for the capture API (services/api CaptureController/MediaService) for the offline-recovery specs. It
// keeps the real server's rules that matter to a retrying client -- clientSessionId and clientFileId idempotency (V38),
// exact part sizes and part checksums, whole-file SHA-256 verification before any verdict, CAPTURE_ERASED,
// SCAN_VERSION_CHANGED -- and lets a test inject the failures a real network and server produce. It is a test double: the
// real server's behaviour is tested in services/api (CaptureOfflineRecoveryTest).

export const VENUE = "11111111-1111-1111-1111-111111111111";
export const FLOOR = "22222222-2222-2222-2222-222222222222";
export const PART = 1024;

interface Media {
  id: string; clientFileId: string | null; sha256: string; size: number; filename: string; kind: string;
  status: "PENDING" | "VALIDATING" | "ACCEPTED" | "REJECTED" | "QUARANTINED"; parts: Map<number, Buffer>;
  rejectionCode: string | null; polls: number;
}
interface CaptureRec { id: string; clientSessionId: string | null; status: string; floorId: string | null; media: Map<string, Media>; startedAt: string }

export interface Fault {
  /** "METHOD path-suffix", e.g. "PUT parts/2", "POST captures", "POST media" */
  match: string;
  /** "abort": the connection drops before the server acts; "lose-answer": it acts, then the answer is lost; or a status. */
  kind: "abort" | "lose-answer" | number;
  code?: string;
  times: number;
}

export class MockCaptureServer {
  captures = new Map<string, CaptureRec>();
  floorVersion: string | null = "33333333-3333-3333-3333-333333333333";
  venueVisible = true;
  down = false;
  verdict: "ACCEPTED" | "REJECTED" | "QUARANTINED" = "ACCEPTED";
  faults: Fault[] = [];
  log: string[] = [];
  /** Every Authorization header the server saw. */
  tokens = new Set<string>();
  /** Bodies of POST /floors/{f}/rescan and POST /floors, as sent. */
  rescanRequests: unknown[] = [];
  floorCreates: unknown[] = [];

  mediaCount(): number {
    return [...this.captures.values()].reduce((n, c) => n + c.media.size, 0);
  }

  private take(method: string, path: string): Fault | null {
    const f = this.faults.find((x) => {
      const [m, suffix] = x.match.split(" ");
      return x.times > 0 && m === method && path.endsWith(suffix);
    });
    if (f) f.times--;
    return f ?? null;
  }

  private json(route: Route, status: number, body: unknown) {
    return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  }

  private problem(route: Route, status: number, code: string, detail = code) {
    return route.fulfill({ status, contentType: "application/problem+json", body: JSON.stringify({ status, code, detail }) });
  }

  async install(page: Page) {
    await page.route("**/mock-api/api/v1/**", (route) => this.handle(route));
  }

  private view(c: CaptureRec) {
    return {
      id: c.id, venueId: VENUE, floorId: c.floorId, operatorId: "ops-user", status: c.status, device: {}, startedAt: c.startedAt,
      endedAt: null, durationSeconds: null, qualityState: "NOT_ASSESSED", failureCode: null, failureMessage: null,
      mediaCount: c.media.size, acceptedMediaCount: [...c.media.values()].filter((m) => m.status === "ACCEPTED").length,
      createdAt: c.startedAt,
    };
  }

  private mediaView(m: Media) {
    return {
      id: m.id, kind: m.kind, status: m.status, filename: m.filename, detectedContentType: null, sizeBytes: m.size, sha256: m.sha256,
      pixelWidth: null, pixelHeight: null, rejectionCode: m.rejectionCode, rejectionMessage: m.rejectionCode,
      totalParts: Math.ceil(m.size / PART), partSizeBytes: PART, uploadedParts: [...m.parts.keys()].sort((a, b) => a - b),
    };
  }

  private async handle(route: Route) {
    const req = route.request();
    const url = new URL(req.url());
    const path = url.pathname.replace(/^.*\/api\/v1/, "");
    const method = req.method();
    const auth = req.headers()["authorization"];
    if (auth) this.tokens.add(auth);
    if (this.down) return route.abort("internetdisconnected");
    const fault = this.take(method, path);
    if (fault?.kind === "abort") return route.abort("connectionreset");
    if (typeof fault?.kind === "number") return this.problem(route, fault.kind, fault.code ?? `HTTP_${fault.kind}`);
    this.log.push(`${method} ${path.replace(/[0-9a-f-]{36}/g, ":id")}`);

    const answer = async (status: number, body: unknown) => {
      if (fault?.kind === "lose-answer") return route.abort("connectionreset");
      return body === undefined ? route.fulfill({ status }) : this.json(route, status, body);
    };

    if (path === "/venues" && method === "GET") {
      return answer(200, this.venueVisible ? [{ id: VENUE, name: "Test Hall", slug: "test-hall" }] : []);
    }
    if (!this.venueVisible && path.startsWith(`/venues/${VENUE}`)) return this.problem(route, 404, "NOT_FOUND", "venue not found");
    if (path === `/venues/${VENUE}/floors` && method === "POST") {
      this.floorCreates.push(req.postDataJSON());
      return answer(201, { id: `cccccccc-0000-0000-0000-${String(this.floorCreates.length).padStart(12, "0")}`, ...(req.postDataJSON() as object) });
    }
    if (path === `/venues/${VENUE}/floors`) {
      return answer(200, [{ id: FLOOR, level: 0, name: "Ground", currentScanVersionId: this.floorVersion }]);
    }
    if (path === `/venues/${VENUE}/floors/${FLOOR}/rescan` && method === "POST") {
      const body = req.postDataJSON() as { parentVersionId: string };
      this.rescanRequests.push(body);
      if (body.parentVersionId !== this.floorVersion) return this.problem(route, 409, "PARENT_NOT_CURRENT");
      const id = `aaaaaaaa-0000-0000-0000-${String(this.captures.size + 1).padStart(12, "0")}`;
      this.captures.set(id, { id, clientSessionId: null, status: "CREATED", floorId: FLOOR, media: new Map(), startedAt: new Date().toISOString() });
      return answer(201, { captureId: id, scanVersionId: null, parentVersionId: body.parentVersionId, navigationRebuildRequired: true, regionAreaSquareMeters: 4.76 });
    }
    const base = `/venues/${VENUE}/captures`;
    if (path === base && method === "GET") return answer(200, [...this.captures.values()].map((c) => this.view(c)));
    if (path === base && method === "POST") {
      const body = req.postDataJSON() as { floorId?: string; clientSessionId?: string; expectedScanVersion?: { versionId: string | null }; startedAt: string };
      let c = [...this.captures.values()].find((x) => x.clientSessionId && x.clientSessionId === body.clientSessionId);
      if (c) return answer(200, this.view(c));
      if (body.expectedScanVersion && body.expectedScanVersion.versionId !== this.floorVersion) {
        return this.problem(route, 409, "SCAN_VERSION_CHANGED");
      }
      const id = `aaaaaaaa-0000-0000-0000-${String(this.captures.size + 1).padStart(12, "0")}`;
      c = { id, clientSessionId: body.clientSessionId ?? null, status: "CREATED", floorId: body.floorId ?? null, media: new Map(), startedAt: body.startedAt };
      this.captures.set(id, c);
      return answer(201, this.view(c));
    }
    const m = path.match(/^\/venues\/[^/]+\/captures\/([^/]+)(.*)$/);
    if (!m) return this.problem(route, 503, "NOT_MOCKED", `not mocked: ${method} ${path}`);
    const c = this.captures.get(m[1]);
    const rest = m[2];
    if (!c) return this.problem(route, 404, "NOT_FOUND", "capture not found");
    if (rest === "" && method === "GET") return answer(200, this.view(c));
    if (rest === "/media" && method === "GET") return answer(200, [...c.media.values()].map((x) => this.mediaView(x)));
    if (rest === "/media" && method === "POST") {
      if (!["CREATED", "UPLOADING"].includes(c.status)) return this.problem(route, 409, "CAPTURE_NOT_ACCEPTING_UPLOADS");
      const b = req.postDataJSON() as { kind: string; filename: string; sizeBytes: number; sha256: string; clientFileId?: string };
      let media = [...c.media.values()].find((x) => x.clientFileId && x.clientFileId === b.clientFileId);
      if (media) {
        return answer(200, { mediaId: media.id, partSizeBytes: PART, totalParts: Math.ceil(media.size / PART), existing: true, status: media.status });
      }
      const id = `bbbbbbbb-0000-0000-0000-${String(this.mediaCount() + 1).padStart(12, "0")}`;
      media = { id, clientFileId: b.clientFileId ?? null, sha256: b.sha256, size: b.sizeBytes, filename: b.filename, kind: b.kind,
        status: "PENDING", parts: new Map(), rejectionCode: null, polls: 0 };
      c.media.set(id, media);
      c.status = "UPLOADING";
      return answer(201, { mediaId: id, partSizeBytes: PART, totalParts: Math.ceil(b.sizeBytes / PART), existing: false, status: "PENDING" });
    }
    const mm = rest.match(/^\/media\/([^/]+)(.*)$/);
    const media = mm ? c.media.get(mm[1]) : undefined;
    if (!mm || !media) return this.problem(route, 404, "NOT_FOUND", "media not found");
    const sub = mm[2];
    if (sub === "" && method === "GET") {
      if (media.status === "VALIDATING" && ++media.polls >= 2) this.settle(media);
      return answer(200, this.mediaView(media));
    }
    const pm = sub.match(/^\/parts\/(\d+)$/);
    if (pm && method === "PUT") {
      const n = Number(pm[1]);
      const data = req.postDataBuffer() ?? Buffer.alloc(0);
      const expected = n * PART <= media.size ? PART : media.size - (n - 1) * PART;
      if (data.length !== expected) return this.problem(route, 400, "INVALID_PART_SIZE");
      const sha = req.headers()["x-part-sha256"];
      if (sha && sha !== createHash("sha256").update(data).digest("hex")) return this.problem(route, 422, "CHECKSUM_MISMATCH");
      media.parts.set(n, data);
      return answer(204, undefined);
    }
    if (sub === "/complete" && method === "POST") {
      if (media.parts.size !== Math.ceil(media.size / PART)) return this.problem(route, 409, "MISSING_PARTS");
      media.status = "VALIDATING";
      media.polls = 0;
      return answer(202, undefined);
    }
    return this.problem(route, 503, "NOT_MOCKED", `not mocked: ${method} ${path}`);
  }

  /** The server's own verification: size and SHA-256 of the assembled bytes, then the configured verdict. */
  private settle(media: Media) {
    const all = Buffer.concat([...media.parts.entries()].sort((a, b) => a[0] - b[0]).map(([, b]) => b));
    const ok = all.length === media.size && createHash("sha256").update(all).digest("hex") === media.sha256;
    media.status = ok ? this.verdict : "REJECTED";
    media.rejectionCode = !ok ? "CHECKSUM_MISMATCH" : this.verdict === "REJECTED" ? "MALWARE_DETECTED"
      : this.verdict === "QUARANTINED" ? "SCANNER_UNAVAILABLE" : null;
  }
}

/** An MP4-signature file of `size` bytes (a container header for type checks, not a playable video). */
export function mp4(size: number, seed = 1): Buffer {
  const ftyp = Buffer.from([0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x6d, 0x70, 0x34, 0x32, 0, 0, 0, 0, 0x6d, 0x70, 0x34, 0x32, 0x69, 0x73, 0x6f, 0x6d]);
  const b = Buffer.alloc(size);
  ftyp.copy(b);
  for (let i = ftyp.length; i < size; i++) b[i] = (i * seed) % 251;
  return b;
}
