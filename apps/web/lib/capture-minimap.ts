// The capture HUD's floor-plan minimap (docs/capture-hud.md section 6). What it draws is only what the server reported:
// the room bounds (measured or estimated, with the unobserved boundary in orange), the recorded path (tracked solid,
// interpolated between manual marks dashed, never joined across a tracking loss), the plan's waypoints by status, the
// walking leg to the next one, and the current position by kind (tracked, manual mark, or last known when tracking is
// unavailable). drawMinimap needs a 2D context; minimapBox is pure and unit tested.

import { type BoundingBox, type PlanPoint, type Viewport, boundingBoxOf, planToPixel } from "./capture-position.ts";
import type { HudStatus } from "./hud-types.ts";

/** Everything the map must show, so nothing drawn is ever clipped: bounds, path, waypoints, position. */
export function minimapBox(status: HudStatus | null, fallback: PlanPoint[][] = []): BoundingBox | null {
  const rings: PlanPoint[][] = [...fallback];
  if (status?.scene?.areas) rings.push(...status.scene.areas);
  if (status?.bounds?.area) rings.push(status.bounds.area);
  if (status?.pathSoFar.length) rings.push(status.pathSoFar);
  if (status?.plannedPath.length) rings.push(status.plannedPath);
  if (status?.currentPosition) rings.push([status.currentPosition]);
  const box = boundingBoxOf(rings);
  if (!box) return null;
  const pad = 0.5;
  return { minX: box.minX - pad, minY: box.minY - pad, maxX: box.maxX + pad, maxY: box.maxY + pad };
}

export const WAYPOINT_COLORS: Record<string, string> = {
  VISITED: "#16a34a",
  PENDING: "#2563eb",
  SKIPPED: "#d97706",
  UNREACHABLE: "#dc2626",
  SUGGESTED: "#71717a",
};

export interface MinimapExtras {
  /** The spot the operator chose for an alignment, and the facing they will confirm. */
  alignTarget?: { x: number; y: number; yawDegrees: number } | null;
  /** The last tracked position this browser saw, shown when the server has no live one (lost tracking, new session). */
  lastKnown?: { x: number; y: number; yawDegrees: number | null } | null;
}

function ring(ctx: CanvasRenderingContext2D, vp: Viewport, pts: PlanPoint[], close = true) {
  ctx.beginPath();
  pts.forEach((p, i) => {
    const q = planToPixel(vp, p);
    if (i === 0) ctx.moveTo(q.x, q.y);
    else ctx.lineTo(q.x, q.y);
  });
  if (close) ctx.closePath();
}

function arrow(ctx: CanvasRenderingContext2D, vp: Viewport, at: PlanPoint, yawDegrees: number, lengthPx: number) {
  const p = planToPixel(vp, at);
  const a = (yawDegrees * Math.PI) / 180;
  ctx.beginPath();
  ctx.moveTo(p.x, p.y);
  ctx.lineTo(p.x + Math.cos(a) * lengthPx, p.y - Math.sin(a) * lengthPx); // canvas y is flipped
  ctx.stroke();
}

export function drawMinimap(ctx: CanvasRenderingContext2D, vp: Viewport, status: HudStatus, extras: MinimapExtras = {}) {
  ctx.clearRect(0, 0, vp.canvasWidth, vp.canvasHeight);
  ctx.setLineDash([]);

  // Room bounds, obstacles, unobserved interior.
  const scene = status.scene;
  for (const area of scene?.areas ?? []) {
    ring(ctx, vp, area);
    ctx.fillStyle = "rgba(255,255,255,0.9)";
    ctx.fill();
    ctx.strokeStyle = status.sceneSource === "ESTIMATED" ? "#52525b" : "#71717a";
    ctx.lineWidth = 2;
    ctx.stroke();
  }
  for (const o of scene?.obstacles ?? []) {
    ring(ctx, vp, o);
    ctx.fillStyle = "#a1a1aa";
    ctx.fill();
  }
  for (const u of scene?.noGoZones ?? []) {
    ring(ctx, vp, u);
    ctx.fillStyle = "rgba(249,115,22,0.18)";
    ctx.fill();
  }
  // Unobserved boundary: where observation stopped, not a wall.
  ctx.strokeStyle = "#f97316";
  ctx.lineWidth = 4;
  ctx.setLineDash([6, 4]);
  for (const f of status.bounds?.frontier ?? []) {
    ring(ctx, vp, f.polyline, false);
    ctx.stroke();
  }
  ctx.setLineDash([]);

  for (const z of status.uncoveredZones) {
    const c = planToPixel(vp, { x: z.centroidX, y: z.centroidY });
    ctx.beginPath();
    ctx.fillStyle = z.reason === "NOT_OBSERVED" ? "rgba(239,68,68,0.30)" : "rgba(234,179,8,0.35)";
    ctx.arc(c.x, c.y, Math.max(5, Math.sqrt(z.areaM2) * vp.scale * 0.4), 0, Math.PI * 2);
    ctx.fill();
  }

  // Recorded path: solid where tracked or imported, dashed where interpolated between manual marks, broken at gaps.
  ctx.lineWidth = 2;
  const path = status.pathSoFar;
  for (let i = 1; i < path.length; i++) {
    const a = path[i - 1], b = path[i];
    if (b.kind === "START" || (a.segment ?? 0) !== (b.segment ?? 0)) continue;
    ctx.strokeStyle = b.kind === "INTERPOLATED" ? "#a1a1aa" : b.kind === "IMPORTED" ? "#7c3aed" : "#2563eb";
    ctx.setLineDash(b.kind === "INTERPOLATED" ? [4, 4] : []);
    ring(ctx, vp, [a, b], false);
    ctx.stroke();
  }
  ctx.setLineDash([]);

  // Leg to the next waypoint, then the waypoints by status.
  const leg = status.nextWaypoint?.leg ?? [];
  if (leg.length > 1) {
    ctx.strokeStyle = "#16a34a";
    ctx.setLineDash([6, 4]);
    ring(ctx, vp, leg, false);
    ctx.stroke();
    ctx.setLineDash([]);
  }
  ctx.font = "10px sans-serif";
  for (const w of status.plannedPath) {
    const p = planToPixel(vp, w);
    const color = WAYPOINT_COLORS[w.status ?? "SUGGESTED"] ?? "#71717a";
    const next = status.nextWaypoint?.order === w.order;
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(p.x, p.y, next ? 7 : 5, 0, Math.PI * 2);
    if (w.status === "VISITED") ctx.fill();
    else ctx.stroke();
    arrow(ctx, vp, w, w.yawDegrees, next ? 16 : 10);
    ctx.fillText(String(w.order), p.x + 7, p.y - 7);
  }

  // Current position: tracked (filled, with heading), manual mark (hollow), or last known (grey, with "?").
  const pos = status.currentPosition;
  const live = status.trackingState === "TRACKING" || status.trackingState === "MANUAL";
  if (pos && live) {
    const p = planToPixel(vp, pos);
    ctx.beginPath();
    ctx.arc(p.x, p.y, 7, 0, Math.PI * 2);
    ctx.lineWidth = 3;
    if (pos.kind === "MANUAL") {
      ctx.strokeStyle = "#1d4ed8";
      ctx.stroke();
    } else {
      ctx.fillStyle = "#1d4ed8";
      ctx.fill();
      ctx.strokeStyle = "white";
      ctx.stroke();
    }
    if (pos.headingDegrees != null) {
      ctx.strokeStyle = "#1d4ed8";
      arrow(ctx, vp, pos, pos.headingDegrees, 18);
    }
  } else {
    const last = pos ?? extras.lastKnown;
    if (last) {
      const p = planToPixel(vp, last);
      ctx.beginPath();
      ctx.arc(p.x, p.y, 7, 0, Math.PI * 2);
      ctx.strokeStyle = "#71717a";
      ctx.lineWidth = 2;
      ctx.setLineDash([2, 2]);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.fillStyle = "#71717a";
      ctx.fillText("?", p.x - 3, p.y + 3);
    }
  }
  if (extras.alignTarget) {
    ctx.strokeStyle = "#db2777";
    ctx.lineWidth = 3;
    const p = planToPixel(vp, extras.alignTarget);
    ctx.beginPath();
    ctx.arc(p.x, p.y, 9, 0, Math.PI * 2);
    ctx.stroke();
    arrow(ctx, vp, extras.alignTarget, extras.alignTarget.yawDegrees, 22);
  }
}
