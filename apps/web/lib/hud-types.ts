// Wire types of the live capture HUD and scan planning (services/api .../hud, packages/contracts/openapi/v1.yaml).
// No imports, so pure modules and their node tests can use them.

export interface ScenePoint { x: number; y: number }
export interface SceneSegment { a: ScenePoint; b: ScenePoint }
export interface SceneDoorway { x: number; y: number; width: number; passageDegrees?: number | null }
export interface Scene {
  areas: ScenePoint[][];
  walls?: SceneSegment[] | null;
  obstacles?: ScenePoint[][] | null;
  noGoZones?: ScenePoint[][] | null;
  doorways?: SceneDoorway[] | null;
}

export type PoseSource = "webxr" | "arkit" | "manual" | "operator-trajectory";

export interface PoseSample {
  capturedAtMs: number;
  x: number;
  y: number;
  yawDegrees?: number | null;
  source?: PoseSource;
  frameId?: string | null;
  frameAlignment?: "NATIVE" | "OPERATOR_ALIGNED";
}

export interface SurfaceSampleWire {
  capturedAtMs: number;
  originX: number;
  originY: number;
  hitX: number;
  hitY: number;
  kind: "WALL" | "FLOOR";
  normalDegrees: number | null;
  source: "webxr" | "arkit" | "operator-trajectory";
  frameId: string | null;
}

export interface TrackingEventWire {
  capturedAtMs: number;
  state: string;
  source: "manual" | "webxr" | "arkit" | "camera" | "operator-trajectory";
  frameId?: string | null;
  detail?: string | null;
}

export interface QualitySample {
  capturedAtMs: number;
  blurScore: number;
  brightnessMean: number;
  shadowClipFraction: number;
  highlightClipFraction: number;
  motionScore: number | null;
  duplicateFrame: boolean;
  featureCount: number;
  spacingMeters: number | null;
  warnings: string[];
}

export interface ReconIssue { code: string; severity: "BLOCKING" | "WARNING" | "INFO"; message: string }
export interface ReconAssessment {
  verdict: "READY" | "INCOMPLETE" | "NOT_STARTED" | "UNSUPPORTED";
  positionBasis: "TRACKED" | "IMPORTED" | "MANUAL" | "MIXED" | "NONE";
  metric: boolean;
  samples: number;
  durationSeconds: number;
  pathLengthMeters: number;
  segments: number;
  headingShare: number;
  trackingLosses: number;
  lostSeconds: number;
  longestGapSeconds: number;
  frameId: string | null;
  excludedOtherFrame: number;
  wallObservations: number;
  boundsEstimable: boolean;
  issues: ReconIssue[];
  requirements: { minDurationSeconds: number; minSamples: number; minManualMarks: number; minPathMeters: number; minWallObservations: number };
}

export interface Frontier { polyline: ScenePoint[]; lengthMeters: number; midX: number; midY: number; outwardDegrees: number }
export interface BoundsIssue { code: string; message: string }
export interface Bounds {
  status: "ESTIMATED" | "LOW_CONFIDENCE" | "MEASURED" | "NOT_ESTIMABLE";
  basis: "SENSOR_EVIDENCE" | "OPERATOR_MEASUREMENT";
  frameId: string | null;
  area: ScenePoint[] | null;
  obstacles: ScenePoint[][];
  unobservedInterior: ScenePoint[][];
  frontier: Frontier[];
  confidence: number;
  confidenceParts: { boundaryObserved: number; interiorObserved: number; density: number };
  areaM2: number;
  observedBoundaryMeters: number;
  frontierMeters: number;
  assumptions: string[];
  issues: BoundsIssue[];
}

export interface EstimateBoundsResponse { stored: boolean; bounds: Bounds; reconnaissance: ReconAssessment }

export interface CoverageMetrics {
  coveragePercent: number; weightedCoveragePercent: number; coveredAreaM2: number; uncoveredAreaM2: number; targetAreaM2: number;
  redundantCapturePercent: number; poseCount: number; pathLengthMeters: number; waypointCount: number;
}
export interface PlannedWaypoint {
  order: number; x: number; y: number; yawDegrees: number; type: string; reason: string; expectedCoverageGainM2: number;
  /** VISITED, SKIPPED, PENDING, UNREACHABLE for a persisted plan; SUGGESTED for a live suggestion. */
  status?: string | null;
}
export interface PlanView {
  version: number;
  createdAt: string;
  sceneSource: "MEASURED" | "ESTIMATED";
  frameId: string | null;
  predicted: { waypoints: PlannedWaypoint[]; route: ScenePoint[]; estimatedDistanceMeters: number; estimatedCaptureSeconds: number;
    planned: CoverageMetrics; baseline: CoverageMetrics };
  observedAtPlan: CoverageMetrics;
}

export interface Position {
  x: number; y: number; headingDegrees: number | null; asOfMs: number;
  source?: string | null;
  /** TRACKED (platform world tracking), MANUAL (marked by the operator) or IMPORTED. */
  kind?: "TRACKED" | "MANUAL" | "IMPORTED" | null;
}
export interface PathPoint { x: number; y: number; segment?: number; kind?: "START" | "TRACKED" | "IMPORTED" | "INTERPOLATED" | "PLANNED" }
export interface UncoveredZone { centroidX: number; centroidY: number; areaM2: number; reason: string }
export interface QualitySummary {
  sampleCount: number; avgBlurScore: number; avgBrightnessMean: number; duplicateFrameRate: number;
  avgFeatureCount: number; avgSpacingMeters: number | null; lastSampleAtMs: number;
}
export interface ReshootRecommendation { reason: string; x: number | null; y: number | null }
export interface NextWaypoint {
  order: number; x: number; y: number; yawDegrees: number; type: string; reason: string; walkMeters: number | null; leg: PathPoint[];
}
export interface PlanStatus {
  version: number; createdAt: string; coverageKind: "PREDICTED"; predictedCoveragePercent: number; observedAtPlanCoveragePercent: number;
  waypoints: number; visited: number; unreachable: number;
}

export type TrackingState =
  | "NOT_STARTED" | "TRACKING" | "STALE" | "LIMITED" | "LOST" | "INTERRUPTED" | "SESSION_ENDED" | "FRAME_MISMATCH"
  | "PERMISSION_DENIED" | "UNSUPPORTED" | "MANUAL" | "IMPORTED_ONLY";

export interface HudStatus {
  trackingAvailable: boolean;
  trackingUnavailableReason: string | null;
  currentPosition: Position | null;
  pathSoFar: PathPoint[];
  sceneSet: boolean;
  coverageAvailable: boolean;
  coverageUnavailableReason: string | null;
  coveragePercent: number | null;
  weightedCoveragePercent: number | null;
  uncoveredAreaM2: number | null;
  uncoveredZones: UncoveredZone[];
  plannedPath: PlannedWaypoint[];
  quality: QualitySummary | null;
  warnings: string[];
  reshootRecommendations: ReshootRecommendation[];
  computedAt: string;
  trackingState?: TrackingState;
  sceneSource?: "MEASURED" | "ESTIMATED" | null;
  sceneFrameId?: string | null;
  scene?: Scene | null;
  bounds?: Bounds | null;
  reconnaissance?: ReconAssessment | null;
  coverageBasis?: "LIVE_TRACKED" | "TRACKED_NOT_LIVE" | "MANUAL_MARKS" | "IMPORTED_TRAJECTORY" | "MIXED" | "NONE";
  coverageLive?: boolean;
  coverageBasisDetail?: string;
  plannedPathKind?: "PERSISTED_PLAN" | "LIVE_SUGGESTION" | "NONE";
  plan?: PlanStatus | null;
  nextWaypoint?: NextWaypoint | null;
  completion?: "NO_PLAN" | "NOT_STARTED" | "IN_PROGRESS" | "ROUTE_COMPLETE" | "COVERAGE_TARGET_REACHED";
  excludedOtherFramePositions?: number;
  qualityFlags?: string[];
  lastTrackingEventAtMs?: number | null;
  lastTrackingEvent?: string | null;
}
