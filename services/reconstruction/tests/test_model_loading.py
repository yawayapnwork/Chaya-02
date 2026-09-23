"""Model weight loading: pickle weights are refused unless explicitly allowed, revisions are passed through, and a
failed load is a structured DependencyError (no transformers or network needed: the loader is a stand-in)."""

from __future__ import annotations

import pytest

from chaya_worker.errors import DependencyError
from chaya_worker.model_loading import load_pretrained, resolved_revision
from chaya_worker.settings import Settings


def test_safetensors_only_and_revision_are_requested_by_default() -> None:
    seen = {}

    def loader(model_id, **kwargs):
        seen.update(kwargs, model_id=model_id)
        return "model"

    assert load_pretrained(loader, "org/model", revision="abc123", allow_pickle=False, what="test model") == "model"
    assert seen == {"model_id": "org/model", "use_safetensors": True, "revision": "abc123"}


def test_unpinned_load_passes_no_revision() -> None:
    seen = {}
    load_pretrained(lambda m, **kw: seen.update(kw), "org/model", revision="", allow_pickle=True, what="test model")
    assert seen == {"use_safetensors": False}


def test_a_repository_without_safetensors_fails_clearly() -> None:
    def loader(model_id, **kwargs):
        raise OSError("org/model does not appear to have a file named model.safetensors")

    with pytest.raises(DependencyError) as err:
        load_pretrained(loader, "org/model", revision="", allow_pickle=False, what="test model")
    assert err.value.code == "DEPENDENCY_UNAVAILABLE"
    assert "MODEL_ALLOW_PICKLE_WEIGHTS" in err.value.message
    assert err.value.details["safetensors_only"] is True


def test_settings_default_to_safetensors_only_and_read_revisions() -> None:
    s = Settings.from_env({"GROUNDING_DINO_REVISION": "deadbeef", "SEMANTIC_SEGMENTATION_REVISION": "cafe"})
    assert s.allow_pickle_weights is False
    assert s.grounding_dino_revision == "deadbeef" and s.semantic_segmentation_revision == "cafe"
    assert Settings.from_env({"MODEL_ALLOW_PICKLE_WEIGHTS": "TRUE"}).allow_pickle_weights is True


def test_resolved_revision_reads_the_commit_hash_when_present() -> None:
    class Config:
        _commit_hash = "0123abc"

    class Model:
        config = Config()

    assert resolved_revision(Model()) == "0123abc"
    assert resolved_revision(object()) is None
