import assert from "node:assert/strict";
import { test } from "node:test";
import { contentSecurityPolicy, FRAMEABLE_PATH, origin } from "./security-headers.ts";

const directive = (policy: string, name: string) => policy.split("; ").find((d) => d.startsWith(`${name} `));

test("frame-src and connect-src name the identity provider and API of the running configuration", () => {
  const { reportOnly } = contentSecurityPolicy(
    { apiBaseUrl: "https://api.example.org/api/v1", oidcIssuer: "http://localhost:18081/realms/chaya" },
    { isDev: false, pathname: "/viewer" },
  );
  assert.equal(directive(reportOnly, "frame-src"), "frame-src 'self' http://localhost:18081");
  assert.equal(directive(reportOnly, "connect-src"), "connect-src 'self' https://api.example.org http://localhost:18081");
});

test("without configuration the policy still parses and allows only this origin", () => {
  const { reportOnly } = contentSecurityPolicy({}, { isDev: false, pathname: "/" });
  assert.equal(directive(reportOnly, "frame-src"), "frame-src 'self'");
  assert.equal(directive(reportOnly, "connect-src"), "connect-src 'self'");
  assert.equal(origin("not a url"), "");
});

test("only the sign-in callback may be framed, and only by this app", () => {
  const page = contentSecurityPolicy({}, { isDev: false, pathname: "/viewer" });
  const callback = contentSecurityPolicy({}, { isDev: false, pathname: FRAMEABLE_PATH });
  assert.equal(directive(page.enforced, "frame-ancestors"), "frame-ancestors 'none'");
  assert.equal(directive(page.reportOnly, "frame-ancestors"), "frame-ancestors 'none'");
  assert.equal(directive(callback.enforced, "frame-ancestors"), "frame-ancestors 'self'");
  assert.equal(directive(callback.reportOnly, "frame-ancestors"), "frame-ancestors 'self'");
});

test("the enforced policy keeps plugins, <base> and form targets locked; unsafe-eval only in development", () => {
  const prod = contentSecurityPolicy({}, { isDev: false, pathname: "/" });
  assert.match(prod.enforced, /object-src 'none'/);
  assert.match(prod.enforced, /base-uri 'self'/);
  assert.match(prod.enforced, /form-action 'self'/);
  assert.doesNotMatch(prod.reportOnly, /'unsafe-eval'/); // 'wasm-unsafe-eval' (the splat viewer's WASM) stays
  assert.match(contentSecurityPolicy({}, { isDev: true, pathname: "/" }).reportOnly, /'unsafe-eval'/);
});
