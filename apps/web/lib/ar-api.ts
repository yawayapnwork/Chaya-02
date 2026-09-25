"use client";

import { api } from "./capture-api";
import type { Pose } from "./ar-anchor-math";

/** Mirrors dev.chaya.api.ar.ArDtos.Anchor. */
export interface Anchor {
  id: string;
  venueId: string;
  floorId: string;
  markerType: "QR_CODE" | "ARUCO_MARKER" | "IMAGE_TARGET" | "APRILTAG";
  markerIdentifier: string;
  physicalPose: Pose;
  digitalPose: Pose;
  calibrationStatus: "UNCALIBRATED" | "CALIBRATED" | "STALE";
  lastCalibratedAt: string | null;
  /** The coordinate frame digitalPose (canonical metres, +Z up) is expressed in. */
  coordinateFrameId: string | null;
}

export interface AnchorRequest {
  markerType: Anchor["markerType"];
  markerIdentifier: string;
  physicalPose: Pose;
  digitalPose: Pose;
}

/** Mirrors dev.chaya.api.ar.ArDtos.AnchorObservation: `observedPose` must be a real detection from this
 * device's own tracking session, never fabricated, in the device's own convention (metres, gravity-aligned, +Y up;
 * lib/ar-frame-boundary.ts). The server applies the device/canonical boundary. */
export interface AnchorObservation {
  anchorId: string;
  observedPose: Pose;
}

/** Mirrors dev.chaya.api.ar.ArDtos.RelocalizationResponse. residualMeters is a real measured spread
 * across the anchors used, never a claimed accuracy figure -- see docs/ar.md -- and null (unknown) when only one
 * anchor was used. deviceToVenueTransform maps device tracking coordinates to canonical venue metres. */
export interface RelocalizationResponse {
  deviceToVenueTransform: Pose;
  residualMeters: number | null;
  anchorsUsed: number;
  gravityTiltDegrees: number;
  deviceFrameConvention: string;
  coordinateFrameId: string;
}

export const listAnchors = (venueId: string, floorId: string) =>
  api<Anchor[]>(`/venues/${venueId}/floors/${floorId}/anchors`);

export const getAnchor = (venueId: string, floorId: string, anchorId: string) =>
  api<Anchor>(`/venues/${venueId}/floors/${floorId}/anchors/${anchorId}`);

export const createAnchor = (venueId: string, floorId: string, body: AnchorRequest) =>
  api<Anchor>(`/venues/${venueId}/floors/${floorId}/anchors`, { method: "POST", body: JSON.stringify(body) });

export const updateAnchor = (venueId: string, floorId: string, anchorId: string, body: AnchorRequest) =>
  api<Anchor>(`/venues/${venueId}/floors/${floorId}/anchors/${anchorId}`, { method: "PUT", body: JSON.stringify(body) });

export const calibrateAnchor = (venueId: string, floorId: string, anchorId: string) =>
  api<Anchor>(`/venues/${venueId}/floors/${floorId}/anchors/${anchorId}/calibrate`, { method: "POST" });

export const deleteAnchor = (venueId: string, floorId: string, anchorId: string) =>
  api<void>(`/venues/${venueId}/floors/${floorId}/anchors/${anchorId}`, { method: "DELETE" });

export const relocalize = (venueId: string, floorId: string, observations: AnchorObservation[]) =>
  api<RelocalizationResponse>(`/venues/${venueId}/floors/${floorId}/anchors/relocalize`, {
    method: "POST",
    body: JSON.stringify({ observations }),
  });
