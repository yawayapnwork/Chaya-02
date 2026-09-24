// Runtime public configuration: the values the browser needs (API origin, OIDC issuer, client id). None of them is
// secret.
//
// Production images are built ONCE and promoted from staging to production, so these values cannot be baked into the
// bundle at build time (which is what NEXT_PUBLIC_* does). Instead the server reads CHAYA_PUBLIC_* at request time
// (app/layout.tsx) and hands them to the browser as window.__CHAYA_PUBLIC_CONFIG__. NEXT_PUBLIC_* remains a fallback
// for `next dev` and the Playwright e2e setup.

export interface PublicConfig {
  apiBaseUrl: string;
  oidcIssuer: string;
  oidcClientId: string;
}

export const RUNTIME_KEYS: Record<keyof PublicConfig, string> = {
  apiBaseUrl: "CHAYA_PUBLIC_API_BASE_URL",
  oidcIssuer: "CHAYA_PUBLIC_OIDC_ISSUER",
  oidcClientId: "CHAYA_PUBLIC_OIDC_CLIENT_ID",
};

/** Runtime values win; build-time values fill the gaps. Reports which keys are missing instead of throwing. */
export function resolvePublicConfig(
  runtimeEnv: Record<string, string | undefined>,
  buildTime: Partial<Record<keyof PublicConfig, string | undefined>>,
): { config: PublicConfig | null; missing: string[] } {
  const out: Partial<PublicConfig> = {};
  const missing: string[] = [];
  for (const key of Object.keys(RUNTIME_KEYS) as (keyof PublicConfig)[]) {
    const value = (runtimeEnv[RUNTIME_KEYS[key]] || buildTime[key] || "").trim();
    if (!value) missing.push(RUNTIME_KEYS[key]);
    else out[key] = key === "apiBaseUrl" ? value.replace(/\/+$/, "") : value;
  }
  return missing.length ? { config: null, missing } : { config: out as PublicConfig, missing };
}

/** A <script> body that sets the config. JSON is escaped so no value can close the script element. */
export function configScript(config: PublicConfig): string {
  const json = JSON.stringify(config).replace(/</g, "\\u003c").replace(/\u2028/g, "\\u2028").replace(/\u2029/g, "\\u2029");
  return `window.__CHAYA_PUBLIC_CONFIG__=${json};`;
}
