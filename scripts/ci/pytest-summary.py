#!/usr/bin/env python3
"""Classifies one pytest run (its JUnit XML) for CI and prints a Markdown summary.

    pytest-summary.py <junit.xml> --label "CPU suite" --mode required [--expected-skip REGEX ...]
    pytest-summary.py <junit.xml> --label "GPU / stack tests" --mode not-executed

Every test is counted as exactly one of:
  passed            it ran and passed
  failed            it ran and failed, or errored
  expected skip     skipped for a reason matching an --expected-skip pattern (each pattern says why that skip is legitimate)
  unexpected skip   any other skip: in a required suite this means a test that should have run did not
  not executed      (mode not-executed) skipped because this runner lacks what the test needs (a GPU, a stack): reported
                    with its reason, never counted as passed

Exit status: 1 on any failure or error; in mode required also on any unexpected skip or when nothing ran.
Only the standard library is used, so it runs before or without the project's dependencies.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET


def classify(path: str) -> list[tuple[str, str, str]]:
    """(test id, outcome, detail) for every test case: outcome passed | failed | skipped."""
    out: list[tuple[str, str, str]] = []
    # Only the JUnit file pytest itself just wrote on this runner is read: not untrusted input.
    for case in ET.parse(path).getroot().iter("testcase"):  # noqa: S314
        test_id = f"{case.get('classname', '')}::{case.get('name', '')}".strip(":")
        if case.find("failure") is not None or case.find("error") is not None:
            node = case.find("failure") if case.find("failure") is not None else case.find("error")
            out.append((test_id, "failed", (node.get("message") or "").splitlines()[0][:200] if node is not None else ""))
        elif case.find("skipped") is not None:
            node = case.find("skipped")
            out.append((test_id, "skipped", (node.get("message") or node.text or "").strip()[:300]))
        else:
            out.append((test_id, "passed", ""))
    return out


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("junit")
    ap.add_argument("--label", required=True)
    ap.add_argument("--mode", choices=["required", "not-executed"], required=True)
    ap.add_argument("--expected-skip", action="append", default=[], help="regex: a skip reason that is legitimate here")
    args = ap.parse_args(argv)
    allowed = [re.compile(p) for p in args.expected_skip]

    cases = classify(args.junit)
    passed = [c for c in cases if c[1] == "passed"]
    failed = [c for c in cases if c[1] == "failed"]
    skipped = [c for c in cases if c[1] == "skipped"]
    expected = [c for c in skipped if any(p.search(c[2]) for p in allowed)]
    other = [c for c in skipped if c not in expected]

    lines = [f"### {args.label}", "", "| passed | failed | expected skip | "
             + ("unexpected skip" if args.mode == "required" else "not executed") + " |", "|---:|---:|---:|---:|",
             f"| {len(passed)} | {len(failed)} | {len(expected)} | {len(other)} |", ""]
    for title, rows in (("Failed", failed), ("Expected skips", expected),
                        ("UNEXPECTED skips (a required test did not run)" if args.mode == "required" else "Not executed here", other)):
        if rows:
            lines += [f"**{title}**", "", "| test | reason |", "|---|---|"]
            lines += [f"| `{t}` | {d.replace('|', '/')} |" for t, _, d in rows]
            lines.append("")
    print("\n".join(lines))

    if failed:
        return 1
    if args.mode == "required" and (other or not cases):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
