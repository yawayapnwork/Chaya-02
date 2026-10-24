import { defineConfig, devices } from "@playwright/test";

// Smoke tests only: no real backend/Keycloak is required. Every test mocks the exact JSON API responses it
// needs via page.route(); nothing here asserts against a fabricated 3D scene. The one scene that is loaded
// (e2e/ksplat-viewer.spec.ts) is the production encoder's real .ksplat, rendered by the real GaussianSplats3D.
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // One retry in CI only to record a trace (trace: on-first-retry); a test that needed it fails the run anyway
  // (failOnFlakyTests), so an intermittent failure is fixed, never hidden. 65/65 passed twice with --retries=0 and the
  // runner's two workers (docs/TEST_REPRODUCIBILITY.md, 2026-10-24).
  retries: process.env.CI ? 1 : 0,
  failOnFlakyTests: !!process.env.CI,
  reporter: "list",
  use: {
    baseURL: "http://localhost:3100",
    trace: "on-first-retry",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    // The production build, as shipped. `next dev` compiled and served unoptimised bundles on demand, and under the
    // parallel software-WebGL specs the silent sign-in iframe (lib/auth.ts, 5 s timeout) sometimes missed its window,
    // so a reload showed "Sign in" (ui-audit.spec.ts, 2026-10-24). PLAYWRIGHT_DEV=1 uses the dev server for local work.
    // localhost, not 127.0.0.1: Next's dev server blocks cross-origin requests to its own dev
    // resources (chunks, HMR) from an origin it doesn't recognise as itself (see allowedDevOrigins).
    command: process.env.PLAYWRIGHT_DEV ? "npm run dev -- --port 3100" : "npm run build && npm run start -- --port 3100",
    url: "http://localhost:3100",
    reuseExistingServer: !process.env.CI,
    timeout: 300_000, // includes the production build
    env: {
      // Browser configuration: the runtime CHAYA_PUBLIC_* that the production server reads per request (app/layout.tsx,
      // proxy.ts), and the NEXT_PUBLIC_* fallbacks the dev server uses.
      CHAYA_PUBLIC_API_BASE_URL: "http://localhost:3100/mock-api",
      CHAYA_PUBLIC_OIDC_ISSUER: "http://localhost:3100/mock-oidc",
      CHAYA_PUBLIC_OIDC_CLIENT_ID: "chaya-web-e2e",
      NEXT_PUBLIC_API_BASE_URL: "http://localhost:3100/mock-api",
      NEXT_PUBLIC_OIDC_ISSUER: "http://localhost:3100/mock-oidc",
      NEXT_PUBLIC_OIDC_CLIENT_ID: "chaya-web-e2e",
      NEXT_TELEMETRY_DISABLED: "1",
    },
  },
});
