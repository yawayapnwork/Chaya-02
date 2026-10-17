import type { Page } from "@playwright/test";
import { mockJson } from "./viewer-fixture";

// Shared by the specs of signed-in pages: lib/auth.ts runs the real authorization-code + PKCE flow (oidc-client-ts), and
// this mock provider only answers its endpoints. Tokens are in memory only, so each test signs in through the page.

function b64url(o: unknown): string {
  return Buffer.from(JSON.stringify(o)).toString("base64url");
}

/**
 * A minimal OIDC provider for oidc-client-ts: discovery, an authorize endpoint that approves at once, and a token endpoint.
 * `session: false` is a provider where nobody is signed in yet: a silent request (prompt=none, lib/auth.ts currentUser)
 * gets `login_required`, as Keycloak answers it, while an interactive sign-in still succeeds.
 */
export async function mockOidc(page: Page, { session = true }: { session?: boolean } = {}) {
  const issuer = "http://localhost:3100/mock-oidc";
  await mockJson(page, "**/mock-oidc/.well-known/openid-configuration", {
    issuer,
    authorization_endpoint: `${issuer}/auth`,
    token_endpoint: `${issuer}/token`,
    end_session_endpoint: `${issuer}/logout`,
    jwks_uri: `${issuer}/certs`,
  });
  await page.route("**/mock-oidc/auth?**", (route) => {
    const u = new URL(route.request().url());
    if (!session && u.searchParams.get("prompt") === "none") {
      const refused = `${u.searchParams.get("redirect_uri")}?error=login_required&state=${u.searchParams.get("state")}`;
      return route.fulfill({ status: 302, headers: { location: refused } });
    }
    const back = `${u.searchParams.get("redirect_uri")}?code=test-code&state=${u.searchParams.get("state")}`;
    return route.fulfill({ status: 302, headers: { location: back } });
  });
  const t = Math.floor(Date.now() / 1000);
  const idToken = `${b64url({ alg: "none", typ: "JWT" })}.${b64url({ iss: issuer, aud: "chaya-web-e2e", sub: "ops-user", iat: t, exp: t + 3600 })}.`;
  await mockJson(page, "**/mock-oidc/token", {
    access_token: "test-access-token", token_type: "Bearer", expires_in: 3600, id_token: idToken, scope: "openid",
  });
}
