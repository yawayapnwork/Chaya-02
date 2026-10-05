"use client";

import { ApiError, api } from "./capture-api";
import type { Pose } from "./ar-anchor-math";
import { publicConfig } from "./env";
import { currentAuthHeaders } from "./session";

/** Mirrors dev.chaya.api.ar.ArDtos.Anchor. */
export interface Anchor {
  id: string;
  venueId: string;
  floorId: string;
  markerType: "QR_CODE" | "ARUCO_MARKER" | "IMAGE_TARGET" | "APRILTAG";
  markerIdentifier: string;
  /** Printed width in metres. Required for IMAGE_TARGET, the only marker type WebXR can detect (docs/ar.md). */
  markerSizeMeters: number | null;
  physicalPose: Pose;
  /** Canonical venue pose. For an IMAGE_TARGET: the printed image's centre, with the image's own axes (docs/ar.md). */
  digitalPose: Pose;
  calibrationStatus: "UNCALIBRATED" | "CALIBRATED" | "STALE";
  lastCalibratedAt: string | null;
  /** The coordinate frame digitalPose (canonical metres, +Z up) is expressed in. */
  coordinateFrameId: string | null;
  /** The scan version the pose was entered against; null for an anchor from before poses were versioned (not usable). */
  scanVersionId?: string | null;
  /** The immutable versioned pose record these values are (ar_anchor_pose), and its revision. */
  poseId?: string | null;
  poseRevision?: number | null;
}

export interface AnchorRequest {
  markerType: Anchor["markerType"];
  markerIdentifier: string;
  markerSizeMeters: number | null;
  physicalPose: Pose;
  digitalPose: Pose;
}

/** Mirrors dev.chaya.api.ar.ArDtos.AnchorObservation: `observedPose` is the pose of that anchor's own marker, as this
 * device's WebXR image tracking measured it in the AR world frame (metres, gravity-aligned, +Y up;
 * lib/ar-marker-tracking.ts). Never a hit-test result, never fabricated. */
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
  /** The scan version the floor publishes, whose anchors and frame the transform is solved in. */
  scanVersionId: string;
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

/** scanVersionId: the version the client shows, if it knows; the server refuses (409 VERSION_MISMATCH) when the floor
 * publishes another one, so the device is never placed in one version's frame while drawing another's content. */
export const relocalize = (venueId: string, floorId: string, observations: AnchorObservation[], scanVersionId?: string | null) =>
  api<RelocalizationResponse>(`/venues/${venueId}/floors/${floorId}/anchors/relocalize`, {
    method: "POST",
    body: JSON.stringify({ observations, scanVersionId: scanVersionId ?? null }),
  });

/** The IMAGE_TARGET's target image (the server generates it from the anchor id): the exact image the operator printed,
 * handed to WebXR image tracking. */
export async function fetchTargetImage(venueId: string, floorId: string, anchorId: string): Promise<Blob> {
  const res = await fetch(`${publicConfig().apiBaseUrl}/api/v1/venues/${venueId}/floors/${floorId}/anchors/${anchorId}/target.png`, {
    headers: await currentAuthHeaders(),
  });
  if (!res.ok) throw new ApiError(res.status, "TARGET_IMAGE_UNAVAILABLE", `target image of anchor ${anchorId}: ${res.status}`);
  return res.blob();
}
