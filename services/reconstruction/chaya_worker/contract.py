"""The contract every pipeline stage implements.

A stage receives a StageContext (work order, local input files, working directory, logger, command
runner, deadline) and returns a StageResult. It never talks to the control plane or to object storage:
the orchestrator does that, so every stage is testable with plain files and every stage report has the
same shape (name, inputs, outputs, command/config, timestamps, exit status, log locations, checksum,
error code).
"""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from .errors import DependencyError, StageError, StageNotImplemented, TimeLimitExceeded  # noqa: F401 (re-exported)
from .runner import CommandRunner
from .settings import Settings
from .toolchain import Toolchain


@dataclass
class InputFile:
    """A claimed input artifact, downloaded to a local path."""

    ref: dict[str, Any]  # the InputRef from the work order
    path: Path

    @property
    def kind(self) -> str:
        return self.ref["kind"]

    @property
    def artifact_id(self) -> str:
        return self.ref["artifactId"]


@dataclass
class ArtifactSpec:
    """A file a stage wants to publish. The orchestrator computes size and checksum and uploads it."""

    kind: str
    path: Path
    name: str  # object name below the stage's output prefix
    content_type: str = "application/octet-stream"
    contains_pii: bool = False
    partial: bool = False


@dataclass
class StageResult:
    status: str  # SUCCEEDED | FAILED
    command: dict[str, Any] = field(default_factory=dict)
    exit_status: int | None = None
    artifacts: list[ArtifactSpec] = field(default_factory=list)
    error_code: str | None = None
    error_message: str | None = None
    error_details: dict[str, Any] | None = None


@dataclass
class StageContext:
    order: dict[str, Any]
    inputs: list[InputFile]
    workdir: Path
    logger: logging.Logger
    runner: CommandRunner
    toolchain: Toolchain
    settings: Settings
    deadline: float | None  # epoch seconds; None = no time box
    cancelled: threading.Event = field(default_factory=threading.Event)  # set when the control plane says stop

    @property
    def stage(self) -> str:
        return self.order["stage"]

    def inputs_of(self, *kinds: str) -> list[InputFile]:
        return [i for i in self.inputs if i.kind in kinds]

    def remaining_seconds(self, now: float) -> float | None:
        return None if self.deadline is None else self.deadline - now


class Stage(Protocol):
    name: str

    def run(self, ctx: StageContext) -> StageResult: ...
