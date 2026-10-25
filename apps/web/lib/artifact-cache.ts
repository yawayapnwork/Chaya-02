// Verified viewer artifacts kept in memory, so the viewer does not download the same model again when the user switches
// back to a version, retries after a graphics failure, or the component remounts. Pure (no DOM), so it is unit-tested.
//
// Entries are keyed by the artifact's registered SHA-256 and size: only bytes that passed lib/artifact-integrity's
// verification are stored (the loader resolves only after verifying), and content addressing means a cached entry is
// exactly the artifact any reference with the same digest names. Concurrent requests for one artifact share a single
// download, and each gets its progress. A failed download is never cached. The cache is bounded by entry count and by
// total bytes, least recently used first out; it lives as long as the page, so a different session (sign-in, another
// viewing link) starts with an empty one.

export interface CacheableRef {
  sha256: string;
  sizeBytes: number;
}

type Progress = (loaded: number, total: number) => void;

interface InFlight<T> {
  promise: Promise<T>;
  listeners: Set<Progress>;
}

export class ArtifactCache<T extends { size: number } = Blob> {
  private readonly entries = new Map<string, T>(); // insertion order = recency (oldest first)
  private readonly inFlight = new Map<string, InFlight<T>>();

  private readonly maxEntries: number;
  private readonly maxBytes: number;

  constructor(maxEntries = 3, maxBytes = 768 * 1024 * 1024) {
    this.maxEntries = maxEntries;
    this.maxBytes = maxBytes;
  }

  private static key(ref: CacheableRef): string {
    return `${ref.sha256}:${ref.sizeBytes}`;
  }

  has(ref: CacheableRef): boolean {
    return this.entries.has(ArtifactCache.key(ref));
  }

  get size(): number {
    return this.entries.size;
  }

  /** The cached artifact, or the result of `load` (shared with any concurrent request for the same artifact). */
  get(
    ref: CacheableRef,
    load: (onProgress: Progress, signal?: AbortSignal) => Promise<T>,
    onProgress?: Progress,
    signal?: AbortSignal,
  ): Promise<T> {
    if (signal?.aborted) {
      return Promise.reject(new DOMException("The download was cancelled.", "AbortError"));
    }
    const key = ArtifactCache.key(ref);
    const hit = this.entries.get(key);
    if (hit) {
      this.entries.delete(key); // refresh recency
      this.entries.set(key, hit);
      onProgress?.(ref.sizeBytes, ref.sizeBytes);
      return Promise.resolve(hit);
    }
    const pending = this.inFlight.get(key);
    if (pending) {
      if (onProgress) pending.listeners.add(onProgress);
      if (signal) {
        signal.addEventListener("abort", () => {
          if (onProgress) pending.listeners.delete(onProgress);
        }, { once: true });
      }
      return pending.promise;
    }
    const listeners = new Set<Progress>(onProgress ? [onProgress] : []);
    const promise = load((loaded, total) => listeners.forEach((l) => l(loaded, total)), signal).then(
      (value) => {
        this.inFlight.delete(key);
        this.store(key, value);
        return value;
      },
      (e: unknown) => {
        this.inFlight.delete(key);
        throw e;
      },
    );
    this.inFlight.set(key, { promise, listeners });
    if (signal) {
      signal.addEventListener("abort", () => {
        if (onProgress) listeners.delete(onProgress);
      }, { once: true });
    }
    return promise;
  }

  /** Stops reporting progress to a requester that no longer needs it (the download itself continues and is cached). */
  unsubscribe(ref: CacheableRef, onProgress: Progress): void {
    this.inFlight.get(ArtifactCache.key(ref))?.listeners.delete(onProgress);
  }

  private store(key: string, value: T) {
    if (value.size > this.maxBytes) return; // larger than the whole budget: used once, never kept
    this.entries.set(key, value);
    let total = 0;
    for (const v of this.entries.values()) total += v.size;
    for (const [k, v] of this.entries) {
      if (this.entries.size <= this.maxEntries && total <= this.maxBytes) break;
      if (k === key) continue;
      this.entries.delete(k);
      total -= v.size;
    }
  }
}
