"use client";

import { createSHA256 } from "hash-wasm";
import { ApiError, completeMedia, getMedia, initMedia, putPart } from "./capture-api";
import { kindForType, normalizeType, planParts } from "./upload-plan";

export type Phase = "hashing" | "uploading" | "validating" | "accepted" | "rejected" | "quarantined" | "error";

export interface UploadState {
  phase: Phase;
  /** 0..1 within the current phase (hashing or uploading). */
  progress: number;
  message?: string;
  mediaId?: string;
}

const RETRIES = 3;

/** Streaming SHA-256 so multi-GB videos are never held in memory. */
export async function sha256Of(blob: Blob, onProgress: (fraction: number) => void): Promise<string> {
  const hasher = await createSHA256();
  hasher.init();
  const reader = blob.stream().getReader();
  let done = 0;
  for (;;) {
    const { value, done: finished } = await reader.read();
    if (finished) break;
    hasher.update(value);
    done += value.byteLength;
    onProgress(blob.size === 0 ? 1 : done / blob.size);
  }
  return hasher.digest("hex");
}

async function sha256OfPart(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * Uploads one file: hash, open the upload, send parts (retrying transient failures; parts that the
 * server already has are skipped, which also resumes an interrupted upload), then wait for the
 * server-side validation verdict.
 */
export async function uploadFile(
  venueId: string,
  captureId: string,
  file: File,
  update: (s: UploadState) => void,
  resumeMediaId?: string,
): Promise<void> {
  const kind = kindForType(file.type);
  if (!kind) {
    update({ phase: "error", progress: 0, message: `${file.name}: unsupported type` });
    return;
  }
  let mediaId = resumeMediaId;
  try {
    let partSizeBytes: number;
    if (mediaId) {
      // Resume: the server already knows the file; ask which parts it holds.
      partSizeBytes = (await getMedia(venueId, captureId, mediaId)).partSizeBytes;
    } else {
      update({ phase: "hashing", progress: 0 });
      const sha256 = await sha256Of(file, (p) => update({ phase: "hashing", progress: p }));
      const init = await initMedia(venueId, captureId, {
        kind,
        filename: file.name,
        contentType: normalizeType(file.type),
        sizeBytes: file.size,
        sha256,
      });
      mediaId = init.mediaId;
      partSizeBytes = init.partSizeBytes;
    }
    const id = mediaId;
    const parts = planParts(file.size, partSizeBytes);
    const have = new Set((await getMedia(venueId, captureId, id)).uploadedParts);

    let uploaded = parts.filter((p) => have.has(p.partNumber)).reduce((n, p) => n + (p.end - p.start), 0);
    update({ phase: "uploading", progress: uploaded / file.size, mediaId: id });

    for (const part of parts) {
      if (have.has(part.partNumber)) continue;
      const blob = file.slice(part.start, part.end);
      const partSha = await sha256OfPart(blob);
      for (let attempt = 1; ; attempt++) {
        try {
          await putPart(venueId, captureId, id, part.partNumber, blob, partSha, (loaded) =>
            update({ phase: "uploading", progress: (uploaded + loaded) / file.size, mediaId: id }),
          );
          break;
        } catch (e) {
          const transient = e instanceof ApiError && (e.status === 0 || e.status >= 500 || e.code === "CHECKSUM_MISMATCH");
          if (!transient || attempt >= RETRIES) throw e;
          await sleep(500 * 2 ** attempt);
        }
      }
      uploaded += part.end - part.start;
      update({ phase: "uploading", progress: uploaded / file.size, mediaId: id });
    }

    await completeMedia(venueId, captureId, id);
    await awaitVerdict(venueId, captureId, id, update);
  } catch (e) {
    // mediaId is kept so the operator can resume from the parts the server already has.
    update({ phase: "error", progress: 0, mediaId, message: e instanceof ApiError ? e.message : String(e) });
  }
}

/** Retry validation of a quarantined file (for example after the malware scanner came back). */
export async function retryValidation(
  venueId: string,
  captureId: string,
  mediaId: string,
  update: (s: UploadState) => void,
): Promise<void> {
  try {
    await completeMedia(venueId, captureId, mediaId);
    await awaitVerdict(venueId, captureId, mediaId, update);
  } catch (e) {
    update({ phase: "error", progress: 0, message: e instanceof ApiError ? e.message : String(e), mediaId });
  }
}

async function awaitVerdict(venueId: string, captureId: string, mediaId: string, update: (s: UploadState) => void) {
  update({ phase: "validating", progress: 1, mediaId });
  for (;;) {
    const m = await getMedia(venueId, captureId, mediaId);
    if (m.status === "ACCEPTED") return update({ phase: "accepted", progress: 1, mediaId });
    if (m.status === "REJECTED") return update({ phase: "rejected", progress: 1, mediaId, message: m.rejectionCode ?? m.rejectionMessage ?? "rejected" });
    if (m.status === "QUARANTINED") return update({ phase: "quarantined", progress: 1, mediaId, message: m.rejectionCode ?? "SCANNER_UNAVAILABLE" });
    await sleep(1000);
  }
}
