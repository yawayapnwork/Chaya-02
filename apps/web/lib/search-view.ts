/**
 * What the search panel shows for a search response (docs/search.md). Types mirror dev.chaya.api.search.SearchDtos; the
 * helpers are pure and unit-tested with node --test, so this file imports nothing at run time.
 */

import type { SpatialStatus } from "./search-location.ts";

/** Mirrors SearchDtos.SearchResult. */
export interface SearchResult {
  poiId: string;
  floorId: string | null;
  label: string;
  category: string | null;
  tags: string[];
  x: number;
  y: number;
  z: number;
  /** In [0, 1]. For an embedding match: CLIP text-to-text cosine with the POI's own text (a detected object's text is
   * its detector label), the same space for every result. Not comparable with lexical_fallback similarities. 0 for a POI
   * found by name while its embedding is pending. */
  similarity: number;
  detectionConfidence: number | null;
  source: "MANUAL" | "AUTO_DETECTED";
  boundingBox: Record<string, unknown> | null;
  /** Similarity minus the query's mean similarity to the distinct POI texts in scope; null for the lexical fallback. */
  relevanceMargin?: number | null;
  /** Detected objects only: CLIP text-to-image cosine with the crop. Its own scale; never compare it with similarity. */
  imageSimilarity?: number | null;
  /** FILTERED searches only: the evidence that made this a result. LEXICAL: a whole-word fuzzy match of the name;
   * SYNONYM: a name match through the synonym table. */
  matchedBy?: "TEXT" | "IMAGE" | "TEXT_AND_IMAGE" | "LEXICAL" | "SYNONYM" | null;
  /** Detected objects only: the capture frame the bounding box was measured in. */
  sourceFrame?: string | null;
  /** Whether the position can be shown where it is (docs/search.md, "Ranking"). VALID results always rank first. */
  spatialStatus?: SpatialStatus | null;
  /** Detected objects placed with depth evidence: from two or more frames, or one. */
  localizationStatus?: "MULTI_VIEW" | "SINGLE_VIEW" | null;
  /** Their measured placement spread in metres: a spread, not an accuracy. */
  localizationUncertaintyM?: number | null;
  lexicalSimilarity?: number | null;
  rankScore?: number | null;
  scanVersionId?: string | null;
  pipelineRunId?: string | null;
  detectionArtifactId?: string | null;
  /** Detected objects: what produced them (detector and embedding model, artifact SHA-256, source frames). */
  provenance?: Record<string, unknown> | null;
  /** The label, category or a tag equals the query (or a synonym of it). */
  exactMatch?: boolean;
  matchedTerm?: string | null;
  /** "Nearest" with a position: walking-route length (ROUTE) or, where nothing can be routed, straight-line (STRAIGHT_LINE). */
  distanceMeters?: number | null;
  distanceKind?: "ROUTE" | "STRAIGHT_LINE" | null;
  /** Why there is no route distance: NO_ROUTE, NO_ACCESSIBLE_ROUTE, NAVMESH_NOT_READY, NOT_EVALUATED, ... */
  routeStatus?: string | null;
  /** Detected objects whose detector confidence is low or unknown; null for staff POIs. */
  lowConfidence?: boolean | null;
  /** Other detections of the same object (e.g. "sofa" and "couch" labels of one sofa) folded into this one. */
  duplicateIds?: string[];
  /** What staff recorded about wheelchair accessibility; null when nobody did. Never inferred. */
  accessible?: boolean | null;
  /** PENDING_EMBEDDING: found by name only until the embedding job reaches it. */
  indexStatus?: "INDEXED" | "PENDING_EMBEDDING";
}

export interface SearchCorrection {
  from: string;
  to: string;
}

/** Mirrors SearchDtos.SearchInterpretation: how the query was read. */
export interface SearchInterpretation {
  normalized: string;
  subject: string;
  lexicalTerms: string[];
  synonymGroups: string[];
  corrections: SearchCorrection[];
  targetKind: "NAMED_PLACE" | "CATEGORY" | "DESCRIPTION";
  spatialModifier: "NONE" | "NEAREST";
  spatialModifierApplied: boolean;
  spatialNote: "NO_POSITION" | "POSITION_NOT_ON_VERSION_FLOOR" | null;
  accessibilityRequired: boolean;
}

/** Mirrors SearchDtos.SearchResponse. matchType: "embedding" is real CLIP cosine similarity; "lexical_fallback" is
 * trigram text similarity, used only when the embedding model (services/vision) was unavailable -- never presented to
 * the user as the same thing. */
export interface SearchResponse {
  query: string;
  matchType: "embedding" | "lexical_fallback";
  /** What the venue has for the query; empty means nothing relevant was found. */
  results: SearchResult[];
  /** Only when results is empty: the nearest candidates, which did NOT clear the relevance threshold. */
  closestMatches?: SearchResult[];
  relevance?: "FILTERED" | "UNFILTERED" | "LEXICAL";
  interpretation?: SearchInterpretation;
  /** Every result is weak evidence (a low-confidence detection, or admitted by the image test alone). */
  lowConfidence?: boolean;
  /** Accessibility was asked for and nothing recorded as accessible matched: matches nobody recorded either way. */
  accessibilityUnconfirmed?: SearchResult[];
  /** Only when results is empty: other searches that return something here. */
  suggestions?: string[];
}

/** The searcher's position as the viewer knows it: a POI standing in for "where I am" (the route's From). */
export interface SearchPosition {
  label: string;
  floorId: string;
  x: number;
  y: number;
  z: number;
}

const ROUTE_STATUS: Record<string, string> = {
  NO_ROUTE: "no walkable route from your position",
  NO_ACCESSIBLE_ROUTE: "no step-free route from your position",
  FLOOR_CONNECTION_UNAVAILABLE: "no registered stairs or lift reaches it",
};

function metres(m: number): string {
  return m < 10 ? `${m.toFixed(1)} m` : `${Math.round(m)} m`;
}

/** The distance line of a result, or null when there is none to show. A straight-line figure is always called that. */
export function distanceNote(r: SearchResult): string | null {
  if (r.distanceMeters != null && r.distanceKind === "ROUTE") return `${metres(r.distanceMeters)} walk`;
  if (r.distanceMeters != null && r.distanceKind === "STRAIGHT_LINE") return `≈${metres(r.distanceMeters)} in a straight line (no walking route available)`;
  if (r.routeStatus && ROUTE_STATUS[r.routeStatus]) return ROUTE_STATUS[r.routeStatus];
  return null;
}

/** Short labels for a result: what kind of evidence it is. Never a claim the response does not make. */
export function resultBadges(r: SearchResult): string[] {
  const badges: string[] = [];
  if (r.source === "AUTO_DETECTED") badges.push("detected");
  if (r.lowConfidence) badges.push("low confidence");
  if (r.matchedBy === "IMAGE") badges.push("matched by appearance");
  if (r.indexStatus === "PENDING_EMBEDDING") badges.push("name match only");
  if (r.accessible === true) badges.push("accessible");
  const merged = r.duplicateIds?.length ?? 0;
  if (merged > 0) badges.push(`${merged + 1} detections merged`);
  return badges;
}

/** How well the result matched, in words: a percentage only where it is a CLIP similarity of a judged match. */
export function matchNote(r: SearchResult, matchType: SearchResponse["matchType"]): string {
  if (matchType === "lexical_fallback") return "text match";
  if (r.indexStatus === "PENDING_EMBEDDING" || r.matchedBy === "LEXICAL") return "name match";
  if (r.matchedBy === "SYNONYM") return `name match (“${r.matchedTerm ?? ""}”)`;
  return `${Math.round(r.similarity * 100)}% match`;
}

/** Whether "Navigate" can be offered: a route needs a position in the frame being viewed, on a floor. */
export function canNavigate(r: SearchResult): boolean {
  return r.floorId != null && (r.spatialStatus == null || r.spatialStatus === "VALID");
}

/** Lines explaining how the query was read, in display order. */
export function interpretationNotes(response: SearchResponse, position: SearchPosition | null): string[] {
  const i = response.interpretation;
  if (!i) return [];
  const notes: string[] = [];
  if (i.corrections.length > 0) {
    notes.push(`Showing results for “${i.subject}” (you typed ${i.corrections.map((c) => `“${c.from}”`).join(", ")}).`);
  }
  if (i.accessibilityRequired) notes.push("Only places staff recorded as wheelchair accessible.");
  if (i.spatialModifier === "NEAREST") {
    if (i.spatialModifierApplied) {
      const straight = response.results.some((r) => r.distanceKind === "STRAIGHT_LINE");
      const routed = response.results.some((r) => r.distanceKind === "ROUTE");
      notes.push(straight && !routed
        ? `Nearest first, by straight-line distance from ${position?.label ?? "your position"}: this floor has no walking routes yet.`
        : `Nearest first, by walking distance from ${position?.label ?? "your position"}.`);
    } else if (i.spatialNote === "POSITION_NOT_ON_VERSION_FLOOR") {
      notes.push("Your position is on another floor than this reconstruction, so results are not sorted by distance.");
    } else {
      notes.push("Choose where you are (Navigation route → From) to sort by distance.");
    }
  }
  return notes;
}

/** The message when nothing matched, or null when there are results. */
export function emptyMessage(response: SearchResponse): string | null {
  if (response.results.length > 0) return null;
  if ((response.accessibilityUnconfirmed?.length ?? 0) > 0) {
    return `Nothing matching “${response.query}” is recorded as wheelchair accessible. These match, but nobody recorded whether they are accessible:`;
  }
  if ((response.closestMatches?.length ?? 0) > 0) return `Nothing matching “${response.query}” here. Closest:`;
  return "No matches.";
}
