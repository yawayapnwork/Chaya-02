/**
 * The viewer's selection in its URL (components/ViewerWorkspace.tsx), so a refresh, a sign-in round trip or a shared link
 * opens the same venue, floor and version. Pure: the query string for a selection, given the current one.
 */
export interface ViewerSelection {
  venueId: string;
  floorId: string;
  runId: string;
  /** A public viewing link: its venue is implied by the link and never written. */
  publicLink: boolean;
}

export function viewerQuery(current: string, sel: ViewerSelection): string {
  const q = new URLSearchParams(current);
  const set = (key: string, value: string) => (value ? q.set(key, value) : q.delete(key));
  if (sel.publicLink) q.delete("venue");
  else set("venue", sel.venueId);
  set("floor", sel.floorId);
  set("run", sel.runId);
  const s = q.toString();
  return s ? `?${s}` : "";
}

/** The AR page for the viewer's venue and floor (app/ar reads ?venue= and ?floor=), or null without a venue. */
export function arHref(venueId: string, floorId: string): string | null {
  if (!venueId) return null;
  const q = new URLSearchParams({ venue: venueId });
  if (floorId) q.set("floor", floorId);
  return `/ar?${q.toString()}`;
}
