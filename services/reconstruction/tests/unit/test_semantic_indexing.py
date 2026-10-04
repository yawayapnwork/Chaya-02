"""Pure unit tests for SEMANTIC_INDEXING's geometry/clustering logic and the chaya_worker.datasets
annotation format. No external tools, no ML models."""

from __future__ import annotations

import json

import numpy as np
import pytest

from chaya_worker.datasets import Annotation, BoundingBox, ImageRecord, Provenance
from chaya_worker.datasets.jsonl import append_annotations, append_images, read_annotations, read_images, validate_dataset
from chaya_worker.datasets.scaffold import scaffold_dataset
from chaya_worker.stages.semantic_indexing import cluster_by_distance

# ---- semantic_indexing.py geometry/clustering -----------------------------------------------------

def _det(label, position, embedding, confidence=0.8, frame="a.jpg", support=1, bbox=None, depth_spread=0.05):
    return {"label": label, "confidence": confidence, "position": list(position), "embedding": list(embedding),
            "bbox_px": bbox or {}, "source_frame": frame, "support_points": support,
            "localization": {"depth_spread_m": depth_spread, "coverage": 0.6}}


def test_a_cluster_keeps_how_well_its_position_is_supported():
    """Two frames that agree: MULTI_VIEW, with their disagreement and the surfaces' thickness as the uncertainty. One
    frame: SINGLE_VIEW, a downgrade the search ranks below multi-view evidence (docs/search.md, "Ranking")."""
    clusters = cluster_by_distance([_det("chair", [0, 0, 0], [1.0], frame="a.jpg", depth_spread=0.04),
                                    _det("chair", [0.3, 0, 0], [1.0], frame="b.jpg", depth_spread=0.08),
                                    _det("bench", [9, 0, 0], [1.0], frame="a.jpg", depth_spread=0.02)], distance=0.75)
    chair, bench = sorted(clusters, key=lambda c: c["label"], reverse=True)
    assert chair["localization"]["status"] == "MULTI_VIEW" and chair["localization"]["views"] == 2
    assert chair["localization"]["view_spread_m"] == pytest.approx(0.15)
    assert chair["localization"]["depth_spread_m"] == pytest.approx(0.06)
    assert chair["localization"]["uncertainty_m"] == pytest.approx(0.15)
    assert bench["localization"]["status"] == "SINGLE_VIEW" and bench["localization"]["views"] == 1
    assert bench["localization"]["view_spread_m"] == 0.0 and bench["localization"]["uncertainty_m"] == pytest.approx(0.02)


def test_two_detections_in_one_frame_are_one_view():
    clusters = cluster_by_distance([_det("chair", [0, 0, 0], [1.0], frame="a.jpg"), _det("chair", [0.1, 0, 0], [1.0], frame="a.jpg")],
                                   distance=0.75)
    assert clusters[0]["localization"]["status"] == "SINGLE_VIEW" and clusters[0]["localization"]["views"] == 1


def test_cluster_by_distance_merges_the_same_object_seen_from_several_frames():
    objects = [
        _det("chair", [0, 0, 0], [1.0, 0.0], confidence=0.9, frame="a.jpg", support=2, bbox={"x": 1}),
        _det("Chair ", [0.1, 0, 0], [0.8, 0.6], confidence=0.6, frame="b.jpg", support=4, bbox={"x": 2}),  # label case/space
        _det("chair", [50, 50, 50], [1.0, 0.0], confidence=0.5, frame="c.jpg"),  # same label, far away
    ]
    clusters = cluster_by_distance(objects, distance=1.0)
    assert len(clusters) == 2
    near = next(c for c in clusters if c["detections_merged"] == 2)
    assert near["confidence"] == 0.9  # max over members
    assert near["support_points"] == 4  # max over members
    assert near["position"] == pytest.approx([0.05, 0, 0])  # centroid
    assert (near["source_frame"], near["bbox_px"]) == ("a.jpg", {"x": 1})  # box and its frame from one member
    assert np.linalg.norm(near["embedding"]) == pytest.approx(1.0, abs=1e-6)  # re-normalised mean embedding


def test_cluster_by_distance_never_merges_different_objects_that_are_close_together():
    # docs/ADVERSARIAL_REVIEW.md CV-2: a fire extinguisher 0.3 m from an exit sign must stay two objects, each with its
    # own crop embedding, position and source frame.
    objects = [
        _det("exit sign", [0, 0, 2.0], [1.0, 0.0], confidence=0.7, frame="exit.jpg"),
        _det("fire extinguisher", [0.3, 0, 1.8], [0.0, 1.0], confidence=0.8, frame="ext.jpg"),
    ]
    clusters = cluster_by_distance(objects, distance=0.75)
    assert len(clusters) == 2
    by_label = {c["label"]: c for c in clusters}
    assert by_label["exit sign"]["embedding"] == pytest.approx([1.0, 0.0])
    assert by_label["fire extinguisher"]["embedding"] == pytest.approx([0.0, 1.0])
    assert by_label["fire extinguisher"]["position"] == pytest.approx([0.3, 0, 1.8])
    assert by_label["fire extinguisher"]["source_frame"] == "ext.jpg"


def test_cluster_by_distance_joins_the_nearest_same_label_cluster():
    objects = [_det("chair", [0, 0, 0], [1.0]), _det("chair", [1.0, 0, 0], [1.0]), _det("chair", [0.9, 0, 0], [1.0])]
    clusters = cluster_by_distance(objects, distance=0.5)
    assert sorted(c["detections_merged"] for c in clusters) == [1, 2]
    pair = next(c for c in clusters if c["detections_merged"] == 2)
    assert pair["position"] == pytest.approx([0.95, 0, 0])


def test_cluster_by_distance_keeps_isolated_detections_separate():
    objects = [_det("table", [float(i) * 10, 0, 0], [1.0], frame=f"{i}.jpg") for i in range(3)]
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
