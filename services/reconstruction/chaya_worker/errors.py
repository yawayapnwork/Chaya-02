"""Errors a stage may raise. The orchestrator turns each into a structured failed stage report."""

from __future__ import annotations

from typing import Any


class StageError(Exception):
    """A stage failed in an expected, reportable way."""

    code = "STAGE_FAILED"

    def __init__(self, message: str, *, code: str | None = None, details: dict[str, Any] | None = None,
                 exit_status: int | None = None) -> None:
        super().__init__(message)
        if code:
            self.code = code
        self.message = message
        self.details = details or {}
        self.exit_status = exit_status


class DependencyError(StageError):
    """A required tool or library is not available on this worker. Never worked around with fake output."""

    code = "DEPENDENCY_UNAVAILABLE"


class TimeLimitExceeded(StageError):
    """The run's time budget ran out while the stage was working."""

    code = "TIME_LIMIT_EXCEEDED"


class StageNotImplemented(StageError):
    """The stage exists in the pipeline plan but its implementation is not written yet."""

    code = "STAGE_NOT_IMPLEMENTED"
