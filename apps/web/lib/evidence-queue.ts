// Batches capture evidence (positions, surface observations, tracking events, quality samples) for upload, and keeps it
// through brief network failures. Capture never blocks on the network: when the queue is over its cap the oldest items are
// dropped and counted, so the operator is told how much evidence was lost instead of the HUD silently showing less.

export class EvidenceQueue<T> {
  private items: T[] = [];
  private inFlight = false;
  dropped = 0;
  sent = 0;
  lastError: string | null = null;
  private readonly send: (batch: T[]) => Promise<unknown>;
  private readonly maxBatch: number;
  private readonly cap: number;

  constructor(send: (batch: T[]) => Promise<unknown>, maxBatch: number, cap: number) {
    this.send = send;
    this.maxBatch = maxBatch;
    this.cap = cap;
  }

  push(...items: T[]): void {
    this.items.push(...items);
    if (this.items.length > this.cap) {
      this.dropped += this.items.length - this.cap;
      this.items.splice(0, this.items.length - this.cap);
    }
  }

  get pending(): number {
    return this.items.length;
  }

  /** Sends everything queued, in order, one batch at a time. A failed batch goes back to the front of the queue. */
  async flush(): Promise<void> {
    if (this.inFlight) return;
    this.inFlight = true;
    try {
      while (this.items.length > 0) {
        const batch = this.items.splice(0, this.maxBatch);
        try {
          await this.send(batch);
          this.sent += batch.length;
          this.lastError = null;
        } catch (e) {
          this.items.unshift(...batch);
          this.push(); // re-apply the cap
          this.lastError = e instanceof Error ? e.message : String(e);
          return;
        }
      }
    } finally {
      this.inFlight = false;
    }
  }
}
