/**
 * The changed region of a re-scan as the operator types it: one vertex per line, "x, y" in canonical venue metres
 * (docs/rescan.md, "Select the changed region"). Checked here only for what can be told without the server (enough
 * vertices, numbers, no repeats); the server validates the polygon itself (simple, area limits, calibrated parent) and
 * its refusal is shown as it is.
 */
export const MAX_VERTICES = 512;

export type RegionParse = { points: [number, number][]; areaSquareMeters: number } | { error: string };

export function parseRegion(text: string): RegionParse {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const points: [number, number][] = [];
  for (const [i, line] of lines.entries()) {
    const parts = line.split(/[\s,;]+/).filter(Boolean);
    const x = Number(parts[0]);
    const y = Number(parts[1]);
    if (parts.length !== 2 || !Number.isFinite(x) || !Number.isFinite(y)) {
      return { error: `Line ${i + 1} is not "x, y" in metres: "${line}"` };
    }
    points.push([x, y]);
  }
  if (points.length < 3) return { error: "A region needs at least 3 vertices." };
  if (points.length > MAX_VERTICES) return { error: `A region may have at most ${MAX_VERTICES} vertices.` };
  const seen = new Set<string>();
  for (const [x, y] of points) {
    const key = `${x},${y}`;
    if (seen.has(key)) return { error: `Vertex ${x}, ${y} is repeated.` };
    seen.add(key);
  }
  return { points, areaSquareMeters: polygonArea(points) };
}

/** Shoelace area, square metres (the server's PolygonGeometry.area applies its own limits to the same number). */
export function polygonArea(points: [number, number][]): number {
  let twice = 0;
  for (let i = 0; i < points.length; i++) {
    const [x1, y1] = points[i];
    const [x2, y2] = points[(i + 1) % points.length];
    twice += x1 * y2 - x2 * y1;
  }
  return Math.abs(twice) / 2;
}
