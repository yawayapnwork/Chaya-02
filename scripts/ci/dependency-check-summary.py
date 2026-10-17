#!/usr/bin/env python3
"""Human-readable summary (Markdown) of an OWASP Dependency-Check JSON report (scripts/ci/dependency-check.sh).

    dependency-check-summary.py target/dependency-check-report.json --threshold 7 --database "updated this run"

Reports what the scan saw; it does not decide pass or fail. The plugin's own failBuildOnCVSS does, and this summary
names the same findings: a vulnerability counts when its CVSS v2, v3 or v4 base score reaches the threshold, or, for a
source that gives only a severity word, when Dependency-Check's estimate for that word does (SeverityUtil.estimateCvssV2
in 12.2.2: critical and high 10, medium 6.9, info 0, anything else 3.9).
"""
import argparse
import json
import sys
from collections import Counter

UNSCORED = {"critical": 10.0, "high": 10.0, "moderate": 6.9, "medium": 6.9, "info": 0.0, "none": 0.0, "informational": 0.0}


def score(v):
    """The highest base score the vulnerability carries, and which CVSS version it came from."""
    best = (None, None)
    for version, key, field in (("v2", "cvssv2", "score"), ("v3", "cvssv3", "baseScore"), ("v4", "cvssv4", "baseScore")):
        data = v.get(key) or {}
        value = data.get(field)
        if value is None:
            value = (data.get("cvssData") or {}).get("baseScore")
        if value is not None and (best[0] is None or float(value) > best[0]):
            best = (float(value), version)
    if best[0] is None and v.get("severity"):
        best = (UNSCORED.get(str(v["severity"]).lower(), 3.9), f"estimated from '{v['severity']}'")
    return best


def main():
    p = argparse.ArgumentParser()
    p.add_argument("report")
    p.add_argument("--threshold", type=float, default=7.0)
    p.add_argument("--database", default="unknown", help="how current the vulnerability database is")
    p.add_argument("--database-date", default="unknown")
    a = p.parse_args()

    with open(a.report, encoding="utf-8") as f:
        report = json.load(f)
    deps = report.get("dependencies") or []
    severities, findings, suppressed = Counter(), [], 0
    for d in deps:
        suppressed += len(d.get("suppressedVulnerabilities") or [])
        for v in d.get("vulnerabilities") or []:
            s, version = score(v)
            severities[str(v.get("severity") or "UNKNOWN").upper()] += 1
            if s is not None and s >= a.threshold:
                pkg = ", ".join(x.get("id", "") for x in d.get("packages") or []) or d.get("fileName", "?")
                findings.append((s, v.get("name", "?"), version, pkg, v.get("source", "")))
    findings.sort(key=lambda x: (-x[0], x[1]))

    info = report.get("scanInfo") or {}
    sources = {s.get("name"): s.get("timestamp") for s in info.get("dataSource") or []}
    print(f"### OWASP Dependency-Check {info.get('engineVersion', '?')}: services/api")
    print()
    print(f"- Vulnerability database: **{a.database}** (last successful update {a.database_date}; "
          f"NVD last modified {sources.get('NVD API Last Modified', 'unknown')})")
    print(f"- Dependencies analysed: {len(deps)}; vulnerabilities: {sum(severities.values())} "
          f"({', '.join(f'{k} {n}' for k, n in sorted(severities.items())) or 'none'}); suppressed: {suppressed}")
    print(f"- Policy: fail at CVSS >= {a.threshold:g}. **At or above it: {len(findings)}**")
    if findings:
        print()
        print("| CVSS | Vulnerability | Scored by | Package | Source |")
        print("|---|---|---|---|---|")
        for s, name, version, pkg, src in findings:
            print(f"| {s:.1f} | {name} | {version} | {pkg} | {src} |")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
