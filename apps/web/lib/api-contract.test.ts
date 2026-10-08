import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { test } from "node:test";

// Every backend path this client calls must exist in the published contract (packages/contracts/openapi/v1.yaml),
// which the backend's OpenApiContractTest keeps equal to what the controllers serve. A path removed from the API,
// or a typo here, fails this test instead of a user's request.

const LIB = new URL("./", import.meta.url);
const CONTRACT = new URL("../../../packages/contracts/openapi/v1.yaml", import.meta.url);

/** Path templates of the contract, each segment either a literal or null for a {parameter}. */
function contractPaths(): (string | null)[][] {
  const yaml = readFileSync(CONTRACT, "utf8");
  return [...yaml.matchAll(/^ {2}(\/api\/v1\/[^:\s]*):\s*$/gm)].map((m) =>
    m[1].slice("/api/v1".length).split("/").slice(1).map((s) => (s.startsWith("{") ? null : s)),
  );
}

/** Backend paths in this client's code: string and template literals starting at a top-level API resource. */
function clientPaths(): { file: string; path: string }[] {
  const out: { file: string; path: string }[] = [];
  for (const file of readdirSync(LIB).filter((f) => f.endsWith(".ts") && !f.endsWith(".test.ts"))) {
    let src = readFileSync(new URL(file, LIB), "utf8");
    // Inline one-line path helpers, e.g. const base = (venueId: string) => `/venues/${venueId}/ops`;
    for (const h of [...src.matchAll(/^const (\w+) = \([^)]*\) => `([^`]+)`;$/gm)]) {
      src = src.replace(h[0], "").replaceAll(new RegExp(`\\$\\{${h[1]}\\([^)]*\\)\\}`, "g"), h[2]);
    }
    const literal = /["'`]((?:\/api\/v1)?\/(?:venues|navigation|public|health|version|internal|erasures|audit-log)(?=[/?"'`$])[^"'`]*)["'`]/g;
    for (const m of src.matchAll(literal)) {
      const path = m[1]
        .replace(/^\/api\/v1/, "")
        .replace(/\?.*$/, "") // query string
        .replace(/([^/])\$\{.*$/, "$1") // an expression glued to a segment: an optional query string
        .replace(/\/\$\{[^}]+\}/g, "/{}"); // a whole segment
      out.push({ file, path });
    }
  }
  return out;
}

function served(path: string, contract: (string | null)[][]): boolean {
  const segments = path.split("/").slice(1);
  return contract.some(
    (t) => t.length === segments.length && t.every((lit, i) => lit === null || segments[i] === "{}" || segments[i] === lit),
  );
}

test("every backend path the web client calls is in the API contract", () => {
  const contract = contractPaths();
  assert.ok(contract.length > 60, `expected the generated contract, found ${contract.length} paths`);
  const calls = clientPaths();
  assert.ok(calls.length > 40, `expected the client's API calls, found ${calls.length}`);
  const missing = calls.filter((c) => !served(c.path, contract)).map((c) => `${c.file}: ${c.path}`);
  assert.deepEqual(missing, []);
});
