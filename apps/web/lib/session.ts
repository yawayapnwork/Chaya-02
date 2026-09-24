"use client";

// Extends lib/auth.ts's Keycloak session with an optional public-viewer session (a venue-scoped opaque
// token traded for a public link secret, see PublicViewerService on the backend). Exactly one of the two
// is ever active in a given tab. The public token lives in memory only, same policy as the OIDC token in
// lib/auth.ts: never localStorage, so it does not survive a reload (the link must be exchanged again).

import { NotSignedInError, accessToken as oidcAccessToken } from "./auth";

interface PublicSession {
  token: string;
  expiresAtMs: number;
  venueId: string;
}

let publicSession: PublicSession | null = null;

export function setPublicViewerSession(token: string, expiresAt: string, venueId: string): void {
  publicSession = { token, expiresAtMs: Date.parse(expiresAt), venueId };
}

export function clearPublicViewerSession(): void {
  publicSession = null;
}

function livePublicSession(): PublicSession | null {
  if (publicSession && publicSession.expiresAtMs > Date.now()) return publicSession;
  publicSession = null;
  return null;
}

/** The venue a live public-viewer session is scoped to, or null if there is none (normal signed-in mode). */
export function publicViewerVenueId(): string | null {
  return livePublicSession()?.venueId ?? null;
}

/** The API header that authenticates the current session. A public-viewer token is an opaque token, not a
 * JWT: the backend reads it only from X-Chaya-Viewer-Token (PublicViewerTokenFilter) and rejects it as a
 * bearer token. Otherwise the OIDC access token as a bearer. Throws NotSignedInError when neither is available. */
export async function currentAuthHeaders(): Promise<Record<string, string>> {
  const live = livePublicSession();
  if (live) return { "X-Chaya-Viewer-Token": live.token };
  return { Authorization: `Bearer ${await oidcAccessToken()}` };
}

export { NotSignedInError };
