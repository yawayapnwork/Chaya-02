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
const api = origin(process.env.NEXT_PUBLIC_API_BASE_URL);
const idp = origin(process.env.NEXT_PUBLIC_OIDC_ISSUER);

const enforced = ["frame-ancestors 'none'", "object-src 'none'", "base-uri 'self'", "form-action 'self'"].join("; ");

const reportOnly = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'${isDev ? " 'unsafe-eval'" : ""}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' blob: data:",
  "media-src 'self' blob:",
  "font-src 'self'",
  "worker-src 'self' blob:",
  `connect-src 'self' ${api} ${idp}`.trim(),
  `frame-src ${idp || "'none'"}`,
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

const nextConfig: NextConfig = {
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
    ];
  },
};

export default nextConfig;
