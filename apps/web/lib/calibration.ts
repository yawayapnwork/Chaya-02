// Pure rules for capture calibration evidence (docs/capture-calibration.md), shared by the UI and its tests. They mirror
// the server's checks for early feedback only: the server re-checks everything and is the authority. Nothing here
// produces a measurement, a scale or a pose: every number comes from the operator.

export type MeasurementKind = "DISTANCE" | "CONTROL_POINT";
export type LengthUnit = "m" | "cm" | "mm" | "ft" | "in";
export type MeasurementMethod = "TAPE" | "LASER_DISTANCE_METER" | "TOTAL_STATION" | "SURVEY_PLAN" | "OTHER";
export type PointName = "A" | "B" | "P";

export const UNIT_METRES: Record<LengthUnit, number> = { m: 1, cm: 0.01, mm: 0.001, ft: 0.3048, in: 0.0254 };
export const METHODS: readonly MeasurementMethod[] = ["TAPE", "LASER_DISTANCE_METER", "TOTAL_STATION", "SURVEY_PLAN", "OTHER"];
export const MIN_DISTANCE_M = 0.05;
export const MAX_DISTANCE_M = 200;
export const MIN_VIEWS_PER_POINT = 2;

/** Where the operator marked a physical point: a pixel in an accepted image, or in one frame of an accepted video. */
export interface ObservationDraft {
  point: PointName;
  mediaId: string;
  frameTimeSeconds: number | null;
  u: number;
  v: number;
}

export interface MeasurementDraft {
  kind: MeasurementKind;
  label: string;
  method: MeasurementMethod;
  unit: LengthUnit;
  value: number | null;
  uncertainty: number | null;
  datum: string;
  venue: [number, number, number] | null;
  observations: ObservationDraft[];
}

/** What the client knows about a media file a point can be marked in. */
export interface ObservableMedia {
  id: string;
  kind: "IMAGE" | "VIDEO" | "METADATA";
  status: string;
  pixelWidth: number | null;
  pixelHeight: number | null;
}

export function pointsOf(kind: MeasurementKind): PointName[] {
  return kind === "DISTANCE" ? ["A", "B"] : ["P"];
}

/** Problems an operator can fix before sending, in the server's words where it has them; empty when none. */
export function checkMeasurementDraft(d: MeasurementDraft, media: ObservableMedia[]): string[] {
  const problems: string[] = [];
  if (!d.label.trim()) problems.push("Give the measurement a label, e.g. \"door 2.14 width\" or \"CP-3\".");
  const perUnit = UNIT_METRES[d.unit];
  if (perUnit === undefined) problems.push("Choose a unit: m, cm, mm, ft or in. Values are never assumed to be metres.");
  if (d.kind === "DISTANCE") {
    if (d.value === null || !Number.isFinite(d.value) || d.value <= 0) {
      problems.push("Enter the measured length between A and B (a positive number).");
    } else if (perUnit !== undefined) {
      const m = d.value * perUnit;
      if (m < MIN_DISTANCE_M || m > MAX_DISTANCE_M) {
        problems.push(`${d.value} ${d.unit} is ${round(m)} m; a measured indoor reference must be between ${MIN_DISTANCE_M} m and ${MAX_DISTANCE_M} m. Check the unit.`);
      }
    }
  } else {
    if (!d.venue || d.venue.some((c) => !Number.isFinite(c))) problems.push("Enter the surveyed x, y and z of the control point (+Z up).");
    if (!d.datum.trim()) problems.push("Name the survey datum the coordinates are in.");
  }
  if (d.uncertainty !== null && (!Number.isFinite(d.uncertainty) || d.uncertainty <= 0)) {
    problems.push("Uncertainty, when given, must be a positive number.");
  }
  const byId = new Map(media.map((m) => [m.id, m]));
  const views = new Map<PointName, Set<string>>();
  for (const o of d.observations) {
    const m = byId.get(o.mediaId);
    if (!m || m.status !== "ACCEPTED" || m.kind === "METADATA") {
      problems.push(`Point ${o.point}: mark it in an accepted image or video of this capture.`);
      continue;
    }
    if (!Number.isFinite(o.u) || !Number.isFinite(o.v) || o.u < 0 || o.v < 0) {
      problems.push(`Point ${o.point}: pixel coordinates must be non-negative numbers.`);
    } else if (m.pixelWidth !== null && m.pixelHeight !== null && (o.u >= m.pixelWidth || o.v >= m.pixelHeight)) {
      problems.push(`Point ${o.point}: pixel (${o.u}, ${o.v}) is outside the ${m.pixelWidth}x${m.pixelHeight} image.`);
    }
    if (m.kind === "VIDEO" && (o.frameTimeSeconds === null || !Number.isFinite(o.frameTimeSeconds) || o.frameTimeSeconds < 0)) {
      problems.push(`Point ${o.point}: give the time (seconds) of the video frame it was marked in.`);
    }
    const view = `${o.mediaId}@${m.kind === "VIDEO" ? o.frameTimeSeconds : ""}`;
    const set = views.get(o.point) ?? new Set<string>();
    set.add(view);
    views.set(o.point, set);
  }
  for (const p of pointsOf(d.kind)) {
    const n = views.get(p)?.size ?? 0;
    if (n < MIN_VIEWS_PER_POINT) {
      problems.push(`Point ${p} is marked in ${n} view(s); mark it in at least ${MIN_VIEWS_PER_POINT} different images or video frames so it can be located in 3D.`);
    }
  }
  return problems;
}

/** The request body for POST .../measurements. */
export function measurementBody(d: MeasurementDraft): Record<string, unknown> {
  return {
    kind: d.kind,
    label: d.label.trim(),
    method: d.method,
    unit: d.unit,
    value: d.kind === "DISTANCE" ? d.value : null,
    uncertainty: d.uncertainty,
    datum: d.kind === "CONTROL_POINT" ? d.datum.trim() : null,
    venue: d.kind === "CONTROL_POINT" ? d.venue : null,
    observations: d.observations.map((o) => ({
      point: o.point,
      mediaId: o.mediaId,
      frameTimeSeconds: o.frameTimeSeconds,
      u: o.u,
      v: o.v,
    })),
  };
}

/**
 * Pixel coordinates in the media's own pixels from a click on its displayed element. (0, 0) is the top-left corner of
 * the top-left pixel, as the server records it.
 */
export function clickToPixel(
  click: { offsetX: number; offsetY: number },
  displayed: { width: number; height: number },
  natural: { width: number; height: number },
): { u: number; v: number } | null {
  if (displayed.width <= 0 || displayed.height <= 0 || natural.width <= 0 || natural.height <= 0) return null;
  const u = (click.offsetX / displayed.width) * natural.width;
  const v = (click.offsetY / displayed.height) * natural.height;
  if (u < 0 || v < 0 || u >= natural.width || v >= natural.height) return null;
  return { u: Math.round(u * 10) / 10, v: Math.round(v * 10) / 10 };
}

export type CalibrationState =
  | "NO_EVIDENCE"
  | "EVIDENCE_INCOMPLETE"
  | "AWAITING_RECONSTRUCTION"
  | "READY_TO_CALIBRATE"
  | "CALIBRATED"
  | "REJECTED";

export type CaptureStage = "INCOMPLETE_CAPTURE" | "VALIDATING_UPLOAD" | "MEDIA_UPLOADED" | "PROCESSING" | "COMPLETED" | "FAILED";

export interface StateBadge {
  label: string;
  tone: "neutral" | "pending" | "ok" | "error";
  detail: string;
}

export function captureStageBadge(stage: CaptureStage): StateBadge {
  switch (stage) {
    case "INCOMPLETE_CAPTURE":
      return { label: "Incomplete capture", tone: "pending", detail: "Media is still being uploaded." };
    case "VALIDATING_UPLOAD":
      return { label: "Checking upload", tone: "pending", detail: "The server is verifying the stored media." };
    case "MEDIA_UPLOADED":
      return { label: "Media uploaded", tone: "ok", detail: "All media is accepted and stored; processing can start." };
    case "PROCESSING":
      return { label: "Media uploaded · processing", tone: "ok", detail: "The reconstruction pipeline is running or waiting for a retry." };
    case "COMPLETED":
      return { label: "Media uploaded · processed", tone: "ok", detail: "The reconstruction pipeline finished." };
    case "FAILED":
      return { label: "Capture failed", tone: "error", detail: "The capture cannot continue." };
  }
}

/** "Calibration pending" covers every state short of a decision; CALIBRATED and REJECTED are the decisions. */
export function calibrationBadge(state: CalibrationState, reconstructionFrame: string): StateBadge {
  switch (state) {
    case "NO_EVIDENCE":
      return { label: "Calibration pending", tone: "pending", detail: "No measurements recorded yet." };
    case "EVIDENCE_INCOMPLETE":
      return { label: "Calibration pending", tone: "pending", detail: "Not enough measurements yet." };
    case "AWAITING_RECONSTRUCTION":
      return { label: "Calibration pending", tone: "pending", detail: "Measurements recorded; waiting for a reconstruction." };
    case "READY_TO_CALIBRATE":
      return { label: "Calibration pending", tone: "pending", detail: "Locate the measured points in the reconstruction." };
    case "CALIBRATED":
      return reconstructionFrame === "CANONICAL"
        ? { label: "Calibrated", tone: "ok", detail: "Metric and gravity-aligned (metres, +Z up)." }
        : { label: "Calibrated (scale only)", tone: "pending", detail: "Metric scale, but not gravity-aligned." };
    case "REJECTED":
      return { label: "Calibration rejected", tone: "error", detail: "The measurements were refused; see below." };
  }
}

export function reconstructionFrameLabel(frame: string): string {
  switch (frame) {
    case "NO_RECONSTRUCTION":
      return "No reconstruction yet";
    case "ARBITRARY_SCALE":
      return "Arbitrary SfM scale (not metric)";
    case "METRIC_NOT_ALIGNED":
      return "Metric, not gravity-aligned";
    case "CANONICAL":
      return "Metric, gravity-aligned (canonical venue frame)";
    default:
      return frame;
  }
}

/** Maps server calibration error codes to an action; the server's own message follows it. */
export function explainCalibrationCode(code: string): string | null {
  switch (code) {
    case "INSUFFICIENT_OBSERVATIONS":
      return "Mark every point in at least two different images or video frames.";
    case "DEGENERATE_GEOMETRY":
      return "The points coincide or lie on one line; measure between distinct points or add a point off the line.";
    case "INCONSISTENT_UNITS":
    case "INCONSISTENT_DATUM":
      return "All control points of one capture must use the same survey unit and datum.";
    case "MISSING_UNIT":
    case "INVALID_UNIT":
      return "Choose the unit the value was measured in.";
    case "MEASUREMENT_OUT_OF_RANGE":
      return "The value is not a plausible indoor measurement; check the unit.";
    case "MEASUREMENT_TOO_UNCERTAIN":
      return "The stated uncertainty is too large for a calibration; measure a longer reference.";
    case "CALIBRATION_INCONSISTENT":
      return "The measurements disagree with each other; re-measure or withdraw the one that is off.";
    case "CAPTURE_NOT_OWNED":
      return "Only the capture's operator, a venue manager or an admin can change its calibration.";
    case "FLOOR_REQUIRED":
      return "This capture has no floor; record measurements on a capture created for a floor.";
    default:
      return null;
  }
}

function round(v: number): number {
  return Math.round(v * 1000) / 1000;
}
