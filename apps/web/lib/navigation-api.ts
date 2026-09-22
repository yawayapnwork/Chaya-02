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

/** Mirrors dev.chaya.api.navigation.NavigationDtos.FloorTransition. */
export interface FloorTransition {
  fromFloorId: string;
  toFloorId: string;
  connectorType: "STAIRS" | "ELEVATOR" | "ESCALATOR" | "RAMP";
  poiId: string;
}

/** Mirrors dev.chaya.api.navigation.NavigationDtos.RouteResponse. */
export interface RouteResponse {
  waypoints: RouteWaypoint[];
  distanceMeters: number;
  estimatedDurationSeconds: number;
  floorTransitions: FloorTransition[];
  accessibilityProfile: "STANDARD" | "STEP_FREE";
  accessibilityConstraintsApplied: string[];
}

export interface RouteRequest {
  venueId: string;
  floorId: string;
  start: [number, number, number];
  destinationPoiId: string;
  /** "STANDARD" (default) or "STEP_FREE". */
  accessibility?: string;
  blockedRegions?: BlockedRegion[];
}

export const planRoute = (request: RouteRequest) => api<RouteResponse>("/navigation/routes", { method: "POST", body: JSON.stringify(request) });
