import type { NextConfig } from "next";

// Security headers (docs/security-hardening.md, "Web app").
//
// * Referrer-Policy no-referrer: public viewer links carry their secret in the URL (?link=...); no referrer means it
//   is never sent to another origin.
// * The Content Security Policy (enforced and report-only) is NOT set here: it names the API and identity-provider
//   origins of the running container, and next.config is evaluated at BUILD time. proxy.ts sets it per request
//   (lib/security-headers.ts).

const nextConfig: NextConfig = {
  // Self-contained server bundle (.next/standalone) for the container image: no node_modules install at runtime.
  output: "standalone",
  async headers() {
    return [
      {
        source: "/(.*)",
        headers: [
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
        // Later rules override earlier ones for the same key (Next.js headers): framing by this app only, for this route
        // (its frame-ancestors 'self' comes from proxy.ts).
        source: "/auth/callback",
        headers: [{ key: "X-Frame-Options", value: "SAMEORIGIN" }],
      },
    ];
  },
};

export default nextConfig;
