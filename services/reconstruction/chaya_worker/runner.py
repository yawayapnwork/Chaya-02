"""Runs external commands for a stage: captures stdout/stderr into the stage's log files, enforces the
time box, and records exactly what was executed (argv, exit status, timing)."""

from __future__ import annotations

import os
import signal
import subprocess
import time
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path

from .errors import StageError, TimeLimitExceeded


@dataclass
class ExecutedCommand:
    argv: list[str]
    exit_status: int | None
    started_at: float
    finished_at: float


@dataclass
class CommandRunner:
    stdout_path: Path
    stderr_path: Path
    deadline: float | None = None  # epoch seconds
    history: list[ExecutedCommand] = field(default_factory=list)

    def _budget(self, timeout: float | None) -> float | None:
        remaining = None if self.deadline is None else self.deadline - time.time()
        if remaining is not None and remaining <= 0:
            raise TimeLimitExceeded("the run's time budget is already spent", details={"argv_not_started": True})
        candidates = [t for t in (timeout, remaining) if t is not None]
        return min(candidates) if candidates else None

    def run(self, argv: Sequence[str], *, cwd: Path | None = None, timeout: float | None = None,
            check: bool = True, error_code: str = "COMMAND_FAILED", capture: bool = False) -> subprocess.CompletedProcess[str]:
        """Execute argv. stdout/stderr are appended to the stage logs. With capture=True the output is also returned."""
        argv = [str(a) for a in argv]
        budget = self._budget(timeout)
        started = time.time()
        with open(self.stdout_path, "ab") as out, open(self.stderr_path, "ab") as err:
            out.write(f"$ {' '.join(argv)}\n".encode())
            out.flush()
            popen_kwargs: dict = {"cwd": str(cwd) if cwd else None, "stdout": subprocess.PIPE if capture else out,
                                  "stderr": subprocess.PIPE if capture else err}
            if os.name != "nt":
                popen_kwargs["start_new_session"] = True  # so a timeout can kill the whole process group
            try:
                proc = subprocess.Popen(argv, text=capture, **popen_kwargs)
            except OSError as exc:
                self.history.append(ExecutedCommand(argv, None, started, time.time()))
                raise StageError(f"could not start {argv[0]}: {exc}", code=error_code, details={"argv": argv}) from exc
            try:
                stdout, stderr = proc.communicate(timeout=budget)
            except subprocess.TimeoutExpired as exc:
                self._kill(proc)
                proc.communicate()
                self.history.append(ExecutedCommand(argv, None, started, time.time()))
                raise TimeLimitExceeded(f"{argv[0]} was stopped when the time budget ran out",
                                        details={"argv": argv, "timeout_seconds": budget}) from exc
            if capture:
                out.write((stdout or "").encode())
                err.write((stderr or "").encode())
        self.history.append(ExecutedCommand(argv, proc.returncode, started, time.time()))
        result = subprocess.CompletedProcess(argv, proc.returncode, stdout or "", stderr or "")
        if check and proc.returncode != 0:
            raise StageError(f"{Path(argv[0]).name} exited with status {proc.returncode}", code=error_code,
                             details={"argv": argv}, exit_status=proc.returncode)
        return result

    @staticmethod
    def _kill(proc: subprocess.Popen) -> None:
        try:
            if os.name == "nt":
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True)
            else:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        except (OSError, ProcessLookupError):
            proc.kill()

    def commands(self) -> list[list[str]]:
        return [c.argv for c in self.history]

    def last_exit_status(self) -> int | None:
        return self.history[-1].exit_status if self.history else None
