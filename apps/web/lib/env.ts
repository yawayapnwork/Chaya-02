// Browser-side configuration. Production: runtime values injected by app/layout.tsx from CHAYA_PUBLIC_* (see
// lib/public-config.ts). Development and e2e: NEXT_PUBLIC_* inlined at build time, referenced literally below.
// Nothing secret belongs here.

import type { PublicConfig } from "./public-config";

declare global {
  interface Window {
    __CHAYA_PUBLIC_CONFIG__?: PublicConfig;
  }
}

function required(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} is not set. See .env.example.`);
  return value;
}

export function publicConfig(): PublicConfig {
  const injected = typeof window !== "undefined" ? window.__CHAYA_PUBLIC_CONFIG__ : undefined;
  if (injected) return injected;
  return {
    apiBaseUrl: required("CHAYA_PUBLIC_API_BASE_URL (or NEXT_PUBLIC_API_BASE_URL)", process.env.NEXT_PUBLIC_API_BASE_URL).replace(/\/+$/, ""),
    oidcIssuer: required("CHAYA_PUBLIC_OIDC_ISSUER (or NEXT_PUBLIC_OIDC_ISSUER)", process.env.NEXT_PUBLIC_OIDC_ISSUER),
    oidcClientId: required("CHAYA_PUBLIC_OIDC_CLIENT_ID (or NEXT_PUBLIC_OIDC_CLIENT_ID)", process.env.NEXT_PUBLIC_OIDC_CLIENT_ID),
  };
}
