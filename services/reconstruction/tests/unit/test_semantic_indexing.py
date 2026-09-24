"""Pure unit tests for SEMANTIC_INDEXING's geometry/clustering logic and the chaya_worker.datasets
annotation format. No external tools, no ML models."""

from __future__ import annotations

import json

import numpy as np
import pytest

from chaya_worker.datasets import Annotation, BoundingBox, ImageRecord, Provenance
from chaya_worker.datasets.jsonl import append_annotations, append_images, read_annotations, read_images, validate_dataset
from chaya_worker.datasets.scaffold import scaffold_dataset
from chaya_worker.grounding_dino import Detection
from chaya_worker.stages.semantic_indexing import associate_detection_with_geometry, cluster_by_distance

# ---- semantic_indexing.py geometry/clustering -----------------------------------------------------

def test_associate_detection_with_geometry_needs_a_reprojected_point_inside_the_box():
    positions = np.array([[0.0, 0.0, 5.0], [10.0, 10.0, 5.0]])
    px = np.array([[50.0, 50.0], [900.0, 900.0]])
    visible = np.array([True, True])
    inside_box = Detection("chair", 0.9, (0, 0, 100, 100))
    result = associate_detection_with_geometry(inside_box, positions, px, visible)
    assert result is not None
    position, support = result
    assert support == 1 and np.allclose(position, [0.0, 0.0, 5.0])

    nothing_there = Detection("chair", 0.9, (500, 500, 600, 600))
    assert associate_detection_with_geometry(nothing_there, positions, px, visible) is None


def test_associate_detection_with_geometry_ignores_points_the_camera_cannot_see():
    positions = np.array([[0.0, 0.0, 5.0]])
    px = np.array([[50.0, 50.0]])
    visible = np.array([False])  # e.g. behind the camera
    det = Detection("chair", 0.9, (0, 0, 100, 100))
    assert associate_detection_with_geometry(det, positions, px, visible) is None


def test_cluster_by_distance_merges_only_by_3d_proximity_not_label_text():
    objects = [
        {"label": "chair", "confidence": 0.9, "position": [0, 0, 0], "embedding": [1.0, 0.0],
         "bbox_px": {}, "source_frame": "a.jpg", "support_points": 2},
        {"label": "seat", "confidence": 0.6, "position": [0.1, 0, 0], "embedding": [0.0, 1.0],  # different label text, same object
         "bbox_px": {}, "source_frame": "b.jpg", "support_points": 4},
        {"label": "chair", "confidence": 0.5, "position": [50, 50, 50], "embedding": [1.0, 0.0],  # same label, far away
         "bbox_px": {}, "source_frame": "c.jpg", "support_points": 1},
    ]
    clusters = cluster_by_distance(objects, distance=1.0)
    assert len(clusters) == 2
    near = next(c for c in clusters if c["detections_merged"] == 2)
    assert near["confidence"] == 0.9  # max over members
    assert near["support_points"] == 4  # max over members
    norm = np.linalg.norm(near["embedding"])
    assert norm == pytest.approx(1.0, abs=1e-6)  # re-normalised mean embedding


def test_cluster_by_distance_keeps_isolated_detections_separate():
    objects = [{"label": "table", "confidence": 0.8, "position": [float(i) * 10, 0, 0], "embedding": [1.0],
               "bbox_px": {}, "source_frame": f"{i}.jpg", "support_points": 1} for i in range(3)]
    clusters = cluster_by_distance(objects, distance=0.5)
    assert len(clusters) == 3
    assert all(c["detections_merged"] == 1 for c in clusters)


# ---- chaya_worker.datasets ------------------------------------------------------------------------

def test_bounding_box_rejects_degenerate_and_out_of_range_boxes():
    BoundingBox(x=0.1, y=0.1, width=0.2, height=0.2, normalized=True)  # valid
    with pytest.raises(ValueError):
        BoundingBox(x=0, y=0, width=0, height=1)
    with pytest.raises(ValueError):
        BoundingBox(x=0.9, y=0, width=0.5, height=0.1, normalized=True)


def test_provenance_requires_a_model_id_when_model_assisted():
    Provenance(source="human", annotator="alice", created_at="2026-01-01T00:00:00Z")
    with pytest.raises(ValueError):
        Provenance(source="model_assisted", annotator="pipeline", created_at="2026-01-01T00:00:00Z")


def test_scaffold_dataset_creates_the_expected_layout(tmp_path):
    d = scaffold_dataset(tmp_path, "venue-fixtures", ["chair", "sofa"], description="test")
    assert (d / "manifest.json").is_file()
    assert (d / "images").is_dir()
    assert (d / "images.jsonl").is_file()
    assert (d / "annotations.jsonl").is_file()
    manifest = json.loads((d / "manifest.json").read_text())
    assert manifest["object_classes"] == ["chair", "sofa"]


def test_scaffold_dataset_refuses_to_overwrite_a_non_empty_directory(tmp_path):
    scaffold_dataset(tmp_path, "ds", ["chair"])
    with pytest.raises(FileExistsError):
        scaffold_dataset(tmp_path, "ds", ["chair"])


def test_dataset_round_trip_and_validation(tmp_path):
    d = scaffold_dataset(tmp_path, "ds", ["chair"])
    image = ImageRecord(image_id="img1", relative_path="img1.jpg", width=200, height=100, venue_id="v1", floor_id="f1")
    append_images(d / "images.jsonl", [image])
    (d / "images" / "img1.jpg").write_bytes(b"not a real jpeg, just a placeholder for the test")

    annotation = Annotation(
        annotation_id="a1", image_id="img1", object_class="chair",
        bounding_box=BoundingBox(x=10, y=10, width=40, height=40),
        provenance=Provenance(source="model_assisted", annotator="chaya-worker", created_at="2026-01-01T00:00:00Z",
                              model_id="IDEA-Research/grounding-dino-tiny"))
    append_annotations(d / "annotations.jsonl", [annotation])

    assert validate_dataset(d) == []
    assert list(read_images(d / "images.jsonl")) == [image]
    assert list(read_annotations(d / "annotations.jsonl")) == [annotation]


def test_validate_dataset_reports_an_annotation_with_no_matching_image(tmp_path):
    d = scaffold_dataset(tmp_path, "ds", ["chair"])
    orphan = Annotation(annotation_id="a1", image_id="missing-image", object_class="chair",
                        bounding_box=BoundingBox(x=0, y=0, width=10, height=10),
                        provenance=Provenance(source="human", annotator="alice", created_at="2026-01-01T00:00:00Z"))
    append_annotations(d / "annotations.jsonl", [orphan])
    problems = validate_dataset(d)
    assert len(problems) == 1 and "missing-image" in problems[0]
