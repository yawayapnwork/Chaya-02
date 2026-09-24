import assert from "node:assert/strict";
import { test } from "node:test";
import { configScript, resolvePublicConfig } from "./public-config.ts";

test("runtime CHAYA_PUBLIC_* values win over build-time values", () => {
  const { config, missing } = resolvePublicConfig(
    { CHAYA_PUBLIC_API_BASE_URL: "https://api.example.org/", CHAYA_PUBLIC_OIDC_ISSUER: "https://id.example.org/realms/chaya", CHAYA_PUBLIC_OIDC_CLIENT_ID: "chaya-web" },
    { apiBaseUrl: "http://localhost:8080", oidcIssuer: "http://localhost:8081", oidcClientId: "dev" },
  );
  assert.deepEqual(missing, []);
  assert.deepEqual(config, { apiBaseUrl: "https://api.example.org", oidcIssuer: "https://id.example.org/realms/chaya", oidcClientId: "chaya-web" });
});

test("build-time values fill gaps; missing keys are named, not defaulted", () => {
  const partial = resolvePublicConfig({}, { apiBaseUrl: "http://localhost:8080" });
  assert.equal(partial.config, null);
  assert.deepEqual(partial.missing, ["CHAYA_PUBLIC_OIDC_ISSUER", "CHAYA_PUBLIC_OIDC_CLIENT_ID"]);
});

test("the injected script cannot be broken out of", () => {
  const s = configScript({ apiBaseUrl: "https://x</script><script>alert(1)</script>", oidcIssuer: "i", oidcClientId: "c" });
  assert.ok(!s.includes("</script>"));
  assert.ok(s.startsWith("window.__CHAYA_PUBLIC_CONFIG__="));
});
