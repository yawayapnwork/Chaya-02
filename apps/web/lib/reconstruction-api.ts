"use client";

import { ApiError, api } from "./capture-api";
import type { CoordinateFrame } from "./coordinate-frame";
import { publicConfig } from "./env";
import { currentAuthHeaders } from "./session";

/** Mirrors dev.chaya.api.reconstruction.ReconstructionService.ReconstructionVersion. */
export interface ReconstructionVersion {
  runId: string;
  floorId: string;
  generatedAt: string;
  runStatus: string;
  runQuality: string | null;
}

/** Mirrors dev.chaya.api.reconstruction.ReconstructionService.ArtifactRef. `url` is a path on this
 * backend (never a raw object-storage URL), so every download stays venue/organization scoped. */
export interface ArtifactRef {
  kind: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  url: string;
}

/** Mirrors dev.chaya.api.reconstruction.ReconstructionService.Reconstruction. The artifacts are in the
 * reconstruction's own frame; `coordinateFrame` (null until calibrated) is how they are placed in canonical metres. */
export interface Reconstruction {
  runId: string;
  scanId: string;
  floorId: string;
  generatedAt: string;
  runStatus: string;
  runQuality: string | null;
  artifacts: ArtifactRef[];
  coordinateFrame: CoordinateFrame | null;
}

export const listReconstructions = (venueId: string, floorId: string) =>
  api<ReconstructionVersion[]>(`/venues/${venueId}/floors/${floorId}/reconstructions`);

export const getLatestReconstruction = (venueId: string, floorId: string) =>
  api<Reconstruction>(`/venues/${venueId}/floors/${floorId}/reconstructions/latest`);

export const getReconstruction = (venueId: string, runId: string) =>
  api<Reconstruction>(`/venues/${venueId}/reconstructions/${runId}`);

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
 * Downloads an artifact `url` (as returned in ArtifactRef, e.g. the .ksplat) with the caller's
 * credentials (bearer or public-viewer token) and reports real download progress from the stream, so the viewer can show it without guessing.
 * Returns a Blob; the caller turns it into an object URL for the GaussianSplats3D loader, since that
 * loader fetches the path itself and cannot be handed an Authorization header directly.
 */
export async function fetchArtifact(url: string, onProgress?: (loadedBytes: number, totalBytes: number | null) => void): Promise<Blob> {
  const res = await fetch(`${publicConfig().apiBaseUrl}${url}`, { headers: await currentAuthHeaders() });
  if (!res.ok) throw new ApiError(res.status, "ARTIFACT_FETCH_FAILED", `Could not load the reconstruction asset (HTTP ${res.status}).`);
  const contentType = res.headers.get("Content-Type") ?? "application/octet-stream";
  const totalHeader = res.headers.get("Content-Length");
  const total = totalHeader ? Number(totalHeader) : null;
  if (!res.body || !onProgress) return res.blob();

  const reader = res.body.getReader();
  const chunks: Uint8Array[] = [];
  let loaded = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    loaded += value.byteLength;
    onProgress(loaded, total);
  }
  return new Blob(chunks as BlobPart[], { type: contentType });
}
