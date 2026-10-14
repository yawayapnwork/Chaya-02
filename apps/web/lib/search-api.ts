"use client";

import { api } from "./capture-api";
import type { SearchResponse } from "./search-view";

export { locationNote, type SpatialStatus } from "./search-location";
export type { SearchCorrection, SearchInterpretation, SearchPosition, SearchResponse, SearchResult } from "./search-view";

export interface SearchOptions {
  floorId?: string;
  topK?: number;
  accessible?: boolean;
  /** Search only the POIs of this finalized scan version. */
  scanVersionId?: string;
  /** Where the person searching is, for "nearest" (canonical metres on that floor, in its current or the version's frame). */
  position?: { floorId: string; x: number; y: number; z: number };
}

export function searchVenue(venueId: string, query: string, options: SearchOptions = {}): Promise<SearchResponse> {
  const params = new URLSearchParams({ q: query });
  if (options.floorId) params.set("floorId", options.floorId);
  if (options.topK) params.set("topK", String(options.topK));
  if (options.accessible) params.set("accessible", "true");
  if (options.scanVersionId) params.set("scanVersionId", options.scanVersionId);
  if (options.position) {
    for (const v of [options.position.x, options.position.y, options.position.z]) params.append("position", String(v));
    params.set("positionFloorId", options.position.floorId);
  }
  return api<SearchResponse>(`/venues/${venueId}/search?${params.toString()}`);
}
