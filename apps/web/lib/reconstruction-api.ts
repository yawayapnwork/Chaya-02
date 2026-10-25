"use client";

import { ApiError, api } from "./capture-api";
import {
  type ArtifactFormat,
  type ArtifactManifest,
  type ArtifactRef,
  ArtifactIntegrityError,
  checkBeforeDownload,
  readExactly,
  verifyArtifact,
  verifyManifest,
} from "./artifact-integrity";
import type { CoordinateFrame } from "./coordinate-frame";
import { publicConfig } from "./env";
import { currentAuthHeaders } from "./session";

/** Mirrors dev.chaya.api.reconstruction.ReconstructionService.ReconstructionVersion: one FINALIZED scan version of the
 * floor (only those are listed). current: it is the version the floor publishes (floor.current_scan_version_id). */
export interface ReconstructionVersion {
  runId: string;
  floorId: string;
  generatedAt: string;
  runStatus: string;
  runQuality: string | null;
  scanVersionId: string | null;
  versionNumber: number | null;
  parentVersionId: string | null;
  current: boolean;
  /** FLOOR, RESCAN, STITCHED (rooms combined) or ROOM: one room of the floor (roomName), viewable on its own and never
   * what the floor publishes (docs/multi-room.md). Absent from APIs before V41. */
  kind?: "FLOOR" | "RESCAN" | "STITCHED" | "ROOM";
  roomId?: string | null;
  roomName?: string | null;
}

export type { ArtifactFormat, ArtifactManifest, ArtifactRef } from "./artifact-integrity";

/** Mirrors dev.chaya.api.reconstruction.ReconstructionService.Reconstruction. The artifacts are in the
 * reconstruction's own frame; `coordinateFrame` (null until calibrated) is how they are placed in canonical metres.
 * For a finalized scan version (scanVersionId set) the artifacts are exactly the ones that version pinned and the frame
 * is the one it recorded; its POIs, routes and search results must then be requested for that version too. */
export interface Reconstruction {
  runId: string;
  scanId: string;
  floorId: string;
  generatedAt: string;
  runStatus: string;
  runQuality: string | null;
  artifacts: ArtifactRef[];
  coordinateFrame: CoordinateFrame | null;
  scanVersionId: string | null;
  versionNumber: number | null;
  parentVersionId: string | null;
}

export const listReconstructions = (venueId: string, floorId: string) =>
  api<ReconstructionVersion[]>(`/venues/${venueId}/floors/${floorId}/reconstructions`);

export const getLatestReconstruction = (venueId: string, floorId: string) =>
  api<Reconstruction>(`/venues/${venueId}/floors/${floorId}/reconstructions/latest`);

export const getReconstruction = (venueId: string, runId: string) =>
  api<Reconstruction>(`/venues/${venueId}/reconstructions/${runId}`);

export const getVersionReconstruction = (venueId: string, scanVersionId: string) =>
  api<Reconstruction>(`/venues/${venueId}/scan-versions/${scanVersionId}/reconstruction`);

export interface PublicViewerToken {
  token: string;
  expiresAt: string;
  venueId: string;
}

/** Unauthenticated: trades a public viewer link secret for a short-lived, venue-scoped token. */
export async function exchangePublicLink(secret: string): Promise<PublicViewerToken> {
  const res = await fetch(`${publicConfig().apiBaseUrl}/api/v1/public/viewer-token`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ secret }),
    signal: AbortSignal.timeout(10000),
  });
  if (!res.ok) throw new ApiError(res.status, "LINK_INVALID", "This viewing link is invalid, expired or has been revoked.");
  return (await res.json()) as PublicViewerToken;
}

/**
 * Downloads an artifact (e.g. the .ksplat) with the caller's credentials (bearer or public-viewer token), reporting real
 * progress from the stream, and returns its bytes only once they are verified: exactly the registered size (a larger
 * download is cut off, a shorter one refused), the registered SHA-256, and for a KSPLAT the viewer contract and the
 * format the API recorded (lib/artifact-integrity). Retries up to maxRetries on transient network/streaming failures.
 * Supports cancellation via AbortSignal. Anything else throws -- the viewer never hands unverified bytes to GaussianSplats3D.
 */
export async function fetchArtifact(
  ref: ArtifactRef,
  onProgress?: (loadedBytes: number, totalBytes: number) => void,
  signal?: AbortSignal,
  maxRetries = 2,
): Promise<Blob> {
  checkBeforeDownload(ref);
  let lastError: unknown = null;
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    if (signal?.aborted) {
      throw new DOMException("The download was cancelled.", "AbortError");
    }
    try {
      const res = await fetch(`${publicConfig().apiBaseUrl}${ref.url}`, {
        headers: await currentAuthHeaders(),
        signal,
      });
      if (!res.ok) {
        const isRetryable = res.status >= 500 || res.status === 408 || res.status === 429;
        if (isRetryable && attempt < maxRetries) {
          await new Promise((r) => setTimeout(r, 200 * Math.pow(2, attempt)));
          continue;
        }
        throw new ApiError(res.status, "ARTIFACT_FETCH_FAILED", `Could not load the reconstruction asset (HTTP ${res.status}).`);
      }
      const length = res.headers.get("Content-Encoding") ? null : res.headers.get("Content-Length");
      if (length !== null && Number(length) !== ref.sizeBytes) {
        await res.body?.cancel().catch(() => {});
        throw new ArtifactIntegrityError("SIZE_MISMATCH",
          `The server is sending ${length} bytes but the reconstruction is registered as ${ref.sizeBytes}.`);
      }
      if (!res.body) throw new ArtifactIntegrityError("SIZE_MISMATCH", "The reconstruction download has no body.");
      const bytes = await readExactly(res.body, ref.sizeBytes, (loaded) => onProgress?.(loaded, ref.sizeBytes), signal);
      await verifyArtifact(bytes, ref);
      return new Blob([bytes], { type: "application/octet-stream" });
    } catch (e) {
      if (signal?.aborted) throw e;
      if (e instanceof ArtifactIntegrityError && (e.code === "SIZE_MISMATCH" || e.code === "CHECKSUM_MISMATCH") && attempt < maxRetries) {
        lastError = e;
        await new Promise((r) => setTimeout(r, 200 * Math.pow(2, attempt)));
        continue;
      }
      if (e instanceof TypeError && attempt < maxRetries) {
        lastError = e;
        await new Promise((r) => setTimeout(r, 200 * Math.pow(2, attempt)));
        continue;
      }
      throw e;
    }
  }
  throw lastError ?? new Error("Download failed after bounded retries.");
}

/** Fetches and validates the authoritative artifact manifest against the scene registration. */
export async function fetchAndVerifyManifest(
  manifestRef: ArtifactRef,
  expected: { runId: string; scanId?: string; ksplatRef: ArtifactRef },
  signal?: AbortSignal,
): Promise<ArtifactManifest> {
  const blob = await fetchArtifact(manifestRef, undefined, signal);
  const text = await blob.text();
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch (e) {
    throw new ArtifactIntegrityError("FORMAT_MISMATCH", `Artifact manifest is not valid JSON: ${e instanceof Error ? e.message : String(e)}`);
  }
  return verifyManifest(json, expected);
}
