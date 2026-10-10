// Where the viewer's camera starts, derived from the scene that was actually loaded. Pure, so it is unit-tested.
//
// The camera used to start at a fixed (0, -4, 1.7) looking at the origin, and the library's orbit controls kept their
// default target (0, 0, 0). That only frames a reconstruction whose content happens to sit around the origin. A
// FLOOR_LOCAL frame's origin is the capture's floor reference point, so a large venue could still be mostly out of view.
// A VENUE_CONTROL_POINTS frame's origin is the survey datum, which may be far from the venue, so nothing was in view. An
// uncalibrated reconstruction's origin is wherever SfM put it. So the start is derived from the splats: a robust centre
// (per-axis median) and extent (10th to 90th percentile, so floaters do not stretch it) of a sample of their centres,
// read after the scene transform.

export type Vec3 = [number, number, number];

export interface InitialView {
  position: Vec3;
  target: Vec3;
  up: Vec3;
  /** The robust horizontal extent the view was fitted to, metres (canonical) or reconstruction units. */
  extent: number;
}

function quantile(sorted: number[], q: number): number {
  if (sorted.length === 0) return 0;
  const i = Math.min(sorted.length - 1, Math.max(0, Math.round(q * (sorted.length - 1))));
  return sorted[i];
}

/** Indices of at most `max` splats, evenly spread over `count`, so the sample covers the whole scene. */
export function sampleIndices(count: number, max = 20000): number[] {
  if (count <= 0) return [];
  const n = Math.min(count, max);
  const step = count / n;
  return Array.from({ length: n }, (_, k) => Math.min(count - 1, Math.floor(k * step)));
}

/**
 * The starting view for a scene whose sampled splat centres are `centers`. `canonical`: the scene is in the canonical
 * venue frame (metres, +Z up), so the camera stands back from the content and looks at it slightly from above, with +Z
 * up. Otherwise no up direction is known; the camera looks along the reconstruction's +Z, which is how SfM cameras look
 * (COLMAP: x right, y down, z forward), with -Y up. That is a viewing default only, as before. Returns null without
 * finite centres.
 */
export function initialView(centers: readonly Vec3[], canonical: boolean): InitialView | null {
  const finite = centers.filter((c) => c.every(Number.isFinite));
  if (finite.length === 0) return null;
  const axis = (k: 0 | 1 | 2) => finite.map((c) => c[k]).sort((a, b) => a - b);
  const xs = axis(0), ys = axis(1), zs = axis(2);
  const target: Vec3 = [quantile(xs, 0.5), quantile(ys, 0.5), quantile(zs, 0.5)];
  const ex = quantile(xs, 0.9) - quantile(xs, 0.1);
  const ey = quantile(ys, 0.9) - quantile(ys, 0.1);
  const ez = quantile(zs, 0.9) - quantile(zs, 0.1);
  if (canonical) {
    const extent = Math.max(ex, ey, 0.5);
    const back = Math.max(1.5, 0.9 * extent);
    // Stand back along -Y and raise the eye so the floor and the content above it are both in view.
    return { position: [target[0], target[1] - back, target[2] + Math.max(1.0, 0.45 * back)], target, up: [0, 0, 1], extent };
  }
  const extent = Math.max(ex, ey, ez, 1e-3);
  const back = 1.2 * extent;
  return { position: [target[0], target[1], target[2] - back], target, up: [0, -1, 0], extent };
}
