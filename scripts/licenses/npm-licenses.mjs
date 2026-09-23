// Lists the licenses of the installed npm dependency tree of apps/web, as declared in each installed package's own
// package.json, and checks them against scripts/licenses/policy.json.
//   node scripts/licenses/npm-licenses.mjs [--prod] [--json]     (run from the repo root, after `npm ci` in apps/web)
// Exit status: 0 = every license allowed or reviewed-allowed, 1 = at least one needs a decision (review/deny/unknown).
// Declared metadata is what package authors wrote; it is not a verified legal analysis.
import { execSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const web = resolve(here, "../../apps/web");
const policy = JSON.parse(readFileSync(join(here, "policy.json"), "utf8"));
const prod = process.argv.includes("--prod");
const asJson = process.argv.includes("--json");

let raw;
try {
  raw = execSync(`npm ls --all --json${prod ? " --omit=dev" : ""}`, { cwd: web, maxBuffer: 1 << 28, stdio: ["ignore", "pipe", "ignore"] });
} catch (e) {
  raw = e.stdout; // npm ls exits non-zero on peer-dependency warnings but still prints the tree
}
const tree = JSON.parse(raw.toString());

function declaredLicense(pkgJsonPath) {
  const pj = JSON.parse(readFileSync(pkgJsonPath, "utf8"));
  if (typeof pj.license === "string") return pj.license;
  if (pj.license?.type) return pj.license.type;
  if (Array.isArray(pj.licenses)) return pj.licenses.map((l) => l.type ?? l).join(" OR ");
  return "UNKNOWN";
}

// Resolve each package the way Node does: from the dependent's directory upwards.
function findPackageJson(fromDir, name) {
  let dir = fromDir;
  for (;;) {
    const p = join(dir, "node_modules", name, "package.json");
    if (existsSync(p)) return p;
    const parent = dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

const found = new Map();
function walk(deps, fromDir) {
  for (const [name, d] of Object.entries(deps ?? {})) {
    if (!d.version) continue;
    const p = findPackageJson(fromDir, name);
    const key = `${name}@${d.version}`;
    if (!found.has(key)) found.set(key, { name, version: d.version, license: p ? declaredLicense(p) : "UNKNOWN" });
    walk(d.dependencies, p ? dirname(p) : fromDir);
  }
}
walk(tree.dependencies, web);

// An SPDX expression "A OR B" is acceptable when any alternative is allowed; "A AND B" needs all of them.
function classify(expr) {
  expr = expr.trim();
  if (policy.allow.includes(expr)) return "allow";
  if (policy.review.includes(expr)) return "review";
  const clean = expr.replace(/[()]/g, "").trim();
  if (clean.includes(" OR ")) {
    const parts = clean.split(" OR ").map(classify);
    return parts.includes("allow") ? "allow" : parts.includes("review") ? "review" : "deny";
  }
  if (clean.includes(" AND ")) {
    const parts = clean.split(" AND ").map(classify);
    return parts.includes("deny") ? "deny" : parts.includes("review") ? "review" : "allow";
  }
  if (policy.allow.includes(clean)) return "allow";
  if (policy.review.includes(clean)) return "review";
  return "deny";
}

const rows = [...found.values()].sort((a, b) => a.name.localeCompare(b.name)).map((r) => ({ ...r, verdict: classify(r.license) }));
if (asJson) {
  console.log(JSON.stringify(rows, null, 2));
} else {
  const byLicense = {};
  for (const r of rows) (byLicense[r.license] ??= []).push(r);
  console.log(`npm ${prod ? "production" : "all"} dependencies: ${rows.length} packages`);
  for (const [lic, list] of Object.entries(byLicense).sort((a, b) => b[1].length - a[1].length)) {
    console.log(`${String(list.length).padStart(4)}  ${lic}  [${list[0].verdict}]`);
  }
  for (const r of rows.filter((x) => x.verdict !== "allow")) console.log(`NEEDS DECISION (${r.verdict}): ${r.name}@${r.version} ${r.license}`);
}
process.exit(rows.some((r) => r.verdict !== "allow") ? 1 : 0);
