import { defineConfig, devices } from "@playwright/test";

// Smoke tests only: no real backend/Keycloak is required. Every test mocks the exact API responses it
// needs via page.route(); nothing here asserts against a fabricated 3D scene -- the ksplat-loading tests
// intentionally stop at "download started" rather than faking a real GaussianSplats3D render.
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: "list",
  use: {
    baseURL: "http://localhost:3100",
    trace: "on-first-retry",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    // localhost, not 127.0.0.1: Next's dev server blocks cross-origin requests to its own dev
    // resources (chunks, HMR) from an origin it doesn't recognise as itself (see allowedDevOrigins).
    command: "npm run dev -- --port 3100",
    url: "http://localhost:3100",
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
    env: {
      NEXT_PUBLIC_API_BASE_URL: "http://localhost:3100/mock-api",
      NEXT_PUBLIC_OIDC_ISSUER: "http://localhost:3100/mock-oidc",
      NEXT_PUBLIC_OIDC_CLIENT_ID: "chaya-web-e2e",
    },
  },
});
