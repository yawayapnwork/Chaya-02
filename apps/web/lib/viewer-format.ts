import type { Poi } from "./poi-api";

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  const units = ["KB", "MB", "GB"];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(1)} ${units[i]}`;
}

export function formatDate(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/** Euclidean distance between two POIs in the reconstruction's own scene coordinates. The pipeline has no
 * metric calibration step, so this is deliberately "scene units", never labelled as metres. */
export function straightLineDistance(a: Pick<Poi, "x" | "y" | "z">, b: Pick<Poi, "x" | "y" | "z">): number {
  return Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
}
