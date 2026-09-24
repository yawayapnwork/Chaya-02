"""Container health for the worker, which serves no HTTP.

The worker is healthy only while it is actually talking to the control plane: the file below is refreshed after every
*successful* claim call (idle polling) and every *successful* heartbeat (while a job runs). A worker whose process is
up but cannot reach the API, has bad credentials, or is wedged, stops refreshing it and turns unhealthy.

    python -m chaya_worker.liveness        # exit 0 = healthy, 1 = not (Docker HEALTHCHECK)
"""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path

from .settings import Settings

FILE_NAME = ".alive"


def liveness_file(settings: Settings) -> Path:
    return Path(settings.workdir) / FILE_NAME


def mark_alive(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(str(time.time()), encoding="utf-8")
    os.replace(tmp, path)  # atomic: a reader never sees a half-written file


def max_age_seconds(settings: Settings) -> float:
    """Three missed poll or heartbeat intervals, whichever is longer, plus slack for a slow control-plane call."""
    return 3 * max(settings.poll_interval, settings.heartbeat_interval) + 30


def check(path: Path, max_age: float, now: float | None = None) -> tuple[bool, str]:
    if not path.is_file():
        return False, f"{path} missing: the worker has not completed a control-plane call yet"
    try:
        age = (now if now is not None else time.time()) - float(path.read_text(encoding="utf-8").strip())
    except (OSError, ValueError) as exc:
        return False, f"{path} unreadable: {exc}"
    if age > max_age:
        return False, f"last successful control-plane call {age:.0f} s ago (limit {max_age:.0f} s)"
    return True, f"last successful control-plane call {age:.0f} s ago"


def main() -> int:
    settings = Settings.from_env()
    ok, reason = check(liveness_file(settings), max_age_seconds(settings))
    print(("healthy: " if ok else "unhealthy: ") + reason)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
