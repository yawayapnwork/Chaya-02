/**
 * Minimal Server-Sent Events framing, as pure string functions so they can be unit tested without a network
 * stack or a DOM. See hud-api.ts for the fetch-based reader that uses them (the browser's EventSource cannot
 * send an Authorization header, so the live HUD status is read this way instead).
 */

/** Splits a growing text buffer into complete SSE frames (separated by a blank line) plus the unconsumed remainder. */
export function splitSseFrames(buffer: string): { frames: string[]; rest: string } {
  const frames: string[] = [];
  let rest = buffer;
  let sep: number;
  while ((sep = rest.indexOf("\n\n")) !== -1) {
    frames.push(rest.slice(0, sep));
    rest = rest.slice(sep + 2);
  }
  return { frames, rest };
}

/** Extracts and joins the `data:` lines of one SSE frame, or null for a data-less frame (a comment/heartbeat). */
export function sseFrameData(frame: string): string | null {
  const dataLines = frame.split("\n").filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trimStart());
  return dataLines.length === 0 ? null : dataLines.join("\n");
}
