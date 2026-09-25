"""Result records for the Chaya 02 benchmark suite (docs/BENCHMARKS.md).

Every number a benchmark emits is a Metric with an explicit `kind`, so a report can never blur what was measured with
what was configured, assumed or simply not available:

    measured       produced by running real code on the stated input during this run
    estimate       computed from measured quantities and configured/assumed constants (e.g. capture time = measured
                   route length / configured walking speed); `note` gives the formula
    configuration  a value the system or benchmark was configured with (a threshold, a speed, a budget)
    assumption     a value taken as given, not measured (e.g. a dataset's venue model); says what it stands in for
    reference      computed analytically for comparison (e.g. the score of a random ranking), not a measurement
    unavailable    could not be measured here; `note` says why and `command` how to measure it

A run is written as one JSON file under benchmarks/results/<benchmark>/ together with its environment and input
checksums.
"""

from __future__ import annotations

import hashlib
import json
import os
import platform
import subprocess
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

KINDS = ("measured", "estimate", "configuration", "assumption", "reference", "unavailable")
ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "benchmarks" / "results"


@dataclass
class Metric:
    metric: str
    value: Any
    unit: str
    kind: str
    condition: str = ""
    source: str = ""
    note: str | None = None
    command: str | None = None

    def __post_init__(self) -> None:
        if self.kind not in KINDS:
            raise ValueError(f"unknown metric kind {self.kind!r}")
        if self.kind == "unavailable" and (self.value is not None or not self.note):
            raise ValueError(f"{self.metric}: an unavailable metric has no value and must say why")
        if self.kind != "unavailable" and self.value is None:
            raise ValueError(f"{self.metric}: only an unavailable metric may have no value")


def unavailable(metric: str, unit: str, why: str, command: str, condition: str = "") -> Metric:
    return Metric(metric, None, unit, "unavailable", condition=condition, note=why, command=command)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def git_state() -> dict[str, Any]:
    def git(*args: str) -> str:
        try:
            return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True, check=True).stdout.strip()
        except (OSError, subprocess.CalledProcessError):
            return ""
    return {"commit": git("rev-parse", "HEAD"), "dirty_files": len([ln for ln in git("status", "--porcelain").splitlines() if ln])}


@dataclass
class Run:
    benchmark: str
    title: str
    inputs: dict[str, Any] = field(default_factory=dict)
    environment: dict[str, Any] = field(default_factory=dict)
    metrics: list[Metric] = field(default_factory=list)
    details: dict[str, Any] = field(default_factory=dict)
    started: str = field(default_factory=lambda: datetime.now(UTC).isoformat(timespec="seconds"))

    def add(self, *metrics: Metric) -> None:
        self.metrics.extend(metrics)

    def write(self) -> Path:
        out_dir = RESULTS / self.benchmark
        out_dir.mkdir(parents=True, exist_ok=True)
        finished = datetime.now(UTC).isoformat(timespec="seconds")
        env = {"python": platform.python_version(), "platform": platform.platform(), "cpus": os.cpu_count(), **self.environment}
        doc = {"benchmark": self.benchmark, "title": self.title, "started": self.started, "finished": finished,
               "git": git_state(), "environment": env, "inputs": self.inputs,
               "metrics": [asdict(m) for m in self.metrics], "details": self.details}
        path = out_dir / (self.started.replace(":", "").replace("+0000", "Z") + ".json")
        path.write_text(json.dumps(doc, indent=2, default=str), encoding="utf-8")
        (out_dir / "latest.json").write_text(json.dumps(doc, indent=2, default=str), encoding="utf-8")
        return path


def markdown_table(metrics: list[Metric]) -> str:
    rows = ["| condition | metric | value | unit | kind | note |", "|---|---|--:|---|---|---|"]
    for m in metrics:
        v = "n/a" if m.value is None else (f"{m.value:.4g}" if isinstance(m.value, float) else str(m.value))
        rows.append(f"| {m.condition} | {m.metric} | {v} | {m.unit} | {m.kind} | {(m.note or '').replace('|', '/')} |")
    return "\n".join(rows)
