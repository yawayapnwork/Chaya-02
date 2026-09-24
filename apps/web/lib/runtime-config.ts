// Server-side only by use (app/layout.tsx, app/api/health); holds nothing secret, so no server-only guard is needed.
import { resolvePublicConfig } from "./public-config";

/** Server-only: the public config as of this request. Runtime CHAYA_PUBLIC_* first, build-time NEXT_PUBLIC_* second. */
export function runtimePublicConfig() {
  return resolvePublicConfig(process.env, {
    apiBaseUrl: process.env.NEXT_PUBLIC_API_BASE_URL,
    oidcIssuer: process.env.NEXT_PUBLIC_OIDC_ISSUER,
    oidcClientId: process.env.NEXT_PUBLIC_OIDC_CLIENT_ID,
  });
}
