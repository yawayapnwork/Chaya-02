/** How a search result's position may be presented (docs/search.md, "Ranking"). No imports: unit-tested with node --test. */

export type SpatialStatus = "VALID" | "UNVERIFIED" | "STALE_FRAME" | "UNBOUND";

/** What the panel says about a result's position, or null when there is nothing to qualify. Pure. A spread is rounded up
 * to the next 0.1 m and is never presented as an accuracy. */
export interface LocatedResult {
  spatialStatus?: SpatialStatus | null;
  localizationStatus?: "MULTI_VIEW" | "SINGLE_VIEW" | null;
  localizationUncertaintyM?: number | null;
}

export function locationNote(r: LocatedResult): string | null {
  switch (r.spatialStatus) {
    case "UNVERIFIED":
      return "location unverified (placed without depth evidence)";
    case "STALE_FRAME":
      return "location from an older reconstruction";
    case "UNBOUND":
      return "location not calibrated";
    default:
      break;
  }
  if (r.localizationStatus == null) return null;
  const views = r.localizationStatus === "MULTI_VIEW" ? "seen from several views" : "seen from one view";
  const spread = r.localizationUncertaintyM;
  return spread == null ? views : `${views}, placement spread ~${(Math.ceil(spread * 10) / 10).toFixed(1)} m`;
}
