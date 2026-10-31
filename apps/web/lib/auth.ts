"use client";

import { InMemoryWebStorage, type User, UserManager, WebStorageStateStore } from "oidc-client-ts";
import { publicConfig } from "./env.ts";

// Authorization-code flow with PKCE against Keycloak. Tokens live in memory only (not in localStorage), so a page load
// starts without them: currentUser() restores the Keycloak session with a silent sign-in (prompt=none in a hidden iframe,
// answered by app/auth/callback). Where that cannot work (no Keycloak session, or the browser blocks Keycloak's cookie in
// the iframe because the app and Keycloak are not on the same site), the page shows its "Sign in" button as before.
let manager: UserManager | null = null;
let restoring: Promise<User | null> | null = null;

export function userManager(): UserManager {
  if (!manager) {
    const cfg = publicConfig();
    manager = new UserManager({
      authority: cfg.oidcIssuer,
      client_id: cfg.oidcClientId,
      redirect_uri: `${window.location.origin}/auth/callback`,
      post_logout_redirect_uri: window.location.origin,
      silent_redirect_uri: `${window.location.origin}/auth/callback`,
      // A silent attempt that gets no answer (Keycloak unreachable) gives up quickly; the page then offers "Sign in".
      silentRequestTimeoutInSeconds: 5,
      response_type: "code",
      scope: "openid",
      automaticSilentRenew: true,
      userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    });
  }
  return manager;
}

export class NotSignedInError extends Error {
  constructor() {
    super("You are not signed in.");
  }
}

/** A currently valid access token, refreshed if needed. Throws NotSignedInError if none can be had. */
export async function accessToken(): Promise<string> {
  // currentUser() renews an expired token and restores a session after a page load, both silently.
  const user = await currentUser();
  if (!user || user.expired) throw new NotSignedInError();
  return user.access_token;
}

/**
 * The signed-in user, restoring the Keycloak session silently when this page load has none yet. null when nobody is
 * signed in or the silent attempt fails. Concurrent callers (the navigation bar and the page) share one attempt.
 */
export async function currentUser(): Promise<User | null> {
  const mgr = userManager();
  const user = await mgr.getUser();
  if (user && !user.expired) return user;
  if (!restoring) {
    restoring = mgr.signinSilent().catch(() => null).finally(() => { restoring = null; });
  }
  return restoring;
}

/** Signs in and comes back to `returnTo`; by default the current page with its query, so a deep link survives sign-in. */
export function signIn(returnTo: string = window.location.pathname + window.location.search): Promise<void> {
  return userManager().signinRedirect({ state: { returnTo } });
}

/** Ends the Keycloak session too (not only this tab's tokens), then returns to the start page. */
export function signOut(): Promise<void> {
  return userManager().signoutRedirect();
}
