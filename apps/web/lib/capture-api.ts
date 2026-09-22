"use client";

import { accessToken } from "./auth";
import { publicConfig } from "./env";
import { currentAuthToken } from "./session";
import type { MediaKind } from "./upload-plan";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export interface Venue { id: string; name: string; slug: string }
export interface Floor { id: string; level: number; name: string }
export interface Capture {
  id: string;
  venueId: string;
  floorId: string | null;
  status: string;
  startedAt: string;
  endedAt: string | null;
  durationSeconds: number | null;
  qualityState: string;
  failureCode: string | null;
  failureMessage: string | null;
  mediaCount: number;
  acceptedMediaCount: number;
}
export interface MediaItem {
  id: string;
  kind: MediaKind;
  status: "PENDING" | "VALIDATING" | "ACCEPTED" | "REJECTED" | "QUARANTINED";
  filename: string;
  detectedContentType: string | null;
  sizeBytes: number;
  rejectionCode: string | null;
  rejectionMessage: string | null;
  totalParts: number;
  partSizeBytes: number;
  uploadedParts: number[];
}
export interface InitResult { mediaId: string; partSizeBytes: number; totalParts: number }
export interface JobItem {
  id: string;
  stage: string;
  status: string;
  retryCount: number;
  errorCode: string | null;
  errorMessage: string | null;
}
export interface ArtifactView {
  id: string;
  kind: string;
  bucket: string;
  key: string;
  sizeBytes: number;
  sha256: string;
  partial: boolean;
  containsPii: boolean;
}
export interface StageRunView {
  attempt: number;
  status: "SUCCEEDED" | "FAILED";
  startedAt: string;
  finishedAt: string;
  exitStatus: number | null;
  errorCode: string | null;
  errorMessage: string | null;
  errorDetails: Record<string, unknown> | null;
  outputSha256: string | null;
  workerId: string | null;
  command: Record<string, unknown>;
  stdout: ArtifactView | null;
  stderr: ArtifactView | null;
  artifacts: ArtifactView[];
}
export interface StageView {
  stage: string;
  state: "PENDING" | "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "NOT_RUN";
  attempts: number;
  lastRun: StageRunView | null;
}
/** quality is FINAL only for a fully completed run and PARTIAL only for an explicitly time-boxed result. */
export interface RunView {
  id: string;
  status: "RUNNING" | "SUCCEEDED" | "PARTIAL" | "FAILED" | "CANCELLED";
  quality: "FINAL" | "PARTIAL" | null;
  privacyEnabled: boolean;
  timeBudgetSeconds: number;
  startedAt: string;
  deadlineAt: string;
  finishedAt: string | null;
  failureStage: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  retryable: boolean;
  stages: StageView[];
}
export interface ProcessingStatus {
  captureId: string;
  captureStatus: string;
  scanId: string | null;
  run: RunView | null;
  jobs: JobItem[];
}

async function parseError(res: Response): Promise<ApiError> {
  let code = "HTTP_" + res.status;
  let detail = `${res.status} ${res.statusText}`;
  try {
    const body = await res.json();
    if (body.code) code = body.code;
    if (body.detail) detail = body.detail;
  } catch {
    /* non-JSON error body */
  }
  return new ApiError(res.status, code, detail);
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = await currentAuthToken();
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${token}`);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  const res = await fetch(`${publicConfig().apiBaseUrl}/api/v1${path}`, { ...init, headers });
  if (!res.ok) throw await parseError(res);
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const listVenues = () => api<Venue[]>("/venues");
export const listFloors = (venueId: string) => api<Floor[]>(`/venues/${venueId}/floors`);

export const createCapture = (venueId: string, body: { floorId?: string; device: Record<string, unknown>; startedAt: string }) =>
  api<Capture>(`/venues/${venueId}/captures`, { method: "POST", body: JSON.stringify(body) });

export const getCapture = (venueId: string, captureId: string) =>
  api<Capture>(`/venues/${venueId}/captures/${captureId}`);

export const initMedia = (
  venueId: string,
  captureId: string,
  body: { kind: MediaKind; filename: string; contentType: string; sizeBytes: number; sha256: string },
) => api<InitResult>(`/venues/${venueId}/captures/${captureId}/media`, { method: "POST", body: JSON.stringify(body) });

export const getMedia = (venueId: string, captureId: string, mediaId: string) =>
  api<MediaItem>(`/venues/${venueId}/captures/${captureId}/media/${mediaId}`);

export const completeMedia = (venueId: string, captureId: string, mediaId: string) =>
  api<void>(`/venues/${venueId}/captures/${captureId}/media/${mediaId}/complete`, { method: "POST" });

export const completeUpload = (venueId: string, captureId: string, endedAt: string) =>
  api<Capture>(`/venues/${venueId}/captures/${captureId}/complete-upload`, { method: "POST", body: JSON.stringify({ endedAt }) });

export const startProcessing = (venueId: string, captureId: string, options: { timeBudgetSeconds?: number } = {}) =>
  api<ProcessingStatus>(`/venues/${venueId}/captures/${captureId}/processing`, { method: "POST", body: JSON.stringify(options) });

export const retryProcessing = (venueId: string, captureId: string) =>
  api<ProcessingStatus>(`/venues/${venueId}/captures/${captureId}/processing/retry`, { method: "POST" });

export const cancelProcessing = (venueId: string, captureId: string) =>
  api<ProcessingStatus>(`/venues/${venueId}/captures/${captureId}/processing/cancel`, { method: "POST" });

export const getProcessing = (venueId: string, captureId: string) =>
  api<ProcessingStatus>(`/venues/${venueId}/captures/${captureId}/processing`);

/** Sends one part with upload progress (fetch cannot report upload progress, XHR can). */
export async function putPart(
  venueId: string,
  captureId: string,
  mediaId: string,
  partNumber: number,
  data: Blob,
  sha256: string,
  onProgress: (loadedBytes: number) => void,
): Promise<void> {
  const token = await accessToken();
  const url = `${publicConfig().apiBaseUrl}/api/v1/venues/${venueId}/captures/${captureId}/media/${mediaId}/parts/${partNumber}`;
  await new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", url);
    xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    xhr.setRequestHeader("Content-Type", "application/octet-stream");
    xhr.setRequestHeader("X-Part-Sha256", sha256);
    xhr.upload.onprogress = (e) => onProgress(e.loaded);
    xhr.onerror = () => reject(new ApiError(0, "NETWORK", "Network error while uploading. The upload can be resumed."));
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) return resolve();
      let code = "HTTP_" + xhr.status;
      let detail = `${xhr.status} ${xhr.statusText}`;
      try {
        const body = JSON.parse(xhr.responseText);
        code = body.code ?? code;
        detail = body.detail ?? detail;
      } catch {
        /* non-JSON error body */
      }
      reject(new ApiError(xhr.status, code, detail));
    };
    xhr.send(data);
  });
}
