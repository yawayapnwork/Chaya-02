"""Checks the licenses of the API's Maven dependencies against scripts/licenses/policy.json.

Input is the report license-maven-plugin writes (target/generated-sources/license/THIRD-PARTY.txt):

    cd services/api && mvn org.codehaus.mojo:license-maven-plugin:2.4.0:add-third-party \
        -Dlicense.includedScopes=compile,runtime
    python scripts/licenses/maven-licenses.py services/api/target/generated-sources/license/THIRD-PARTY.txt [--json]

Each report line is "(License A) (License B) Name (group:artifact:version - url)". Several licenses on one artifact
are read as alternatives (the artifact's POM lists them; dual licensing such as "EPL 2.0" / "GPL2 w/ CPE" is the
usual reason), so the artifact passes if any one of them passes. Names are the free text POM authors wrote, which is
why policy.json lists several spellings of the same license; a spelling it does not know fails, it is never guessed.

Exit status: 0 = every artifact allowed, or "review" and listed in policy.json pending_review.maven; 1 = anything else
(denied, unknown, or an unlisted "review"); 2 = the report is missing or has no dependency lines.
Declared metadata is what POM authors wrote; it is not a verified legal analysis.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

POLICY = json.loads((Path(__file__).with_name("policy.json")).read_text(encoding="utf-8"))


def classify_one(name: str) -> str:
    name = name.strip()
    if name in POLICY["allow"]:
        return "allow"
    if name in POLICY["review"]:
        return "review"
    return "deny"


def parse_line(line: str) -> tuple[list[str], str] | None:
    """Leading top-level parenthesised groups are licenses (they may contain parentheses themselves); the final group
    holds the coordinates. Returns (licenses, "group:artifact:version") or None for a non-dependency line."""
    s = line.strip()
    if not s.startswith("("):
        return None
    licenses: list[str] = []
    i = 0
    while i < len(s) and s[i] == "(":
        depth, j = 0, i
        while j < len(s):
            depth += {"(": 1, ")": -1}.get(s[j], 0)
            if depth == 0:
                break
            j += 1
        licenses.append(s[i + 1:j])
        i = j + 1
        while i < len(s) and s[i] == " ":
            i += 1
    tail = s[i:]
    start = tail.rfind("(")
    if start < 0 or not tail.endswith(")"):
        return None
    coordinates = tail[start + 1:-1].split(" - ")[0].strip()
    return licenses, coordinates


def is_pending(coordinates: str) -> bool:
    """policy.json pending_review.maven entries are "group:artifact" (exact) or a prefix ending in "*"."""
    ga = ":".join(coordinates.split(":")[:2])
    return any(ga.startswith(p[:-1]) if p.endswith("*") else ga == p
               for p in POLICY.get("pending_review", {}).get("maven", []))


def main(argv: list[str]) -> int:
    paths = [a for a in argv if not a.startswith("--")]
    report = Path(paths[0]) if paths else Path("services/api/target/generated-sources/license/THIRD-PARTY.txt")
    if not report.is_file():
        print(f"error: {report} not found; run license-maven-plugin add-third-party first", file=sys.stderr)
        return 2
    rows = []
    for line in report.read_text(encoding="utf-8").splitlines():
        parsed = parse_line(line)
        if parsed is None:
            continue
        licenses, coordinates = parsed
        verdicts = [classify_one(lic) for lic in licenses] or ["deny"]
        verdict = "allow" if "allow" in verdicts else "review" if "review" in verdicts else "deny"
        if verdict == "review" and is_pending(coordinates):
            verdict = "pending"
        rows.append({"artifact": coordinates, "licenses": licenses or ["UNKNOWN"], "verdict": verdict})
    if not rows:
        print(f"error: no dependency lines in {report}", file=sys.stderr)
        return 2
    rows.sort(key=lambda r: r["artifact"])
    if "--json" in argv:
        print(json.dumps(rows, indent=2))
    else:
        print(f"maven runtime dependencies: {len(rows)}")
        for r in rows:
            print(f"{r['verdict']:7}  {r['artifact']}  {' | '.join(r['licenses'])}")
    return 1 if any(r["verdict"] in ("review", "deny") for r in rows) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
