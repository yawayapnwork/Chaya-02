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
  x: number;
  y: number;
  z: number;
}

export const listPois = (venueId: string) => api<Poi[]>(`/venues/${venueId}/pois`);
export const getPoi = (venueId: string, poiId: string) => api<Poi>(`/venues/${venueId}/pois/${poiId}`);
