"use client";

import { api } from "./capture-api";

export interface Poi {
  id: string;
  floorId: string | null;
  spaceId: string | null;
  version: number;
  label: string;
  category: string | null;
  description: string | null;
  tags: string[];
  /** Canonical venue metres (+Z up) in `coordinateFrameId` (docs/coordinate-frames.md). */
  x: number;
  y: number;
  z: number;
  coordinateFrameId: string | null;
  /** CURRENT: in the floor's current frame (for a version-scoped list: in that version's frame). STALE: in an older
   * one. UNBOUND: in no calibrated frame. */
  frameStatus: "CURRENT" | "STALE" | "UNBOUND";
  /** The scan version this POI version was placed against; null if its reconstruction has no version yet. */
  scanVersionId: string | null;
  /** Whether this POI is part of what its floor publishes now (its current scan version or an ancestor). */
  current: boolean;
}

/** Without scanVersionId: the POIs every floor publishes now (its current scan version). With it: exactly the POIs of that finalized scan version, each
 * as it is in that version. */
export const listPois = (venueId: string, scanVersionId?: string | null) =>
  api<Poi[]>(`/venues/${venueId}/pois${scanVersionId ? `?scanVersionId=${encodeURIComponent(scanVersionId)}` : ""}`);
export const getPoi = (venueId: string, poiId: string) => api<Poi>(`/venues/${venueId}/pois/${poiId}`);
