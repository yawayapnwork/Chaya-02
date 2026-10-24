"""The real GPU-acceptance dataset's preparation (tests/gpu/real_dataset.py), without the network or COLMAP: the
manifest is what it claims, downloads are verified, and invalid projects are refused with a code."""

from __future__ import annotations

import hashlib
import io
import json

import cv2
import numpy as np
import pytest

from tests.gpu import real_dataset
from tests.gpu.real_dataset import DatasetError


def test_the_manifest_is_cc0_with_a_commons_url_and_sha1_for_every_one_of_its_24_frames():
    m = real_dataset.load_manifest()
    assert m["license"] == "CC0-1.0" and len(m["files"]) == 24
    assert len({f["sha1"] for f in m["files"]}) == 24, "no file is listed twice"
    assert all(f["license"] == "CC0" and f["width"] == 8000 and f["height"] == 4500 for f in m["files"])
    assert m["derived"]["width"] / m["derived"]["height"] == pytest.approx(8000 / 4500)


def test_a_manifest_naming_a_non_cc0_file_is_refused(tmp_path):
    m = real_dataset.load_manifest()
    m["files"][3]["license"] = "CC BY-SA 4.0"
    bad = tmp_path / "m.json"
    bad.write_text(json.dumps(m), encoding="utf-8")
    with pytest.raises(DatasetError) as e:
        real_dataset.load_manifest(bad)
    assert e.value.code == "DATASET_INVALID"


class Served:
    def __init__(self, body: bytes) -> None:
        self.body, self.calls = body, 0

    def __call__(self, request, timeout):
        self.calls += 1
        return io.BytesIO(self.body)


def _manifest(body: bytes, sha1: str | None = None) -> dict:
    return {"files": [{"file": "a.jpg", "url": "https://upload.wikimedia.org/a.jpg", "sha1": sha1 or hashlib.sha1(body, usedforsecurity=False).hexdigest(),
                       "license": "CC0", "width": 8, "height": 6}]}


def test_a_download_is_verified_and_a_verified_file_is_not_fetched_again(tmp_path):
    body = b"real bytes"
    served = Served(body)
    [path] = real_dataset.download(_manifest(body), tmp_path, opener=served)
    assert path.read_bytes() == body and served.calls == 1
    real_dataset.download(_manifest(body), tmp_path, opener=served)
    assert served.calls == 1, "resumable: a file that verifies is kept"


def test_a_download_whose_sha1_differs_is_refused_and_removed(tmp_path):
    with pytest.raises(DatasetError) as e:
        real_dataset.download(_manifest(b"other bytes", sha1="0" * 40), tmp_path, opener=Served(b"other bytes"))
    assert e.value.code == "DATASET_CHECKSUM_MISMATCH" and not (tmp_path / "a.jpg").exists()


def test_an_original_of_the_wrong_size_is_refused(tmp_path):
    cv2.imwrite(str(tmp_path / "a.jpg"), np.zeros((5, 5, 3), np.uint8))
    m = {**_manifest(b""), "derived": {"width": 4, "height": 3}}
    with pytest.raises(DatasetError) as e:
        real_dataset.derive(m, tmp_path, tmp_path / "project")
    assert e.value.code == "DATASET_INVALID"


def _project(tmp_path, registered=24, frames=24):
    p = tmp_path / "project"
    (p / "images").mkdir(parents=True)
    (p / "sparse" / "0").mkdir(parents=True)
    for name in ("cameras.bin", "images.bin", "points3D.bin"):
        (p / "sparse" / "0" / name).write_bytes(b"x")
    listed = []
    for i in range(frames):
        f = p / "images" / f"korno-{i}.jpg"
        f.write_bytes(f"frame {i}".encode())
        listed.append({"name": f.name, "sha256": hashlib.sha256(f.read_bytes()).hexdigest()})
    (p / "dataset.json").write_text(json.dumps({"frames": listed, "reconstruction": {"registered_images": registered}}),
                                    encoding="utf-8")
    return p


def test_a_complete_project_validates(tmp_path):
    assert real_dataset.validate(_project(tmp_path))["reconstruction"]["registered_images"] == 24


@pytest.mark.parametrize(("damage", "code"), [
    (lambda p: (p / "sparse" / "0" / "points3D.bin").unlink(), "DATASET_INVALID"),
    (lambda p: (p / "dataset.json").unlink(), "DATASET_INVALID"),
    (lambda p: (p / "images" / "korno-3.jpg").unlink(), "DATASET_INVALID"),
    (lambda p: (p / "images" / "korno-3.jpg").write_bytes(b"edited"), "DATASET_CHECKSUM_MISMATCH"),
])
def test_an_incomplete_or_altered_project_is_refused(tmp_path, damage, code):
    p = _project(tmp_path)
    damage(p)
    with pytest.raises(DatasetError) as e:
        real_dataset.validate(p)
    assert e.value.code == code


def test_a_project_where_too_few_frames_registered_is_refused_not_used(tmp_path):
    with pytest.raises(DatasetError) as e:
        real_dataset.validate(_project(tmp_path, registered=10))
    assert e.value.code == "DATASET_RECONSTRUCTION_FAILED"


def test_a_failing_colmap_step_is_a_reconstruction_failure(tmp_path):
    class Res:
        returncode, stdout, stderr = 1, "", "boom"

    with pytest.raises(DatasetError) as e:
        real_dataset.reconstruct(tmp_path, "colmap", run=lambda *a, **k: Res())
    assert e.value.code == "DATASET_RECONSTRUCTION_FAILED"
