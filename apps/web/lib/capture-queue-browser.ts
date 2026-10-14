"use client";

// The capture upload queue as the browser runs it: lib/capture-queue.ts with the IndexedDB store, the real capture API,
// the signed-in account as owner, and a scheduler. One tab synchronises at a time (Web Locks, where supported); the server
// deduplicates anyway (V38), so two tabs can never publish a file twice.

import { userManager } from "./auth";
import { completeMedia, createCapture, getCapture, getMedia, initMedia, listVenues, putPart } from "./capture-api";
import { CaptureQueue, type QueueApi } from "./capture-queue";
import { IdbQueueStore, freeBytes } from "./capture-queue-idb";
import { sha256Of } from "./uploader";
import type { MediaKind } from "./upload-plan";

let owner: string | null = null;
let queue: CaptureQueue | null = null;
let store: IdbQueueStore | null = null;
let timer: ReturnType<typeof setTimeout> | null = null;

const api: QueueApi = {
  createCapture: (venueId, body) => createCapture(venueId, body),
  getCapture: (venueId, id) => getCapture(venueId, id),
  listVenues: () => listVenues(),
  initMedia: (venueId, captureId, body) => initMedia(venueId, captureId, { ...body, kind: body.kind as MediaKind }),
  getMedia: (venueId, captureId, mediaId) => getMedia(venueId, captureId, mediaId),
  putPart: (venueId, captureId, mediaId, n, data, sha, onProgress) => putPart(venueId, captureId, mediaId, n, data, sha, onProgress),
  completeMedia: (venueId, captureId, mediaId) => completeMedia(venueId, captureId, mediaId),
};

export function queueStore(): IdbQueueStore {
  store ??= new IdbQueueStore();
  return store;
}

/** The queue for this page. Data belongs to the account set with setQueueOwner; without one nothing is listed or sent. */
export function captureQueue(): CaptureQueue {
  if (!queue) {
    queue = new CaptureQueue({
      store: queueStore(),
      api,
      ownerKey: () => owner,
      sha256: (blob, onProgress) => sha256Of(blob, onProgress ?? (() => {})),
      now: () => Date.now(),
      online: () => navigator.onLine,
      freeBytes,
      sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
    });
    window.addEventListener("online", () => void syncNow());
  }
  return queue;
}

/** The owner key of the signed-in account (issuer and subject from the ID token), or null. */
export async function currentOwnerKey(): Promise<string | null> {
  const user = await userManager().getUser();
  const p = user?.profile;
  return p?.sub ? `${p.iss ?? ""}|${p.sub}` : null;
}

export function setQueueOwner(key: string | null): void {
  owner = key;
}

/** Runs a synchronisation pass now (if this tab holds the sync lock), then schedules the next one that is due. */
export async function syncNow(): Promise<void> {
  const q = captureQueue();
  const run = () => q.sync();
  const locks = (navigator as Navigator & { locks?: LockManager }).locks;
  if (locks) {
    await locks.request("chaya-capture-sync", { ifAvailable: true }, async (lock) => {
      if (lock) await run();
    });
  } else {
    await run();
  }
  if (timer) clearTimeout(timer);
  const due = await q.nextDueAtMs();
  if (due !== null) timer = setTimeout(() => void syncNow(), Math.max(250, due - Date.now()));
}
