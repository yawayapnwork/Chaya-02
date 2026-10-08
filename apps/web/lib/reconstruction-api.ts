"use client";

import { ApiError, api } from "./capture-api";
import { type ArtifactRef, ArtifactIntegrityError, checkBeforeDownload, readExactly, verifyArtifact } from "./artifact-integrity";
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
}

export type { ArtifactFormat, ArtifactRef } from "./artifact-integrity";

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
 * format the API recorded (lib/artifact-integrity). Anything else throws -- the viewer never hands unverified bytes to
 * GaussianSplats3D. The caller turns the Blob into an object URL, since that loader fetches the path itself and cannot
 * be handed an Authorization header.
 */
export async function fetchArtifact(ref: ArtifactRef, onProgress?: (loadedBytes: number, totalBytes: number) => void): Promise<Blob> {
  checkBeforeDownload(ref);
  const res = await fetch(`${publicConfig().apiBaseUrl}${ref.url}`, { headers: await currentAuthHeaders() });
  if (!res.ok) throw new ApiError(res.status, "ARTIFACT_FETCH_FAILED", `Could not load the reconstruction asset (HTTP ${res.status}).`);
  const length = res.headers.get("Content-Encoding") ? null : res.headers.get("Content-Length"); // encoded: of the encoding
  if (length !== null && Number(length) !== ref.sizeBytes) {
    await res.body?.cancel().catch(() => {});
    throw new ArtifactIntegrityError("SIZE_MISMATCH",
      `The server is sending ${length} bytes but the reconstruction is registered as ${ref.sizeBytes}.`);
  }
  if (!res.body) throw new ArtifactIntegrityError("SIZE_MISMATCH", "The reconstruction download has no body.");
  const bytes = await readExactly(res.body, ref.sizeBytes, (loaded) => onProgress?.(loaded, ref.sizeBytes));
  await verifyArtifact(bytes, ref);
  return new Blob([bytes], { type: "application/octet-stream" });
}
