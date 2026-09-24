"""Lists the licenses of every distribution installed in the Python environment that runs this script, as declared in
each distribution's own metadata (License-Expression, the License field, or its trove classifiers), and checks them
against scripts/licenses/policy.json.

    <venv>/bin/python scripts/licenses/python-licenses.py [--json] [--exclude NAME ...]

Run it with the interpreter of the environment to scan (the worker's or the vision service's venv, or the image).
Exit status: 0 = every license is allowed, or is a "review" license on a distribution listed in policy.json
pending_review.python (known, awaiting the human decision recorded in THIRD_PARTY_LICENSES.md); 1 = anything else: a
denied or unknown license, or a review license on a distribution nobody has looked at yet.
Declared metadata is what package authors wrote; it is not a verified legal analysis.
"""

from __future__ import annotations

import json
import sys
from importlib import metadata
from pathlib import Path

POLICY = json.loads((Path(__file__).with_name("policy.json")).read_text(encoding="utf-8"))
SKIP = {"pip", "setuptools", "wheel"}


def declared(dist: metadata.Distribution) -> str:
    md = dist.metadata
    expr = md.get("License-Expression")
    if expr:
        return expr.strip()
    classifiers = [c.split("::")[-1].strip() for c in md.get_all("Classifier") or [] if c.startswith("License ::")]
    classifiers = [c for c in classifiers if c not in ("OSI Approved",)]
    if classifiers:
        return " OR ".join(sorted(set(classifiers)))
    lic = (md.get("License") or "").strip()
    # Some packages paste the whole license text into the field; only a short value is an identifier.
    return lic if lic and len(lic) < 80 and "\n" not in lic else "UNKNOWN"


def classify(expr: str) -> str:
    expr = expr.strip()
    if expr in POLICY["allow"]:
        return "allow"
    if expr in POLICY["review"]:
        return "review"
    clean = expr.replace("(", "").replace(")", "").strip()
    if " OR " in clean:
        parts = [classify(p) for p in clean.split(" OR ")]
        return "allow" if "allow" in parts else "review" if "review" in parts else "deny"
    if " AND " in clean:
        parts = [classify(p) for p in clean.split(" AND ")]
        return "deny" if "deny" in parts else "review" if "review" in parts else "allow"
    if clean in POLICY["allow"]:
        return "allow"
    if clean in POLICY["review"]:
        return "review"
    return "deny"


def is_pending(name: str, ecosystem: str = "python") -> bool:
    """Exact (case-insensitive) names, or a prefix ending in "*". Only ever softens a "review" verdict."""
    name = name.lower()
    return any(name.startswith(p[:-1].lower()) if p.endswith("*") else name == p.lower()
               for p in POLICY.get("pending_review", {}).get(ecosystem, []))


def main(argv: list[str]) -> int:
    exclude = {a.lower() for a in argv[argv.index("--exclude") + 1:]} if "--exclude" in argv else set()
    rows = []
    for dist in metadata.distributions():
        name = dist.metadata["Name"]
        if not name or name.lower() in SKIP or name.lower() in exclude:
            continue
        lic = declared(dist)
        verdict = classify(lic)
        if verdict == "review" and is_pending(name):
            verdict = "pending"
        rows.append({"name": name, "version": dist.version, "license": lic, "verdict": verdict})
    rows.sort(key=lambda r: r["name"].lower())
    if "--json" in argv:
        print(json.dumps(rows, indent=2))
    else:
        print(f"python distributions in {sys.prefix}: {len(rows)}")
        for r in rows:
            print(f"{r['verdict']:7}  {r['name']}=={r['version']}  {r['license']}")
    return 1 if any(r["verdict"] in ("review", "deny") for r in rows) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
