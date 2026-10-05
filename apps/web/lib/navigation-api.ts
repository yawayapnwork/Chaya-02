"use client";

import { api } from "./capture-api";

/** Mirrors dev.chaya.api.navigation.NavigationDtos.BlockedRegion. */
export interface BlockedRegion {
  floorId: string;
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
}

/** Mirrors dev.chaya.api.navigation.NavigationDtos.Waypoint. */
export interface RouteWaypoint {
  x: number;
  y: number;
  z: number;
  floorId: string;
  kind: "START" | "WAYPOINT" | "TRANSITION" | "DESTINATION";
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

export const planRoute = (request: RouteRequest) => api<RouteResponse>("/navigation/routes", { method: "POST", body: JSON.stringify(request) });
