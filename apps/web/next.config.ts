import type { NextConfig } from "next";

// Security headers (docs/security-hardening.md, "Web app").
//
// * Referrer-Policy no-referrer: public viewer links carry their secret in the URL (?link=...); no referrer means it
//   is never sent to another origin.
// * Framing, plugins, <base> and form targets are locked down with an enforced CSP (these cannot break the app).
// * The full resource policy is sent as Content-Security-Policy-Report-Only until it has been verified in a browser
//   against the splat viewer (web workers, WASM) and the capture page (camera): browsers report violations in the
//   console without blocking. Promote it to enforcing once it is clean.

function origin(url: string | undefined): string {
  try {
    return url ? new URL(url).origin : "";
  } catch {
    return "";
  }
}

const isDev = process.env.NODE_ENV === "development";
// next.config is evaluated at BUILD time (and serialized into the standalone server), so these origins come from the
// build environment, not the running container. The resource policy is report-only; if the image is built without
// them, connect-src/frame-src just report more. Promoting the CSP to enforcing needs per-request headers (a proxy).
const api = origin(process.env.CHAYA_PUBLIC_API_BASE_URL ?? process.env.NEXT_PUBLIC_API_BASE_URL);
const idp = origin(process.env.CHAYA_PUBLIC_OIDC_ISSUER ?? process.env.NEXT_PUBLIC_OIDC_ISSUER);

const enforced = ["frame-ancestors 'none'", "object-src 'none'", "base-uri 'self'", "form-action 'self'"].join("; ");

// The sign-in callback alone may be framed, and only by this app: the silent sign-in after a page load (lib/auth.ts
// currentUser) loads it in a hidden iframe. With frame-ancestors 'none' the browser blocked it, and every reload signed the
// user out. Everything else stays unframeable.
const enforcedCallback = enforced.replace("frame-ancestors 'none'", "frame-ancestors 'self'");

const reportOnly = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'${isDev ? " 'unsafe-eval'" : ""}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' blob: data:",
  "media-src 'self' blob:",
  "font-src 'self'",
  "worker-src 'self' blob:",
  `connect-src 'self' ${api} ${idp}`.trim(),
  `frame-src 'self' ${idp}`.trim(), // the silent sign-in iframe: the identity provider, then this app's callback
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

const nextConfig: NextConfig = {
  // Self-contained server bundle (.next/standalone) for the container image: no node_modules install at runtime.
  output: "standalone",
  async headers() {
    return [
      {
        source: "/(.*)",
        headers: [
          { key: "Content-Security-Policy", value: enforced },
          { key: "Content-Security-Policy-Report-Only", value: reportOnly },
          { key: "Referrer-Policy", value: "no-referrer" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          {
            key: "Permissions-Policy",
            // camera + microphone: capture and voice search; motion sensors: capture HUD heading; XR: AR page.
            value: "camera=(self), microphone=(self), accelerometer=(self), gyroscope=(self), magnetometer=(self), xr-spatial-tracking=(self), geolocation=(), payment=(), usb=()",
          },
        ],
      },
      {
        // Later rules override earlier ones for the same key (Next.js headers): framing by this app only, for this route.
        source: "/auth/callback",
        headers: [
          { key: "Content-Security-Policy", value: enforcedCallback },
          { key: "Content-Security-Policy-Report-Only", value: reportOnly.replace("frame-ancestors 'none'", "frame-ancestors 'self'") },
          { key: "X-Frame-Options", value: "SAMEORIGIN" },
        ],
      },
    ];
  },
};

export default nextConfig;
