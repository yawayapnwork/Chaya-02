// IndexedDB storage for the capture upload queue (lib/capture-queue.ts). IndexedDB is the browser's durable, origin-scoped
// store for structured data and Blobs: queued sessions, files and media bytes survive a reload or a browser restart. No
// SQLite, no localStorage, and never a token: the stores hold capture metadata and media only.
//
//   sessions  LocalSession, keyed by localId, indexed by ownerKey (an account only ever reads its own)
//   files     LocalFile, keyed by localId, indexed by sessionLocalId
//   blobs     { localId, blob }: the media bytes, deleted as soon as the server has accepted the file
//
// A browser may still evict this storage under pressure unless persistent storage was granted (storageStatus says which).

import type { LocalFile, LocalSession, QueueStore } from "./capture-queue";

const DB_NAME = "chaya-capture-queue";
const DB_VERSION = 1;

function request<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

function done(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new DOMException("transaction aborted", "AbortError"));
  });
}

export function openQueueDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open(DB_NAME, DB_VERSION);
    open.onupgradeneeded = () => {
      const db = open.result;
      db.createObjectStore("sessions", { keyPath: "localId" }).createIndex("ownerKey", "ownerKey");
      db.createObjectStore("files", { keyPath: "localId" }).createIndex("sessionLocalId", "sessionLocalId");
      db.createObjectStore("blobs", { keyPath: "localId" });
    };
    open.onsuccess = () => resolve(open.result);
    open.onerror = () => reject(open.error);
    open.onblocked = () => reject(new Error("The capture queue database is open in an older version of this page; close other tabs."));
  });
}

export class IdbQueueStore implements QueueStore {
  private readonly db: Promise<IDBDatabase>;

  constructor(db: Promise<IDBDatabase> = openQueueDb()) {
    this.db = db;
  }

  private async read<T>(store: string, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    const tx = (await this.db).transaction(store, "readonly");
    return request(fn(tx.objectStore(store)));
  }

  private async write(store: string, fn: (s: IDBObjectStore) => void): Promise<void> {
    const tx = (await this.db).transaction(store, "readwrite");
    fn(tx.objectStore(store));
    await done(tx);
  }

  sessions(ownerKey: string): Promise<LocalSession[]> {
    return this.read("sessions", (s) => s.index("ownerKey").getAll(ownerKey) as IDBRequest<LocalSession[]>);
  }

  sessionCount(): Promise<number> {
    return this.read("sessions", (s) => s.count());
  }

  putSession(x: LocalSession): Promise<void> {
    return this.write("sessions", (s) => s.put(x));
  }

  deleteSession(localId: string): Promise<void> {
    return this.write("sessions", (s) => s.delete(localId));
  }

  files(sessionLocalId: string): Promise<LocalFile[]> {
    return this.read("files", (s) => s.index("sessionLocalId").getAll(sessionLocalId) as IDBRequest<LocalFile[]>);
  }

  putFile(x: LocalFile): Promise<void> {
    return this.write("files", (s) => s.put(x));
  }

  deleteFile(localId: string): Promise<void> {
    return this.write("files", (s) => s.delete(localId));
  }

  /** Rejects with the browser's QuotaExceededError when the device will not store the bytes. */
  putBlob(localId: string, blob: Blob): Promise<void> {
    return this.write("blobs", (s) => s.put({ localId, blob }));
  }

  async getBlob(localId: string): Promise<Blob | null> {
    const row = await this.read("blobs", (s) => s.get(localId) as IDBRequest<{ blob: Blob } | undefined>);
    return row?.blob ?? null;
  }

  deleteBlob(localId: string): Promise<void> {
    return this.write("blobs", (s) => s.delete(localId));
  }
}

export interface StorageStatus {
  /** Bytes this origin uses and may use (browser estimates), or null when the browser does not say. */
  usageBytes: number | null;
  quotaBytes: number | null;
  /** Whether the browser promised not to evict this origin's storage under pressure. */
  persisted: boolean | null;
}

export async function storageStatus(): Promise<StorageStatus> {
  const s = typeof navigator !== "undefined" ? navigator.storage : undefined;
  if (!s) return { usageBytes: null, quotaBytes: null, persisted: null };
  const [estimate, persisted] = await Promise.all([
    s.estimate ? s.estimate().catch(() => null) : Promise.resolve(null),
    s.persisted ? s.persisted().catch(() => null) : Promise.resolve(null),
  ]);
  return { usageBytes: estimate?.usage ?? null, quotaBytes: estimate?.quota ?? null, persisted };
}

/** Asks the browser to keep this origin's storage (it may refuse, or ask the user). */
export async function requestPersistentStorage(): Promise<boolean | null> {
  const s = typeof navigator !== "undefined" ? navigator.storage : undefined;
  if (!s?.persist) return null;
  try {
    return await s.persist();
  } catch {
    return null;
  }
}

export async function freeBytes(): Promise<number | null> {
  const st = await storageStatus();
  return st.quotaBytes === null || st.usageBytes === null ? null : Math.max(0, st.quotaBytes - st.usageBytes);
}
