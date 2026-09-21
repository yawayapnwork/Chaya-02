"use client";

import { InMemoryWebStorage, UserManager, WebStorageStateStore } from "oidc-client-ts";
import { publicConfig } from "./env";

// Authorization-code flow with PKCE against Keycloak. Tokens live in memory only (not in
// localStorage); a full page reload signs in again silently through the Keycloak session.
let manager: UserManager | null = null;

export function userManager(): UserManager {
  if (!manager) {
    const cfg = publicConfig();
    manager = new UserManager({
      authority: cfg.oidcIssuer,
      client_id: cfg.oidcClientId,
      redirect_uri: `${window.location.origin}/auth/callback`,
      post_logout_redirect_uri: window.location.origin,
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
  const mgr = userManager();
  let user = await mgr.getUser();
  if (user && user.expired) {
    try {
      user = await mgr.signinSilent();
    } catch {
      user = null;
    }
  }
  if (!user || user.expired) throw new NotSignedInError();
  return user.access_token;
}

export function signIn(returnTo: string): Promise<void> {
  return userManager().signinRedirect({ state: { returnTo } });
}

export function signOut(): Promise<void> {
  return userManager().signoutRedirect();
}
