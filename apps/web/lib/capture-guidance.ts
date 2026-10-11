// What the operator should do next, from the HUD status and what this browser knows about its own camera and tracking
// (docs/capture-hud.md section 6). Pure, so every rule is unit tested (capture-guidance.test.ts). Every instruction is
// derived from a real signal; when a signal is missing the guidance says what is missing instead of guessing.

import type { HudStatus } from "./hud-types.ts";

export type GuidanceSeverity = "BLOCKING" | "WARNING" | "ACTION" | "INFO" | "DONE";

export interface Instruction {
  code: string;
  severity: GuidanceSeverity;
  title: string;
  detail: string;
}

export type CaptureMode = "AR" | "MANUAL" | "IMPORT";

/** What the browser knows locally, beyond the server's status. */
export interface LocalState {
  mode: CaptureMode | null;
  /** AR: the result of the capability check. */
  arSupport: "SUPPORTED" | "UNSUPPORTED" | "CHECKING" | "UNKNOWN";
  arUnsupportedReason?: string | null;
  arSessionActive: boolean;
  cameraState: "OFF" | "STARTING" | "LIVE" | "DENIED" | "UNAVAILABLE";
  cameraDetail?: string | null;
  /** Live motion of the tracked device, when tracking. */
  motionWarnings: string[];
  /** Manual mode: whether the device heading has been calibrated to the plan's +x axis. */
  headingCalibrated: boolean;
  nowMs: number;
}

const WARNING_TEXT: Record<string, { title: string; detail: string }> = {
  BLUR: { title: "Frames are blurred", detail: "Slow down, hold the device steadier, and pause briefly at each waypoint." },
  FAST_MOTION: { title: "Moving too fast for sharp frames", detail: "Walk and turn more slowly." },
  UNDEREXPOSED: { title: "Too dark", detail: "Turn on the room lights or open blinds; dark frames lose detail and tracking." },
  OVEREXPOSED: { title: "Too bright", detail: "Avoid pointing at windows or lamps; aim at the room's surfaces." },
  LOW_FEATURE_COUNT: { title: "Too little texture", detail: "Plain walls give the camera nothing to track. Include furniture, edges or posters in view." },
  DUPLICATE_FRAME: { title: "The view is not changing", detail: "Keep moving along the path; identical frames add nothing." },
  SPACING_TOO_FAR: { title: "Moving too far between frames", detail: "Slow down so consecutive frames overlap." },
  MOVING_TOO_FAST: { title: "Walking too fast", detail: "Walk slowly (under 1 m/s) so frames stay sharp and tracking holds." },
  TURNING_TOO_FAST: { title: "Turning too fast", detail: "Turn slowly; fast turns blur frames and can lose tracking." },
};

/** Relative direction of a bearing seen from a heading (both plan degrees, counter-clockwise). */
export function relativeDirection(turnDegrees: number): string {
  const a = Math.abs(turnDegrees);
  const side = turnDegrees > 0 ? "left" : "right";
  if (a < 20) return "straight ahead";
  if (a < 70) return `ahead to your ${side}`;
  if (a < 110) return `to your ${side}`;
  return "behind you";
}

function wrap(a: number): number {
  const r = ((a % 360) + 360) % 360;
  return r > 180 ? r - 360 : r;
}

/** How to reach a target from a position (and heading, when known). */
export function directionsTo(from: { x: number; y: number; headingDegrees?: number | null }, to: { x: number; y: number },
                             walkMeters: number | null): { distanceMeters: number; bearingDegrees: number; turnDegrees: number | null; text: string } {
  const dx = to.x - from.x, dy = to.y - from.y;
  const straight = Math.hypot(dx, dy);
  const distance = walkMeters ?? straight;
  const bearing = (Math.atan2(dy, dx) * 180) / Math.PI;
  const turn = from.headingDegrees == null ? null : wrap(bearing - from.headingDegrees);
  const how = turn === null ? `towards (${to.x.toFixed(1)}, ${to.y.toFixed(1)}) on the map` : relativeDirection(turn);
  const via = walkMeters !== null && walkMeters > straight + 0.5 ? " following the path on the map (it goes around an obstacle)" : "";
  return { distanceMeters: distance, bearingDegrees: bearing, turnDegrees: turn, text: `Walk ${distance.toFixed(1)} m ${how}${via}.` };
}

const AT_WAYPOINT_METERS = 0.5;

export function guidance(status: HudStatus | null, local: LocalState): Instruction[] {
  const out: Instruction[] = [];
  const push = (code: string, severity: GuidanceSeverity, title: string, detail: string) => out.push({ code, severity, title, detail });

  // ---- the device itself -------------------------------------------------------------------------------------------
  if (local.mode === "AR" && local.arSupport === "UNSUPPORTED") {
    push("AR_UNSUPPORTED", "BLOCKING", "This device cannot track its position",
      `${local.arUnsupportedReason ?? "WebXR immersive AR is not available here."} Use "Mark positions on a measured plan" or ` +
      `"Import a tracked trajectory" instead; coverage then comes from those positions and is labelled accordingly.`);
  }
  if (local.cameraState === "DENIED") {
    push("CAMERA_DENIED", "BLOCKING", "Camera access was denied",
      "Allow camera access for this site in the browser's site settings, then start again. Without it neither AR tracking nor " +
      "frame-quality checks can run; you can still mark positions on a measured plan.");
  } else if (local.cameraState === "UNAVAILABLE") {
    push("CAMERA_UNAVAILABLE", "WARNING", "No camera available",
      `${local.cameraDetail ?? "The browser reports no usable camera."} Frame quality cannot be checked.`);
  }
  if (!status) {
    push("CONNECTING", "INFO", "Waiting for the capture status", "The server's capture status has not arrived yet.");
    return out;
  }

  // ---- tracking ------------------------------------------------------------------------------------------------------
  const flags = status.qualityFlags ?? [];
  const last = status.currentPosition;
  switch (status.trackingState) {
    case "LOST":
    case "LIMITED": {
      const why = flags.includes("LOW_FEATURE_COUNT") ? " The camera sees too little texture."
        : flags.includes("UNDEREXPOSED") ? " The room is too dark." : "";
      push("TRACKING_LOST", "BLOCKING", status.trackingState === "LOST" ? "Tracking lost" : "Position not tracked (orientation only)",
        `Stop and hold still. Point the camera at a well-lit, textured area (furniture, edges)${last ? ` and return to where tracking was last good, ` +
          `near (${last.x.toFixed(1)}, ${last.y.toFixed(1)})` : ""}.${why} Nothing is recorded or credited until tracking returns.`);
      break;
    }
    case "INTERRUPTED":
    case "SESSION_ENDED":
      push("SESSION_INTERRUPTED", "BLOCKING", "The AR session was interrupted",
        "Restart AR tracking. A new session has a new coordinate frame: you will be asked to re-align it to the plan before positions count again.");
      break;
    case "FRAME_MISMATCH":
      push("REALIGN", "BLOCKING", "Re-align to the plan",
        `Tracking restarted in a new frame (${status.excludedOtherFramePositions ?? 0} position(s) not placed on the plan). Stand on the marked ` +
        `spot on the map, face the arrow, and tap "Re-align here". Your placement accuracy becomes the alignment's error.`);
      break;
    case "PERMISSION_DENIED":
      push("CAMERA_DENIED", "BLOCKING", "Camera access was denied", "Allow camera access for this site, then restart AR tracking.");
      break;
    case "UNSUPPORTED":
      push("AR_UNSUPPORTED", "BLOCKING", "This device cannot track its position",
        "Switch to marking positions on a measured plan, or import a tracked trajectory.");
      break;
    case "STALE":
      push("POSITION_STALE", "WARNING", "No recent position", `${status.trackingUnavailableReason ?? "Positions stopped arriving."} Coverage is not updating.`);
      break;
    case "IMPORTED_ONLY":
      push("IMPORTED_ONLY", "INFO", "Coverage is from an imported trajectory",
        "No live position is available. The coverage shown is what the imported positions observed, not live tracking.");
      break;
    case "MANUAL":
      if (!local.headingCalibrated) {
        push("HEADING_UNCALIBRATED", "INFO", "Facing direction not recorded",
          "Your marks carry no facing direction, so coverage assumes you face the way you walked. To record it, face along the room's " +
          "x-axis (the first wall you measured) and tap \"Set direction\".");
      }
      break;
    default:
      break;
  }
  for (const w of local.motionWarnings) {
    const t = WARNING_TEXT[w];
    if (t) push(w, "WARNING", t.title, t.detail);
  }
  for (const f of flags) {
    const t = WARNING_TEXT[f];
    if (t && !out.some((i) => i.code === f)) push(f, "WARNING", t.title, t.detail);
  }

  // ---- reconnaissance and bounds -----------------------------------------------------------------------------------
  const recon = status.reconnaissance;
  if (!status.sceneSet) {
    if (recon && recon.verdict !== "READY") {
      for (const i of recon.issues.filter((i) => i.severity === "BLOCKING")) {
        push(`RECON_${i.code}`, "ACTION", reconTitle(i.code), i.message);
      }
      if (recon.verdict === "NOT_STARTED" && local.mode === "AR") {
        push("RECON_START", "ACTION", "Walk the room", "Start AR tracking and walk slowly around the room for at least 10 s, pointing the camera at every wall and corner.");
      }
    } else if (recon?.boundsEstimable) {
      push("ESTIMATE_BOUNDS", "ACTION", "Estimate the room bounds", "The walk has enough evidence: tap \"Estimate room bounds\".");
    } else if (local.mode === "MANUAL") {
      push("ENTER_OUTLINE", "ACTION", "Enter the measured room outline", "Measure the room and enter its size; manual marks need a to-scale plan.");
    }
    return sortInstructions(out);
  }
  const bounds = status.bounds;
  if (bounds && (bounds.status === "LOW_CONFIDENCE") && bounds.frontier.length > 0) {
    const f = bounds.frontier[0];
    push("BOUNDS_UNOBSERVED", "WARNING", "Part of the room has not been seen",
      `${bounds.frontierMeters.toFixed(1)} m of the boundary was not observed (largest stretch near (${f.midX.toFixed(1)}, ${f.midY.toFixed(1)})). ` +
      "The bounds are a lower bound, not the walls: look towards the orange edges on the map, then estimate again.");
  }
  if (bounds?.status === "MEASURED" && bounds.issues.some((i) => i.code === "OUTLINE_CONTRADICTED_BY_WALK")) {
    push("OUTLINE_WRONG", "WARNING", "The measured outline does not match where you walked",
      "Check the entered width and depth, and that the plan's origin is where you started.");
  }

  // ---- plan and next waypoint --------------------------------------------------------------------------------------
  if (status.plannedPathKind !== "PERSISTED_PLAN") {
    if (recon && recon.verdict !== "READY") {
      for (const i of recon.issues.filter((i) => i.severity === "BLOCKING")) push(`RECON_${i.code}`, "ACTION", reconTitle(i.code), i.message);
    } else {
      push("MAKE_PLAN", "ACTION", "Plan the scan path", "Tap \"Plan scan path\" to fix an ordered route of waypoints for this room.");
    }
    return sortInstructions(out);
  }
  if (status.completion === "COVERAGE_TARGET_REACHED") {
    push("COVERAGE_REACHED", "DONE", "Coverage target reached",
      `${status.coveragePercent?.toFixed(0)}% of the room is covered by the model${status.coverageLive ? "" : " (from positions that are not live)"}. ` +
      `Check the reshoot list, then finish the capture.`);
  } else if (status.completion === "ROUTE_COMPLETE") {
    push("ROUTE_COMPLETE", "DONE", "Route complete",
      `Every waypoint was visited or is unreachable. Coverage is ${status.coveragePercent?.toFixed(0)}%. Re-plan to cover the remaining gaps, ` +
      "or check the reshoot list.");
  }
  const next = status.nextWaypoint;
  const trackingUsable = status.trackingState === "TRACKING" || status.trackingState === "MANUAL";
  if (next && trackingUsable && last) {
    const d = directionsTo({ x: last.x, y: last.y, headingDegrees: last.headingDegrees }, next, next.walkMeters);
    if (Math.hypot(next.x - last.x, next.y - last.y) <= AT_WAYPOINT_METERS) {
      const face = last.headingDegrees == null ? null : wrap(next.yawDegrees - last.headingDegrees);
      push("AT_WAYPOINT", "ACTION", `At waypoint ${next.order}`,
        face === null ? `Face ${compass(next.yawDegrees)} on the map and hold still for 3 s.`
          : Math.abs(face) < 20 ? "Hold still for 3 s, facing this way."
            : `Turn ${Math.abs(face).toFixed(0)}° ${face > 0 ? "left" : "right"} and hold still for 3 s.`);
    } else {
      push("GO_TO_WAYPOINT", "ACTION", `Next: waypoint ${next.order} (${next.type.toLowerCase()})`,
        `${d.text} Then face ${compass(next.yawDegrees)} on the map. ${next.reason}`);
    }
  } else if (next && !trackingUsable) {
    push("WAYPOINT_WAITING", "INFO", `Next: waypoint ${next.order}`, "Directions resume once your position is known again.");
  } else if (next && !last) {
    push("WAYPOINT_NO_POSITION", "INFO", `Next: waypoint ${next.order}`, `Go to (${next.x.toFixed(1)}, ${next.y.toFixed(1)}) on the map.`);
  }
  const unreachable = status.plannedPath.filter((w) => w.status === "UNREACHABLE").length;
  if (unreachable > 0) {
    push("UNREACHABLE_WAYPOINTS", "WARNING", `${unreachable} waypoint(s) can no longer be reached`,
      "The room bounds changed since the plan was made. Re-plan to get a route that fits them.");
  }
  return sortInstructions(out);
}

const ORDER: Record<GuidanceSeverity, number> = { BLOCKING: 0, WARNING: 1, ACTION: 2, DONE: 3, INFO: 4 };

function sortInstructions(list: Instruction[]): Instruction[] {
  const seen = new Set<string>();
  return list.filter((i) => (seen.has(i.code) ? false : (seen.add(i.code), true)))
    .map((i, idx) => ({ i, idx }))
    .sort((a, b) => ORDER[a.i.severity] - ORDER[b.i.severity] || a.idx - b.idx)
    .map((x) => x.i);
}

function reconTitle(code: string): string {
  switch (code) {
    case "VIDEO_ONLY": return "Video cannot position the walk";
    case "NO_POSITIONS": return "No positions recorded yet";
    case "WALK_TOO_SHORT": return "Keep walking";
    case "PATH_TOO_SHORT": return "Walk further through the room";
    case "TOO_FEW_SAMPLES": return "Not enough positions yet";
    case "TOO_FEW_MARKS": return "Mark more positions";
    case "TRACKING_UNRELIABLE": return "Tracking was lost too often";
    case "MANUAL_NEEDS_MEASURED_OUTLINE": return "Enter the measured outline first";
    case "BOUNDS_NEED_EVIDENCE": return "Point the camera at the walls";
    case "FRAME_MISMATCH": return "Positions from another AR session";
    default: return code.replace(/_/g, " ").toLowerCase();
  }
}

/** A plan heading (counter-clockwise from +x, i.e. from "east" on the map) as a map direction. */
export function compass(yawDegrees: number): string {
  const names = ["east (right)", "north-east", "north (up)", "north-west", "west (left)", "south-west", "south (down)", "south-east"];
  const idx = Math.round(((((yawDegrees % 360) + 360) % 360) / 45)) % 8;
  return names[idx];
}
