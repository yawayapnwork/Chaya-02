"""Safe loading of Hugging Face model weights (docs/security-hardening.md, "Model files").

Two risks, two controls:
  * a model repository can change under the same name: pass a *revision* (a commit SHA) to pin exactly what runs.
    Unpinned loads still work but are logged as a warning and the resolved commit is recorded when available;
  * pickle-format weights (pytorch_model.bin) can execute code when loaded: only safetensors are accepted unless
    MODEL_ALLOW_PICKLE_WEIGHTS=true is set deliberately.
A load that fails for either reason becomes a DependencyError that says how to fix it, never a silent fallback.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from typing import Any

from .errors import DependencyError

log = logging.getLogger(__name__)


def load_pretrained(loader: Callable[..., Any], model_id: str, *, revision: str, allow_pickle: bool, what: str) -> Any:
    kwargs: dict[str, Any] = {"use_safetensors": not allow_pickle}
    if revision:
        kwargs["revision"] = revision
    else:
        log.warning("loading %s from an unpinned revision", what, extra={"model": model_id})
    try:
        return loader(model_id, **kwargs)
    except (OSError, ValueError) as exc:
        raise DependencyError(
            f"{what} {model_id!r} could not be loaded"
            + ("" if allow_pickle else " as safetensors")
            + (f" at revision {revision!r}" if revision else "")
            + ": pin a revision that ships model.safetensors, or set MODEL_ALLOW_PICKLE_WEIGHTS=true after reviewing the source",
            details={"missing": [model_id], "revision": revision or None, "safetensors_only": not allow_pickle,
                     "error": str(exc)[:500]},
        ) from exc


def resolved_revision(model: Any) -> str | None:
    """The commit a transformers model was actually loaded from, when the library records it."""
    return getattr(getattr(model, "config", None), "_commit_hash", None)
