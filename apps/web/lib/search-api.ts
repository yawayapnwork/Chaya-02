"use client";

import { api } from "./capture-api";

/** Mirrors dev.chaya.api.search.SearchDtos.SearchResult. */
export interface SearchResult {
  poiId: string;
  floorId: string;
  label: string;
  category: string | null;
  tags: string[];
  x: number;
  y: number;
  z: number;
  /** In [0, 1]. Comparable across results only when matchType is the same (see SearchResponse). */
  similarity: number;
  detectionConfidence: number | null;
  source: "MANUAL" | "AUTO_DETECTED";
  boundingBox: Record<string, unknown> | null;
}

/** Mirrors dev.chaya.api.search.SearchDtos.SearchResponse. matchType: "embedding" is real CLIP cosine
 * similarity; "lexical_fallback" is trigram text similarity, used only when the embedding model
 * (services/vision) was unavailable -- never presented to the user as the same thing. */
export interface SearchResponse {
  query: string;
  matchType: "embedding" | "lexical_fallback";
  results: SearchResult[];
}

export interface SearchOptions {
  floorId?: string;
  topK?: number;
  accessible?: boolean;
}

export function searchVenue(venueId: string, query: string, options: SearchOptions = {}): Promise<SearchResponse> {
  const params = new URLSearchParams({ q: query });
  if (options.floorId) params.set("floorId", options.floorId);
  if (options.topK) params.set("topK", String(options.topK));
  if (options.accessible) params.set("accessible", "true");
  return api<SearchResponse>(`/venues/${venueId}/search?${params.toString()}`);
}
