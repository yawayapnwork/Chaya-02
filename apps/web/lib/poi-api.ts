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
  /** CURRENT: in the floor's current frame. STALE: in an older one. UNBOUND: in no calibrated frame. */
  frameStatus: "CURRENT" | "STALE" | "UNBOUND";
}

export const listPois = (venueId: string) => api<Poi[]>(`/venues/${venueId}/pois`);
export const getPoi = (venueId: string, poiId: string) => api<Poi>(`/venues/${venueId}/pois/${poiId}`);
