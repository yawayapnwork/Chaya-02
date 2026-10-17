"use client";

import { useState } from "react";
import { ApiError, type Floor, type RescanInitiated, initiateRescan } from "@/lib/capture-api";
import { parseRegion } from "@/lib/rescan-region";

/**
 * Starts a re-scan of a changed region of a floor (docs/rescan.md): POST /floors/{f}/rescan on the version the floor
 * publishes now. The server creates a region-scoped capture; the caller then uploads to it exactly like any capture.
 * It needs a connection (the server creates the capture), and a floor with a published version.
 */
export default function RescanStart({ venueId, floor, online, device, onStarted }: {
  venueId: string;
  floor: Floor;
  online: boolean;
  device: () => Promise<Record<string, unknown>>;
  onStarted: (r: RescanInitiated) => Promise<void>;
}) {
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const parsed = text.trim() ? parseRegion(text) : null;
  const parent = floor.currentScanVersionId;

  async function start(e: React.FormEvent) {
    e.preventDefault();
    if (!parent || !parsed || "error" in parsed || busy) return;
    setBusy(true);
    setError(null);
    try {
      await onStarted(await initiateRescan(venueId, floor.id, { parentVersionId: parent, region: { points: parsed.points }, device: await device() }));
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <details className="rounded border p-3 text-sm" data-testid="rescan-start">
      <summary className="cursor-pointer py-2 font-medium">Re-scan a changed region of this floor</summary>
      {!parent ? (
        <p className="mt-2 text-zinc-700" data-testid="rescan-unavailable">
          This floor has no published version yet, so there is nothing to re-scan: capture and process the whole floor first.
        </p>
      ) : (
        <form className="mt-2 space-y-2" onSubmit={start}>
          <p className="text-zinc-600">
            Only the region is captured and processed; it is aligned to the published version and spliced into it, and the
            result becomes a new version only if every stage succeeds. The floor must be calibrated.
          </p>
          <label className="block">
            <span>Region corners, one per line: x, y in metres (the venue&apos;s coordinates)</span>
            <textarea className="mt-1 block h-28 w-full rounded border p-2 font-mono" value={text} spellCheck={false}
              placeholder={"4.4, -0.1\n6.1, -0.1\n6.1, 2.7\n4.4, 2.7"} onChange={(e) => setText(e.target.value)}
              aria-describedby="rescan-region-status" aria-invalid={!!parsed && "error" in parsed} />
          </label>
          <p id="rescan-region-status" className={parsed && "error" in parsed ? "text-red-700" : "text-zinc-600"} aria-live="polite">
            {!parsed ? "Enter at least three corners." : "error" in parsed ? parsed.error
              : `${parsed.points.length} corners, ${parsed.areaSquareMeters.toFixed(2)} m².`}
          </p>
          {error && <p role="alert" className="whitespace-pre-line rounded border border-red-300 bg-red-50 p-2 text-red-800">{error}</p>}
          {!online && <p className="text-amber-800">Starting a re-scan needs a connection: the server creates the capture.</p>}
          <button type="submit" className="rounded bg-black px-4 py-2 text-white disabled:opacity-50"
            disabled={busy || !online || !parsed || "error" in parsed}>
            {busy ? "Starting re-scan…" : "Start re-scan"}
          </button>
        </form>
      )}
    </details>
  );
}
