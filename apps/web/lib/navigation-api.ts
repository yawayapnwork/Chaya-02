"use client";

import { api } from "./capture-api";

/** Mirrors dev.chaya.api.navigation.NavigationDtos.BlockedRegion: an obstacle the device actually observed, in canonical
 * metres on one floor. observedAt (ISO-8601) is required; the server rejects stale or future observations, and ones in
 * another coordinate frame than the floor is routed in. */
export interface BlockedRegion {
  floorId: string;
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
  observedAt: string;
  coordinateFrameId?: string | null;
}

/** Mirrors NavigationDtos.RejectedObstacle: a reported obstacle the route was NOT planned around, and why. */
export interface RejectedObstacle {
  index: number;
  floorId: string;
  reason: "STALE_OBSERVATION" | "FUTURE_OBSERVATION" | "FRAME_MISMATCH";
  detail: string;
}

/** Mirrors dev.chaya.api.navigation.NavigationDtos.Waypoint. */
export interface RouteWaypoint {
  x: number;
  y: number;
  z: number;
  floorId: string;
  /** STOP: an intermediate destination of a multi-stop route. */
  kind: "START" | "WAYPOINT" | "TRANSITION" | "STOP" | "DESTINATION";
}

/** Mirrors dev.chaya.api.navigation.NavigationDtos.FloorTransition: one crossing of a registered floor connection. */
export interface FloorTransition {
  fromFloorId: string;
  toFloorId: string;
  connectorType: "STAIRS" | "ELEVATOR" | "RAMP";
  /** The landing POI the route leaves from, on fromFloorId. */
  poiId: string;
  connectionId: string;
  /** The landing POI the route arrives at, on toFloorId. */
  toPoiId: string;
  /** Registered walked length (0 for an elevator). */
  distanceMeters: number;
  durationSeconds: number;
}

/** Mirrors dev.chaya.api.navigation.NavigationDtos.RoutingSource: what one floor's leg was routed on. scanVersionId and
 * coordinateFrameId say which version of the floor, and which frame, its waypoints are in. */
export interface RoutingSource {
  floorId: string;
  graphId: string;
  source: string;
  navmeshSha256: string | null;
  recastnavigationVersion: string | null;
  pathMethod: string | null;
  scanVersionId: string | null;
  coordinateFrameId: string | null;
}

/** Mirrors dev.chaya.api.navigation.NavigationDtos.RouteResponse. */
export interface RouteResponse {
  waypoints: RouteWaypoint[];
  distanceMeters: number;
  estimatedDurationSeconds: number;
  floorTransitions: FloorTransition[];
  accessibilityProfile: "STANDARD" | "STEP_FREE";
  accessibilityConstraintsApplied: string[];
  routingSources?: RoutingSource[];
  rejectedObstacles?: RejectedObstacle[];
}

export interface RouteRequest {
  venueId: string;
  floorId: string;
  start: [number, number, number];
  destinationPoiId: string;
  /** "STANDARD" (default) or "STEP_FREE". */
  accessibility?: string;
  blockedRegions?: BlockedRegion[];
  /** Route on this finalized scan version (its pinned navmesh graph and frame) instead of the floor's current state. */
  scanVersionId?: string;
}

/** Mirrors NavigationDtos.MultiStopRequest. Stops are visited in the given order unless optimizeOrder is set. */
export interface MultiStopRequest {
  venueId: string;
  floorId: string;
  start: [number, number, number];
  stopPoiIds: string[];
  optimizeOrder?: boolean;
  accessibility?: string;
  blockedRegions?: BlockedRegion[];
  scanVersionId?: string;
}

/** Mirrors NavigationDtos.RouteSegment. fromPoiId null: the segment starts at the request's start. */
export interface RouteSegment {
  index: number;
  fromPoiId: string | null;
  toPoiId: string;
  route: RouteResponse;
}

/** Mirrors NavigationDtos.StopResult. */
export interface StopResult {
  poiId: string;
  requestedIndex: number;
  visitIndex: number | null;
  status: "REACHED" | "UNREACHABLE";
  failureCode: string | null;
  failureMessage: string | null;
}

/** Mirrors NavigationDtos.MultiStopResponse. */
export interface MultiStopResponse {
  visitOrder: string[];
  orderOptimized: boolean;
  complete: boolean;
  stops: StopResult[];
  segments: RouteSegment[];
  waypoints: RouteWaypoint[];
  distanceMeters: number;
  estimatedDurationSeconds: number;
  accessibilityProfile: "STANDARD" | "STEP_FREE";
  accessibilityConstraintsApplied: string[];
  routingSources: RoutingSource[];
  rejectedObstacles: RejectedObstacle[];
}

export const planRoute = (request: RouteRequest) => api<RouteResponse>("/navigation/routes", { method: "POST", body: JSON.stringify(request) });
export const planMultiStopRoute = (request: MultiStopRequest) =>
  api<MultiStopResponse>("/navigation/routes/multi-stop", { method: "POST", body: JSON.stringify(request) });
