"""Executes one claimed stage job end to end and reports the outcome to the control plane.

claim -> (privacy guard) -> download + verify inputs -> run the stage (heartbeating) -> checksum + upload
outputs and logs -> submit ONE stage report. The orchestrator is the only component that talks to the
control plane and object storage; stages only see local files.

Guarantees:
  * a stage failure, missing dependency, timeout or crash becomes a structured FAILED report, never a
    silent success and never a fabricated artifact;
  * logs are uploaded and registered whether the stage succeeded or failed;
  * with privacy enabled, a stage after PRIVACY_PREPROCESS refuses to start if any input may contain PII;
  * the local working directory (which may hold unblurred frames) is always deleted afterwards.
"""

from __future__ import annotations

import hashlib
import logging
import shutil
import threading
import time
import traceback
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from . import __version__
from .api_client import ApiError, ControlPlane
from .contract import ArtifactSpec, InputFile, Stage, StageContext, StageResult
from .errors import StageError
from .logging_json import file_logger
from .runner import CommandRunner
from .settings import Settings
from .stages import STAGE_ORDER, default_registry, runs_after_privacy
from .stages.base import command_record, sha256_file
from .storage import ObjectStorage, StorageError
from .toolchain import Toolchain

log = logging.getLogger("chaya_worker.orchestrator")


def _iso(epoch: float) -> str:
    return datetime.fromtimestamp(epoch, tz=timezone.utc).isoformat(timespec="milliseconds")


class _Heartbeat(threading.Thread):
    """Keeps the job lease alive; raises the cancel flag if the control plane says the job is no longer ours."""

    def __init__(self, api: ControlPlane, job_id: str, worker_id: str, interval: float, cancelled: threading.Event) -> None:
        super().__init__(daemon=True, name=f"heartbeat-{job_id[:8]}")
        self._api, self._job_id, self._worker_id, self._interval = api, job_id, worker_id, interval
        self._cancelled = cancelled
        self._halt = threading.Event()

    def run(self) -> None:
        while not self._halt.wait(self._interval):
            try:
                if not self._api.heartbeat(self._job_id, self._worker_id).get("keepGoing", False):
                    log.warning("control plane says stop", extra={"job_id": self._job_id})
                    self._cancelled.set()
                    return
            except ApiError as exc:
                log.warning("heartbeat failed", extra={"job_id": self._job_id, "error": str(exc)})

    def stop(self) -> None:
        self._halt.set()


class Orchestrator:
    def __init__(self, api: ControlPlane, storage: ObjectStorage, settings: Settings, *, toolchain: Toolchain | None = None,
                 registry: dict[str, Stage] | None = None, clock: Callable[[], float] = time.time) -> None:
        self.api = api
        self.storage = storage
        self.settings = settings
        self.toolchain = toolchain or Toolchain()
        self.registry = registry or default_registry()
        self.clock = clock

    # ---- polling ------------------------------------------------------------------------------

    def run_once(self) -> bool:
        """Claim and process at most one job. True if a job was processed."""
        stages = list(self.settings.stages) or STAGE_ORDER
        order = self.api.claim(stages, self.settings.worker_id)
        if order is None:
            return False
        log.info("job claimed", extra={"job_id": order["id"], "stage": order["stage"], "run_id": order.get("runId"),
                                       "attempt": order.get("attempt")})
        self.process(order)
        return True

    # ---- one job ------------------------------------------------------------------------------

    def process(self, order: dict[str, Any]) -> dict[str, Any] | None:
        job_id, stage_name = order["id"], order["stage"]
        workdir = self.settings.workdir / job_id
        workdir.mkdir(parents=True, exist_ok=True)
        stdout_path, stderr_path = workdir / "stdout.log", workdir / "stderr.log"
        stdout_path.touch()
        stderr_path.touch()
        context = {"job_id": job_id, "run_id": order.get("runId"), "stage": stage_name, "attempt": order.get("attempt"),
                   "worker_id": self.settings.worker_id}
        logger, handler = file_logger(f"chaya_worker.job.{job_id}", stdout_path, context)
        started = self.clock()
        deadline = datetime.fromisoformat(order["deadlineAt"].replace("Z", "+00:00")).timestamp() if order.get("deadlineAt") else None
        cancelled = threading.Event()
        heartbeat = _Heartbeat(self.api, job_id, self.settings.worker_id, self.settings.heartbeat_interval, cancelled)
        heartbeat.start()
        report: dict[str, Any] | None = None
        try:
            logger.info("stage started", extra={"deadline": deadline})
            runner = CommandRunner(stdout_path, stderr_path, deadline)
            result = self._execute(order, workdir, runner, logger, deadline, cancelled)
            finished = self.clock()
            logger.info("stage finished", extra={"status": result.status, "error_code": result.error_code,
                                                 "duration_seconds": round(finished - started, 3)})
            result = self._publish(order, result, logger)  # uploads outputs; may turn the result into a failure
            heartbeat.stop()
            handler.flush()
            logger.logger.removeHandler(handler)
            handler.close()
            report = self._build_report(order, result, started, finished, stdout_path, stderr_path)
            if cancelled.is_set():
                log.warning("job was cancelled while running; not reporting", extra=context)
                return None
            self._submit(order, report)
            return report
        finally:
            heartbeat.stop()
            logger.logger.removeHandler(handler)
            handler.close()
            shutil.rmtree(workdir, ignore_errors=True)  # may hold unblurred frames: always removed

    # ---- execution ----------------------------------------------------------------------------

    def _execute(self, order: dict[str, Any], workdir: Path, runner: CommandRunner, logger, deadline: float | None,
                 cancelled: threading.Event) -> StageResult:
        name = order["stage"]
        stage = self.registry.get(name)
        if stage is None:
            return self._failed(order, "UNKNOWN_STAGE", f"this worker has no implementation for stage {name}", runner, None)

        if order.get("privacyEnabled") and runs_after_privacy(name):
            pii = [i["artifactId"] for i in order["inputs"] if i.get("containsPii")]
            if pii:  # defence in depth: the control plane already withholds these
                logger.error("refusing to start: input may contain PII", extra={"artifact_ids": pii})
                return self._failed(order, "PRIVACY_VIOLATION", "an input that may contain PII was offered to a stage after privacy preprocessing",
                                    runner, {"artifact_ids": pii})

        ctx = StageContext(order, [], workdir, logger, runner, self.toolchain, self.settings, deadline, cancelled)
        try:
            ctx.inputs.extend(self._download_inputs(order, workdir, logger))
            result = stage.run(ctx)
            if not result.command:
                result.command = command_record(ctx)
            return result
        except StageError as exc:
            logger.error("stage failed", extra={"error_code": exc.code, "message_detail": exc.message, "details": exc.details})
            return StageResult("FAILED", command_record(ctx), exc.exit_status if exc.exit_status is not None else runner.last_exit_status(),
                               [], exc.code, exc.message, exc.details or None)
        except Exception as exc:  # noqa: BLE001 - a bug in a stage must fail the stage, not the worker
            logger.exception("stage crashed")
            with open(runner.stderr_path, "a", encoding="utf-8") as f:
                f.write(traceback.format_exc())
            return StageResult("FAILED", command_record(ctx), runner.last_exit_status(), [], "INTERNAL_ERROR",
                               f"{type(exc).__name__}: {exc}", None)

    def _failed(self, order, code, message, runner, details) -> StageResult:
        return StageResult("FAILED", {"stage": order["stage"], "argv": ["python", "-m", "chaya_worker", order["stage"]], "commands": [],
                                      "config": self.settings.config_snapshot(), "tools": {}},
                           runner.last_exit_status(), [], code, message, details)

    def _download_inputs(self, order: dict[str, Any], workdir: Path, logger) -> list[InputFile]:
        files: list[InputFile] = []
        for ref in order["inputs"]:
            dest = workdir / "inputs" / f"{ref['artifactId']}-{Path(ref['key']).name}"
            try:
                self.storage.download(ref["bucket"], ref["key"], dest)
            except StorageError as exc:
                raise StageError(str(exc), code="INPUT_DOWNLOAD_FAILED", details={"artifact_id": ref["artifactId"]}) from exc
            if ref.get("sha256") and sha256_file(dest) != ref["sha256"]:
                raise StageError(f"input {ref['artifactId']} does not match its recorded checksum", code="INPUT_CHECKSUM_MISMATCH",
                                 details={"artifact_id": ref["artifactId"], "kind": ref["kind"]})
            logger.info("input downloaded", extra={"artifact_id": ref["artifactId"], "kind": ref["kind"], "bytes": dest.stat().st_size})
            files.append(InputFile(ref, dest))
        return files

    # ---- publishing ---------------------------------------------------------------------------

    def _publish(self, order: dict[str, Any], result: StageResult, logger) -> StageResult:
        """Upload output artifacts. If that fails the stage is reported as failed; partial uploads are not registered."""
        self._uploaded: list[dict[str, Any]] = []
        try:
            for spec in result.artifacts:
                self._uploaded.append(self._upload(order, spec))
        except (StorageError, OSError) as exc:
            logger.error("artifact upload failed", extra={"error": str(exc)})
            self._uploaded = []
            return StageResult("FAILED", result.command, result.exit_status, [], "STORAGE_ERROR", f"could not store stage output: {exc}", None)
        return result

    def _upload(self, order: dict[str, Any], spec: ArtifactSpec) -> dict[str, Any]:
        key = order["outputPrefix"] + ("pii/" if spec.contains_pii else "") + spec.name
        sha, size = sha256_file(spec.path), spec.path.stat().st_size
        self.storage.upload(order["derivedBucket"], key, spec.path, spec.content_type)
        return {"kind": spec.kind, "key": key, "sha256": sha, "contentType": spec.content_type, "sizeBytes": size,
                "containsPii": spec.contains_pii, "partial": spec.partial}

    def _log_artifact(self, order: dict[str, Any], path: Path, name: str) -> dict[str, Any] | None:
        try:
            return self._upload(order, ArtifactSpec("LOG", path, f"logs/{name}", "text/plain"))
        except (StorageError, OSError) as exc:
            log.warning("could not upload stage log", extra={"job_id": order["id"], "log": name, "error": str(exc)})
            return None

    def _build_report(self, order, result: StageResult, started: float, finished: float, stdout: Path, stderr: Path) -> dict[str, Any]:
        command = dict(result.command)
        command["worker"] = {"id": self.settings.worker_id, "version": __version__}
        return {
            "status": result.status,
            "startedAt": _iso(started),
            "finishedAt": _iso(finished),
            "command": command,
            "inputArtifactIds": [i["artifactId"] for i in order["inputs"]],
            "exitStatus": result.exit_status,
            "stdout": self._log_artifact(order, stdout, "stdout.log"),
            "stderr": self._log_artifact(order, stderr, "stderr.log"),
            "artifacts": self._uploaded if result.status == "SUCCEEDED" or result.artifacts else [],
            "errorCode": result.error_code,
            "errorMessage": result.error_message,
            "errorDetails": result.error_details,
        }

    def _submit(self, order: dict[str, Any], report: dict[str, Any]) -> None:
        try:
            self.api.report(order["id"], report)
            log.info("stage report accepted", extra={"job_id": order["id"], "stage": order["stage"], "status": report["status"]})
        except ApiError as exc:
            if exc.status in (400, 409) and exc.code not in ("JOB_NOT_RUNNING", "RUN_NOT_ACTIVE"):
                # The control plane refused the report itself. Fail the stage explicitly instead of leaving it to time out.
                log.error("stage report rejected", extra={"job_id": order["id"], "code": exc.code, "message_detail": exc.message})
                fallback = {**report, "status": "FAILED", "artifacts": [], "errorCode": "REPORT_REJECTED",
                            "errorMessage": f"the control plane rejected the stage report: {exc.code}: {exc.message}",
                            "errorDetails": {"rejected_code": exc.code, "original_status": report["status"]}}
                try:
                    self.api.report(order["id"], fallback)
                except ApiError as second:
                    log.error("could not report failure either", extra={"job_id": order["id"], "error": str(second)})
            else:
                log.error("stage report not applied", extra={"job_id": order["id"], "code": exc.code, "message_detail": exc.message})
