// What the viewer checks before a downloaded artifact reaches GaussianSplats3D, and before a scene is shown at all.
// Everything fails closed: a mismatch is an error on screen, never a best-effort render. Pure (no DOM, no Next
// modules) so node --test runs it; lib/reconstruction-api.ts and components/ViewerWorkspace.tsx call it.

import { KsplatInvalidError, type KsplatInfo, checkKsplatSize, validateKsplat } from "./ksplat-validation.ts";

/** Mirrors dev.chaya.api.reconstruction.ReconstructionService.ArtifactFormat: what the API validated when it published
 * the artifact. Null for kinds it does not validate and for a KSPLAT published before that existed (V31). */
export type ArtifactFormat = KsplatInfo;

/** Mirrors dev.chaya.api.reconstruction.ReconstructionService.ArtifactRef. `url` is a path on this backend (never a raw
 * object-storage URL), so every download stays venue/organization scoped. sizeBytes and sha256 are what its bytes must be. */
export interface ArtifactRef {
  kind: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  url: string;
  format: ArtifactFormat | null;
}

export type IntegrityCode =
  | "SIZE_MISMATCH"
  | "CHECKSUM_MISMATCH"
  | "FORMAT_MISMATCH"
  | "INTEGRITY_UNAVAILABLE"
  | "OUT_OF_SCOPE";

export class ArtifactIntegrityError extends Error {
  readonly code: IntegrityCode;

  constructor(code: IntegrityCode, message: string) {
    super(message);
    this.name = "ArtifactIntegrityError";
    this.code = code;
  }
}

const SHA256 = /^[0-9a-f]{64}$/;

export async function sha256Hex(bytes: ArrayBuffer): Promise<string> {
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) {
    // Web Crypto exists only in secure contexts (https, localhost). Without it nothing can be verified: refuse.
    throw new ArtifactIntegrityError("INTEGRITY_UNAVAILABLE",
      "This page cannot verify the reconstruction's checksum (it is not served over HTTPS), so it will not load it.");
  }
  const digest = new Uint8Array(await subtle.digest("SHA-256", bytes));
  return Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Checks to run before downloading: the advertised size is one the contract allows. */
export function checkBeforeDownload(ref: ArtifactRef): void {
  if (!SHA256.test(ref.sha256)) {
    throw new ArtifactIntegrityError("CHECKSUM_MISMATCH", "The reconstruction has no valid registered checksum.");
  }
  if (ref.kind === "KSPLAT") checkKsplatSize(ref.sizeBytes);
}

/**
 * Reads a response body, refusing as soon as it exceeds `expectedBytes` (a server that sends more than it advertised is
 * cut off rather than buffered) and when it ends short of it.
 */
export async function readExactly(
  body: ReadableStream<Uint8Array>,
  expectedBytes: number,
  onProgress?: (loadedBytes: number) => void,
  signal?: AbortSignal,
): Promise<ArrayBuffer> {
  if (signal?.aborted) {
    throw new DOMException("The download was cancelled.", "AbortError");
  }
  const out = new Uint8Array(expectedBytes);
  const reader = body.getReader();
  let loaded = 0;
  try {
    for (;;) {
      if (signal?.aborted) {
        throw new DOMException("The download was cancelled.", "AbortError");
      }
      const { done, value } = await reader.read();
      if (done) break;
      if (loaded + value.byteLength > expectedBytes) {
        throw new ArtifactIntegrityError("SIZE_MISMATCH",
          `The reconstruction download is larger than its registered ${expectedBytes} bytes.`);
      }
      out.set(value, loaded);
      loaded += value.byteLength;
      onProgress?.(loaded);
    }
  } catch (e) {
    await reader.cancel().catch(() => {});
    throw e;
  }
  if (loaded !== expectedBytes) {
    throw new ArtifactIntegrityError("SIZE_MISMATCH",
      `The reconstruction download ended after ${loaded} of ${expectedBytes} bytes (truncated).`);
  }
  return out.buffer;
}

/**
 * Verifies downloaded bytes against the artifact's registration: exact size, SHA-256 and, for a KSPLAT, the viewer
 * contract, plus agreement with the format the API recorded when it published the file.
 */
export async function verifyArtifact(bytes: ArrayBuffer, ref: ArtifactRef): Promise<ArtifactFormat | null> {
  checkBeforeDownload(ref);
  if (bytes.byteLength !== ref.sizeBytes) {
    throw new ArtifactIntegrityError("SIZE_MISMATCH",
      `The reconstruction is ${bytes.byteLength} bytes but was registered as ${ref.sizeBytes}.`);
  }
  const actual = await sha256Hex(bytes);
  if (actual !== ref.sha256) {
    throw new ArtifactIntegrityError("CHECKSUM_MISMATCH",
      "The reconstruction's checksum does not match its registration; it may be corrupt or replaced, so it was not loaded.");
  }
  if (ref.kind !== "KSPLAT") return null;
  const info = validateKsplat(bytes);
  if (ref.format) {
    for (const key of Object.keys(info) as (keyof KsplatInfo)[]) {
      if (ref.format[key] !== info[key]) {
        throw new ArtifactIntegrityError("FORMAT_MISMATCH",
          `The reconstruction's ${key} is ${info[key]} but was published as ${ref.format[key]}.`);
      }
    }
  }
  return info;
}

export interface ArtifactManifestEntry {
  name: string;
  kind: string;
  checksum: { algorithm: string; value: string };
  sizeBytes: number;
  artifactVersion?: string;
  sourceScanVersion?: string | null;
  processingConfiguration?: Record<string, unknown>;
  createdAt?: string;
  format?: Record<string, unknown>;
}

export interface ArtifactManifest {
  manifestVersion: string;
  coordinateSpace: string;
  runId: string;
  scanId: string;
  sourceScanVersion?: string | null;
  workerVersion?: string;
  createdAt?: string;
  processingConfiguration?: Record<string, unknown>;
  artifacts: ArtifactManifestEntry[];
}

export const KNOWN_SYNTHETIC_SHA256 = new Set([
  "af64e4f4d2037e8c3c2949f41134921691f9b5b9e8fd36a7331a1b32607bf48d", // packages/contracts/fixtures/viewer-scene/scene.ksplat
  "082f2c7db95af8165dbdde2cb51699590e624d9b7fafeb6d67d8879de3847e9b", // packages/contracts/fixtures/ksplat/scene.ksplat
]);

/** Identifies whether a reconstruction is a synthetic demonstration fixture rather than a real venue twin. */
export function isSyntheticFixture(scene: {
  runQuality?: string | null;
  scanId?: string | null;
  artifacts?: { sha256?: string; url?: string }[];
}): boolean {
  if (scene.runQuality === "SYNTHETIC_DEMO" || scene.runQuality === "FIXTURE") return true;
  if (scene.scanId && (scene.scanId.toLowerCase().includes("synthetic") || scene.scanId.toLowerCase().includes("fixture"))) return true;
  if (scene.artifacts?.some((a) => (a.sha256 && KNOWN_SYNTHETIC_SHA256.has(a.sha256)) || a.url?.includes("viewer-scene"))) return true;
  return false;
}

/**
 * Validates the authoritative artifact manifest against the reconstruction request and the KSPLAT artifact.
 * Fails closed if the manifest is corrupted, out of scope, or records different artifact properties.
 */
export function verifyManifest(
  manifest: unknown,
  expected: { runId: string; scanId?: string; ksplatRef: ArtifactRef }
): ArtifactManifest {
  if (typeof manifest !== "object" || manifest === null) {
    throw new ArtifactIntegrityError("FORMAT_MISMATCH", "Artifact manifest is not a valid JSON object.");
  }
  const m = manifest as Partial<ArtifactManifest>;
  if (m.manifestVersion !== "1.0") {
    throw new ArtifactIntegrityError("FORMAT_MISMATCH", `Unsupported manifest version: ${m.manifestVersion}; expected 1.0.`);
  }
  if (m.coordinateSpace !== "RECONSTRUCTION") {
    throw new ArtifactIntegrityError("FORMAT_MISMATCH", `Manifest coordinateSpace must be RECONSTRUCTION, got: ${m.coordinateSpace}.`);
  }
  if (m.runId !== expected.runId) {
    throw new ArtifactIntegrityError("OUT_OF_SCOPE", `Manifest runId ${m.runId} does not match expected runId ${expected.runId}.`);
  }
  if (expected.scanId && m.scanId && m.scanId !== expected.scanId) {
    throw new ArtifactIntegrityError("OUT_OF_SCOPE", `Manifest scanId ${m.scanId} does not match expected scanId ${expected.scanId}.`);
  }
  if (!Array.isArray(m.artifacts)) {
    throw new ArtifactIntegrityError("FORMAT_MISMATCH", "Manifest artifacts list is missing or invalid.");
  }
  const ksplatEntry = m.artifacts.find((a) => a.kind === "KSPLAT" || a.name === "scene.ksplat");
  if (!ksplatEntry) {
    throw new ArtifactIntegrityError("FORMAT_MISMATCH", "Manifest does not contain an entry for KSPLAT.");
  }
  if (ksplatEntry.checksum?.value !== expected.ksplatRef.sha256) {
    throw new ArtifactIntegrityError(
      "CHECKSUM_MISMATCH",
      `Manifest recorded KSPLAT checksum ${ksplatEntry.checksum?.value} does not match artifact registration ${expected.ksplatRef.sha256}.`
    );
  }
  if (ksplatEntry.sizeBytes !== expected.ksplatRef.sizeBytes) {
    throw new ArtifactIntegrityError(
      "SIZE_MISMATCH",
      `Manifest recorded KSPLAT size ${ksplatEntry.sizeBytes} does not match artifact registration ${expected.ksplatRef.sizeBytes}.`
    );
  }
  return m as ArtifactManifest;
}

export { KsplatInvalidError };

// ---- scene scope: the version and coordinate frame the bytes are shown in -------------------------------------------------

interface ScopedScene {
  runId: string;
  floorId: string;
  scanVersionId: string | null;
  artifacts: ArtifactRef[];
  coordinateFrame: {
    floorId: string | null;
    canonical: boolean;
    scale: number | null;
    rotation: { x: number; y: number; z: number; w: number } | null;
    translation: { x: number; y: number; z: number } | null;
  } | null;
}

export interface SceneExpectation {
  venueId: string;
  runId: string;
  /** The scan version the viewer picked this run as (ReconstructionVersion.scanVersionId), or null if not a version. */
  scanVersionId: string | null;
}

function outOfScope(what: string): never {
  throw new ArtifactIntegrityError("OUT_OF_SCOPE", `The reconstruction does not match what was requested: ${what}.`);
}

/**
 * The scene the API returned is the one requested and is internally consistent, so its model is never shown with another
 * version's frame: same run, same scan version; a version's viewer asset is the one that version pinned (served from
 * /scan-versions/{id}/artifacts), a version always has its recorded frame, a frame belongs to the scene's floor and a
 * canonical frame is a finite similarity. Returns the KSPLAT to download.
 */
export function checkSceneScope(scene: ScopedScene, expected: SceneExpectation): ArtifactRef {
  if (scene.runId !== expected.runId) outOfScope(`run ${scene.runId} instead of ${expected.runId}`);
  const version = scene.scanVersionId ?? null;
  if (version !== (expected.scanVersionId ?? null)) {
    outOfScope(`scan version ${version ?? "none"} instead of ${expected.scanVersionId ?? "none"}`);
  }
  const ksplats = scene.artifacts.filter((a) => a.kind === "KSPLAT");
  if (ksplats.length !== 1) outOfScope(`${ksplats.length} viewer assets instead of one`);
  const ksplat = ksplats[0];
  const base = `/api/v1/venues/${expected.venueId}`;
  const url = version
    ? `${base}/scan-versions/${version}/artifacts/KSPLAT`
    : `${base}/reconstructions/${scene.runId}/artifacts/KSPLAT`;
  if (ksplat.url !== url) outOfScope(`its viewer asset is ${ksplat.url}, not ${url}`);

  const manifest = scene.artifacts.find((a) => a.kind === "ARTIFACT_MANIFEST");
  if (manifest) {
    const manifestUrl = version
      ? `${base}/scan-versions/${version}/artifacts/ARTIFACT_MANIFEST`
      : `${base}/reconstructions/${scene.runId}/artifacts/ARTIFACT_MANIFEST`;
    if (manifest.url !== manifestUrl) outOfScope(`its manifest is ${manifest.url}, not ${manifestUrl}`);
  }

  const frame = scene.coordinateFrame;
  if (version && !frame) outOfScope("a scan version without its recorded coordinate frame");
  if (frame) {
    if (frame.floorId !== null && frame.floorId !== scene.floorId) {
      outOfScope(`a coordinate frame of floor ${frame.floorId}, not ${scene.floorId}`);
    }
    if (frame.canonical) {
      const { scale, rotation: q, translation: t } = frame;
      const finite = (...v: number[]) => v.every(Number.isFinite);
      if (scale === null || !finite(scale) || scale <= 0) outOfScope("a canonical frame without a positive scale");
      if (!q || !finite(q.x, q.y, q.z, q.w) || Math.abs(Math.hypot(q.x, q.y, q.z, q.w) - 1) > 1e-3) {
        outOfScope("a canonical frame without a unit rotation");
      }
      if (!t || !finite(t.x, t.y, t.z)) outOfScope("a canonical frame without a finite translation");
    }
  }
  return ksplat;
}
