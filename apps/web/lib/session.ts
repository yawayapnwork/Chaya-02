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

/** A currently valid bearer token: the public-viewer token if one is active, otherwise the OIDC token.
 * Throws NotSignedInError when neither is available. */
export async function currentAuthToken(): Promise<string> {
  const live = livePublicSession();
  if (live) return live.token;
  return oidcAccessToken();
}

export { NotSignedInError };
