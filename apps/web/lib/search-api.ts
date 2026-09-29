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
  /** In [0, 1]. For an embedding match: CLIP text-to-text cosine with the POI's own text (a detected object's text is
   * its detector label), the same space for every result. Not comparable with lexical_fallback similarities. */
  similarity: number;
  detectionConfidence: number | null;
  source: "MANUAL" | "AUTO_DETECTED";
  boundingBox: Record<string, unknown> | null;
  /** Similarity minus the query's mean similarity to the distinct POI texts in scope; null for the lexical fallback. */
  relevanceMargin?: number | null;
  /** Detected objects only: CLIP text-to-image cosine with the crop. Its own scale; never compare it with similarity. */
  imageSimilarity?: number | null;
  /** FILTERED searches only: the evidence that made this a result. */
  matchedBy?: "TEXT" | "IMAGE" | "TEXT_AND_IMAGE" | null;
  /** Detected objects only: the capture frame the bounding box was measured in. */
  sourceFrame?: string | null;
}

/** Mirrors dev.chaya.api.search.SearchDtos.SearchResponse. matchType: "embedding" is real CLIP cosine
 * similarity; "lexical_fallback" is trigram text similarity, used only when the embedding model
 * (services/vision) was unavailable -- never presented to the user as the same thing. */
export interface SearchResponse {
  query: string;
  matchType: "embedding" | "lexical_fallback";
  /** What the venue has for the query; empty means nothing relevant was found. */
  results: SearchResult[];
  /** Only when results is empty: the nearest candidates, which did NOT clear the relevance threshold. */
  closestMatches?: SearchResult[];
  relevance?: "FILTERED" | "UNFILTERED" | "LEXICAL";
}

export interface SearchOptions {
  floorId?: string;
  topK?: number;
  accessible?: boolean;
  /** Search only the POIs of this finalized scan version. */
  scanVersionId?: string;
}

export function searchVenue(venueId: string, query: string, options: SearchOptions = {}): Promise<SearchResponse> {
  const params = new URLSearchParams({ q: query });
  if (options.floorId) params.set("floorId", options.floorId);
  if (options.topK) params.set("topK", String(options.topK));
  if (options.accessible) params.set("accessible", "true");
  if (options.scanVersionId) params.set("scanVersionId", options.scanVersionId);
  return api<SearchResponse>(`/venues/${venueId}/search?${params.toString()}`);
}
