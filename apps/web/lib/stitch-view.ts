// Pure presentation rules for rooms and multi-room stitching (docs/multi-room.md). The important ones: a room is only
// offered for stitching once its version is FINALIZED (it is reconstructed and calibrated); a stitch is presented as
// published only when the floor publishes it; and every room that could not be registered is named, with the reason.

/** Mirrors dev.chaya.api.stitch.StitchDtos.RoomVersion. */
export interface RoomVersion {
  id: string;
  versionNumber: number;
  status: "DRAFT" | "FINALIZED" | string;
  pipelineRunId: string | null;
  runStatus: string | null;
  coordinateFrameId: string | null;
  finalizedAt: string | null;
  createdAt: string;
  awaitingCalibration: boolean;
}

/** Mirrors dev.chaya.api.stitch.StitchDtos.RoomView. */
export interface Room {
  id: string;
  floorId: string;
  name: string;
  kind: "ROOM" | "CORRIDOR" | "HALL" | "OTHER" | string;
  createdAt: string;
  latestVersion: RoomVersion | null;
  latestFinalizedVersion: RoomVersion | null;
  latestCaptureId: string | null;
  latestCaptureStatus: string | null;
}

/** Mirrors StitchDtos.StitchRoom: registered null until ROOM_REGISTRATION has reported. */
export interface StitchRoom {
  roomId: string;
  name: string;
  roomVersionId: string;
  roomVersionNumber: number;
  coordinateFrameId: string;
  anchor: boolean;
  registered: boolean | null;
  reason: string | null;
  /** For a registered room: two independent chains of registrations place it. False: placed only because the stitch
   * accepted unconfirmed rooms (absent from APIs before it existed). */
  loopConfirmed?: boolean | null;
}

/** Mirrors StitchDtos.StitchPair. */
export interface StitchPair {
  sourceRoomId: string | null;
  targetRoomId: string | null;
  status: string;
  outcome: string;
  failedGates: string[];
  edgeStatus: string | null;
}

/** The parts of a run this view reads (lib/capture-api RunView). */
export interface StitchRun {
  id: string;
  status: "RUNNING" | "SUCCEEDED" | "PARTIAL" | "FAILED" | "CANCELLED";
  failureStage: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  retryable: boolean;
  /** Why a FAILED run cannot be retried (lib/pipeline-view retryBlockedText). */
  retryBlockedReason?: string | null;
  stages: { stage: string; state: string }[];
}

/** Mirrors StitchDtos.StitchView. */
export interface Stitch {
  id: string;
  floorId: string;
  versionNumber: number;
  status: "DRAFT" | "FINALIZED" | string;
  current: boolean;
  captureId: string;
  scanId: string;
  run: StitchRun | null;
  anchorRoomId: string | null;
  /** The stitch accepts rooms only one chain of registrations places (no loop confirms them). */
  allowUnconfirmedRooms?: boolean;
  rooms: StitchRoom[];
  pairs: StitchPair[];
  registration: Record<string, unknown> | null;
  stitch: Record<string, unknown> | null;
  connectivity: Record<string, unknown> | null;
  createdAt: string;
  finalizedAt: string | null;
  createdBy: string | null;
}

export type Tone = "info" | "success" | "warning" | "error";

// ---- rooms ------------------------------------------------------------------------------------------------------------

export type RoomState = "NOT_CAPTURED" | "CAPTURING" | "PROCESSING" | "FAILED" | "AWAITING_CALIBRATION" | "READY";

/** Where a room is on its way to being stitchable, and what to do next. READY whenever a FINALIZED room version exists:
 * the latest finalized one is what a stitch uses, whatever a newer capture is doing (that is shown alongside). */
export function roomState(room: Room): { state: RoomState; label: string; tone: Tone } {
  const ready = room.latestFinalizedVersion;
  const latest = room.latestVersion;
  if (!latest) {
    return room.latestCaptureId
      ? { state: "CAPTURING", label: `Capture ${room.latestCaptureStatus?.toLowerCase().replaceAll("_", " ") ?? "started"}`, tone: "info" }
      : { state: "NOT_CAPTURED", label: "Not captured yet", tone: "info" };
  }
  if (latest.status === "FINALIZED") return { state: "READY", label: `Ready: v${latest.versionNumber}`, tone: "success" };
  // The latest version is a draft, newer than any finalized one.
  const step: { state: RoomState; text: string; tone: Tone } = latest.awaitingCalibration
    ? { state: "AWAITING_CALIBRATION", text: "reconstructed; calibrate it, then finalize", tone: "warning" }
    : ["FAILED", "CANCELLED", "PARTIAL"].includes(latest.runStatus ?? "")
      ? { state: "FAILED", text: `processing ${latest.runStatus?.toLowerCase()}`, tone: "error" }
      : { state: "PROCESSING", text: "processing", tone: "info" };
  if (ready) return { state: "READY", label: `Ready: v${ready.versionNumber}; newest capture ${step.text}`, tone: step.tone === "error" ? "warning" : "success" };
  return { state: step.state, label: step.text.charAt(0).toUpperCase() + step.text.slice(1), tone: step.tone };
}

/** Why a selection cannot be stitched yet (null when it can): at least two rooms, each with a FINALIZED version, and an
 * anchor among them. The server checks all of this again, and also that every room is calibrated. */
export function stitchProblem(rooms: Room[], selected: string[], anchor: string | null): string | null {
  if (selected.length < 2) return "Select at least two rooms that see each other through a doorway or opening.";
  const notReady = rooms.filter((r) => selected.includes(r.id) && !r.latestFinalizedVersion).map((r) => r.name);
  if (notReady.length) return `Not finalized yet: ${notReady.join(", ")}. Capture, process and calibrate each room first.`;
  if (!anchor || !selected.includes(anchor)) return "Choose the anchor room: the stitched floor is placed in its coordinates.";
  return null;
}

// ---- stitches ---------------------------------------------------------------------------------------------------------

/** What happened to a pair of rooms, in words (chaya_worker.room_registration.OUTCOMES). */
export function outcomeText(outcome: string): string {
  switch (outcome) {
    case "REGISTERED":
      return "registered";
    case "NO_SHARED_FEATURES":
      return "no shared surfaces found (expected for rooms that do not see each other)";
    case "FREE_SPACE_CONFLICT":
      return "rejected: it would put one room where the other capture saw empty space";
    case "SCALE_INCONSISTENT":
      return "rejected: the two rooms' calibrations disagree on scale";
    case "AMBIGUOUS":
      return "rejected: a second, different placement fits almost as well (repeated structure)";
    case "WEAK_OVERLAP":
      return "rejected: too little shared, or a direction is unconstrained";
    case "TILTED":
      return "rejected: it would tilt one room against the other";
    case "RESIDUAL_TOO_HIGH":
      return "rejected: the shared surfaces still disagree";
    default:
      return outcome.toLowerCase().replaceAll("_", " ");
  }
}

export interface StitchSummary {
  badge: string;
  tone: Tone;
  headline: string;
  /** stages completed of the plan, e.g. "2 of 5 stages" */
  progress: string;
  /** the rooms that could not be registered, with why */
  unregistered: { name: string; reason: string }[];
  /** registered rooms no loop confirms: placed by a single chain of registrations, which nothing could check */
  unconfirmed: string[];
  /** the stitched floor can be opened in the viewer */
  viewable: boolean;
  canRetry: boolean;
}

/** The state of a stitch as an operator needs it. Published means the floor publishes it (current); a FINALIZED stitch
 * that is no longer current was published and has been superseded. */
export function summarizeStitch(s: Stitch): StitchSummary {
  const run = s.run;
  const done = run ? run.stages.filter((x) => x.state === "SUCCEEDED").length : 0;
  const total = run ? run.stages.length : 0;
  const progress = `${done} of ${total} stages`;
  const unregistered = s.rooms.filter((r) => r.registered === false).map((r) => ({ name: r.name, reason: r.reason ?? "not registered" }));
  const unconfirmed = s.rooms.filter((r) => r.registered === true && r.loopConfirmed === false).map((r) => r.name);
  const base = { progress, unregistered, unconfirmed, viewable: s.status === "FINALIZED", canRetry: false };
  if (s.status === "FINALIZED") {
    return { ...base, badge: s.current ? "Published" : "Superseded", tone: s.current ? "success" : "info",
      headline: s.current ? "The stitched floor is what this floor shows." : "Published earlier; the floor now shows a newer version." };
  }
  if (!run || run.status === "RUNNING") {
    const active = run?.stages.find((x) => x.state === "RUNNING") ?? run?.stages.find((x) => x.state === "QUEUED");
    return { ...base, badge: "Stitching", tone: "info", headline: active ? `Now: ${active.stage.toLowerCase().replaceAll("_", " ")}` : "Waiting to start." };
  }
  if (run.status === "CANCELLED") {
    return { ...base, badge: "Cancelled", tone: "warning", headline: "This stitch was cancelled. Nothing was published." };
  }
  if (run.status === "FAILED") {
    const rejected = ["REGISTRATION_REJECTED", "STITCH_REJECTED", "STITCH_FRAME_CHANGED"].includes(run.failureCode ?? "");
    return { ...base, badge: rejected ? "Rejected" : "Failed", tone: "error", canRetry: run.retryable,
      headline: rejected
        ? `Rejected${run.failureCode === "REGISTRATION_REJECTED" ? ": not every room could be placed" : run.failureCode === "STITCH_REJECTED"
          ? ": the rooms do not meet on the same surfaces" : ": the anchor room was recalibrated meanwhile"}. Nothing was published.`
        : `Stopped${run.failureStage ? ` at ${run.failureStage.toLowerCase().replaceAll("_", " ")}` : ""}. Nothing was published.` };
  }
  // Every stage succeeded but the version is still a draft: its publication was refused.
  return { ...base, badge: "Not published", tone: "warning", headline: run.failureMessage ?? "Every stage succeeded but the version was not published." };
}

/** A short measured summary of the stitch and its navigation, from the reports recorded on the version, for display.
 * Only numbers the stages measured; nothing is computed or estimated here. */
export function measuredFacts(s: Stitch): string[] {
  const facts: string[] = [];
  const st = s.stitch ?? {};
  if (typeof st.duplicate_fraction_after === "number") {
    facts.push(`Doubled surfaces left after merging: ${(100 * st.duplicate_fraction_after).toFixed(2)} % of Gaussians`);
  }
  const pairs = Array.isArray(st.pairs) ? (st.pairs as Record<string, unknown>[]) : [];
  for (const p of pairs) {
    if (typeof p.offset_m === "number") {
      facts.push(`Overlap ${String((p.pieces as unknown[] | undefined)?.join(" / ") ?? "")}: ${(100 * p.offset_m).toFixed(1)} cm residual offset`);
    }
  }
  if (Array.isArray(st.colour_seams_visible) && st.colour_seams_visible.length) {
    facts.push(`Visible colour difference between ${st.colour_seams_visible.length} pair(s) of rooms (exposure); geometry is unaffected`);
  }
  const nav = s.connectivity ?? {};
  if (typeof nav.connected === "boolean") {
    facts.push(nav.connected ? "Navigation: every room can be reached from every other" : "Navigation: some rooms cannot be reached");
  }
  if (nav.step_free_connected === false) facts.push("Step-free navigation does not reach every room");
  const widths = Array.isArray(nav.clear_widths) ? (nav.clear_widths as Record<string, unknown>[]) : [];
  const narrow = widths.filter((w) => w.accessible_width === false).length;
  if (narrow) facts.push(`${narrow} pair(s) of rooms are joined only by a way narrower than the accessible width`);
  return facts;
}

/** The viewer link for a version's run (app/viewer reads ?venue=, ?floor=, ?run=). */
export function viewerHref(venueId: string, floorId: string, runId: string): string {
  return `/viewer?${new URLSearchParams({ venue: venueId, floor: floorId, run: runId }).toString()}`;
}
