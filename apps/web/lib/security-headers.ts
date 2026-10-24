// Content Security Policy (docs/security-hardening.md, "Web app"), built per request by proxy.ts.
//
// * Framing, plugins, <base> and form targets are locked down with an enforced CSP (these cannot break the app).
// * The full resource policy is sent as Content-Security-Policy-Report-Only until it has been verified in a browser
//   against the splat viewer (web workers, WASM) and the capture page (camera): browsers report violations in the
//   console without blocking. Promote it to enforcing once it is clean.
//
// The API and identity-provider origins come from the RUNNING container's CHAYA_PUBLIC_* (one image is promoted from
// staging to production, lib/public-config.ts). They used to be read in next.config.ts, which is evaluated at build
// time: no image build passes them, so frame-src lacked the identity provider and every silent sign-in logged a CSP
// violation (acceptance browser checks A6/B6, docs/VALIDATION_2026-10-19.md D5).

export interface CspOrigins {
  apiBaseUrl?: string;
  oidcIssuer?: string;
}

export interface CspHeaders {
  enforced: string;
  reportOnly: string;
}

export function origin(url: string | undefined): string {
  try {
    return url ? new URL(url).origin : "";
  } catch {
    return "";
  }
}

// The sign-in callback alone may be framed, and only by this app: the silent sign-in after a page load (lib/auth.ts
// currentUser) loads it in a hidden iframe. With frame-ancestors 'none' the browser blocked it, and every reload signed the
// user out. Everything else stays unframeable.
export const FRAMEABLE_PATH = "/auth/callback";

export function contentSecurityPolicy(origins: CspOrigins, opts: { isDev: boolean; pathname: string }): CspHeaders {
  const api = origin(origins.apiBaseUrl);
  const idp = origin(origins.oidcIssuer);
  const ancestors = opts.pathname === FRAMEABLE_PATH ? "frame-ancestors 'self'" : "frame-ancestors 'none'";
  const enforced = [ancestors, "object-src 'none'", "base-uri 'self'", "form-action 'self'"].join("; ");
  const reportOnly = [
    "default-src 'self'",
    `script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'${opts.isDev ? " 'unsafe-eval'" : ""}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' blob: data:",
    "media-src 'self' blob:",
    "font-src 'self'",
    "worker-src 'self' blob:",
    `connect-src 'self' ${api} ${idp}`.trim().replace(/\s+/g, " "),
    `frame-src 'self' ${idp}`.trim(), // the silent sign-in iframe: the identity provider, then this app's callback
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    ancestors,
  ].join("; ");
  return { enforced, reportOnly };
}
