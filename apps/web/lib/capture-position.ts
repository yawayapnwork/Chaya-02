/**
 * Pure geometry for the HUD's floor-plan minimap: fitting the operator's drawn room outline into a canvas, and
 * converting between canvas pixels and the plan view's metres (x east, y north) that the backend speaks. Kept
 * free of the DOM so it can be unit tested directly.
 */

export interface PlanPoint { x: number; y: number }

export interface Viewport {
  scale: number; // pixels per metre
  originXMeters: number;
  originYMeters: number;
  paddingPx: number;
  canvasWidth: number;
  canvasHeight: number;
}

export interface BoundingBox { minX: number; minY: number; maxX: number; maxY: number }

export function boundingBoxOf(rings: PlanPoint[][]): BoundingBox | null {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const ring of rings) {
    for (const p of ring) {
      minX = Math.min(minX, p.x);
      minY = Math.min(minY, p.y);
      maxX = Math.max(maxX, p.x);
      maxY = Math.max(maxY, p.y);
    }
  }
  return Number.isFinite(minX) ? { minX, minY, maxX, maxY } : null;
}

/** Scales and centres a room outline's bounding box to fit a canvas, leaving `paddingPx` clear on every side. */
export function fitViewport(box: BoundingBox, canvasWidth: number, canvasHeight: number, paddingPx = 24): Viewport {
  const w = Math.max(box.maxX - box.minX, 0.01);
  const h = Math.max(box.maxY - box.minY, 0.01);
  const availW = Math.max(canvasWidth - 2 * paddingPx, 1);
  const availH = Math.max(canvasHeight - 2 * paddingPx, 1);
  const scale = Math.min(availW / w, availH / h);
  // Centre the (possibly non-square) room within the available area.
  const usedW = w * scale, usedH = h * scale;
  const originXMeters = box.minX - (availW - usedW) / 2 / scale;
  const originYMeters = box.minY - (availH - usedH) / 2 / scale;
  return { scale, originXMeters, originYMeters, paddingPx, canvasWidth, canvasHeight };
}

/** Plan metres -> canvas pixels. Canvas y grows downward; plan y grows north, so it is flipped. */
export function planToPixel(vp: Viewport, p: PlanPoint): { x: number; y: number } {
  const px = vp.paddingPx + (p.x - vp.originXMeters) * vp.scale;
  const pyFromTop = vp.paddingPx + (p.y - vp.originYMeters) * vp.scale;
  return { x: px, y: vp.canvasHeight - pyFromTop };
}

/** Canvas pixels -> plan metres. Inverse of planToPixel. */
export function pixelToPlan(vp: Viewport, px: number, py: number): PlanPoint {
  const x = vp.originXMeters + (px - vp.paddingPx) / vp.scale;
  const pyFromTop = vp.canvasHeight - py;
  const y = vp.originYMeters + (pyFromTop - vp.paddingPx) / vp.scale;
  return { x, y };
}
