"""Structured JSON logging. One JSON object per line, machine-parsable, with the job context attached."""

from __future__ import annotations

import json
import logging
import sys
from datetime import datetime, timezone
from typing import Any

_RESERVED = set(logging.LogRecord("", 0, "", 0, "", (), None).__dict__) | {"message", "asctime"}


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        doc: dict[str, Any] = {
            "ts": datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        for key, value in record.__dict__.items():
            if key not in _RESERVED and not key.startswith("_"):
                doc[key] = value
        if record.exc_info:
            doc["exc"] = self.formatException(record.exc_info)
        return json.dumps(doc, default=str, ensure_ascii=False)


def configure(level: str = "INFO", stream=None) -> None:
    """Root logger -> JSON on stderr. Safe to call more than once."""
    root = logging.getLogger()
    for h in list(root.handlers):
        if getattr(h, "_chaya_json", False):
            root.removeHandler(h)
    handler = logging.StreamHandler(stream or sys.stderr)
    handler.setFormatter(JsonFormatter())
    handler._chaya_json = True  # type: ignore[attr-defined]
    root.addHandler(handler)
    root.setLevel(level.upper())


class ContextAdapter(logging.LoggerAdapter):
    """Adds job/run/stage fields to every record."""

    def process(self, msg, kwargs):
        extra = dict(self.extra or {})
        extra.update(kwargs.get("extra", {}))
        kwargs["extra"] = extra
        return msg, kwargs


def stage_logger(base: str, **context: Any) -> ContextAdapter:
    return ContextAdapter(logging.getLogger(base), context)


def file_logger(name: str, path, context: dict[str, Any]) -> tuple[ContextAdapter, logging.Handler]:
    """A logger that writes JSON lines to a file (the stage's stdout log) in addition to the root handlers."""
    logger = logging.getLogger(name)
    logger.setLevel(logging.DEBUG)
    handler = logging.FileHandler(path, encoding="utf-8")
    handler.setFormatter(JsonFormatter())
    logger.addHandler(handler)
    return ContextAdapter(logger, context), handler
