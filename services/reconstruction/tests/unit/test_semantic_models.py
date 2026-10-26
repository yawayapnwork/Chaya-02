"""Regression tests for the semantic models' contracts that need no ML framework: label mapping, model provenance, the
detector's post-processing across transformers versions, evaluation metrics, and search-index compatibility between the
worker, the vision service and the API. (Training and real model loading: tests/ml, run in chaya-ml-eval.)"""

from __future__ import annotations

import json
import re
from pathlib import Path

import numpy as np
import pytest

from chaya_worker.evaluation import (
    average_precision,
    bootstrap_ci,
    box_iou,
    confusion,
    iou_per_class,
    match_image,
    mean_iou,
    paired_bootstrap_diff,
    pixel_accuracy,
    precision_recall_at,
    retrieval_metrics,
    top1_accuracy,
)
from chaya_worker.grounding_dino import phrase_token_spans, prompt_phrases, resolve_prompt_phrase, post_process
from chaya_worker.model_provenance import (
    CARD_SCHEMA,
    MODEL_CARD,
    STOCK_MODELS,
    ModelCardError,
    ModelLicenseError,
    check_license_policy,
    enforce_license_policy,
    provenance,
    read_card,
)
from chaya_worker.navmesh import GROUND_LABELS, OBSTACLE_LABELS
from chaya_worker.semantic_classes import (
    ADE20K_150_REVIEWED,
    BUCKETS,
    CLUTTER,
    FLOOR,
    FURNITURE,
    RUNTIME_BUCKET_OF,
    STAIRS,
    UNKNOWN,
    WALL,
    bucket_label,
    class_mapping,
)
from chaya_worker.settings import Settings
from chaya_worker.stages.semantic_segmentation import share_histogram, vote_labels, winning_shares

ROOT = Path(__file__).resolve().parents[4]
FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"
ADE = {int(k): v for k, v in json.loads((FIXTURES / "ade20k-150-id2label.json").read_text(encoding="utf-8"))["id2label"].items()}


# ---- label mapping -----------------------------------------------------------------------------------------------------

def test_the_default_checkpoints_label_set_uses_the_reviewed_table_and_every_label_has_a_decision():
    mapping, method = class_mapping(ADE)
    assert method.startswith("reviewed:ade20k-150@")
    assert len(mapping) == 150 and set(mapping.values()) <= set(BUCKETS)
    assert {v.strip() for v in ADE.values()} == set(ADE20K_150_REVIEWED)


@pytest.mark.parametrize("label,expected", [
    ("bannister", FURNITURE),  # was clutter: a thin barrier cleanup could delete as isolated noise, while railing was kept
    ("railing", FURNITURE), ("escalator", FURNITURE), ("kitchen island", FURNITURE), ("seat", FURNITURE),
    ("barrel", CLUTTER),  # was furniture only because it contains "bar"
    ("stairs", STAIRS), ("stairway", STAIRS), ("step", STAIRS), ("stage", FLOOR), ("floor", FLOOR), ("rug", FLOOR),
    ("ceiling", WALL), ("wall", WALL), ("person", CLUTTER), ("lamp", CLUTTER),
])
def test_reviewed_ade20k_decisions(label, expected):
    mapping, _ = class_mapping(ADE)
    ids = [i for i, v in ADE.items() if v.strip() == label]
    assert ids and mapping[ids[0]] == expected


def test_keyword_rule_is_still_available_and_differs_only_where_reviewed():
    reviewed, _ = class_mapping(ADE)
    keyword, method = class_mapping(ADE, method="keyword")
    assert method == "keyword"
    changed = {ADE[i].strip() for i in ADE if reviewed[i] != keyword[i]}
    assert "bannister" in changed and "barrel" in changed and "wall" not in changed and "chair" not in changed


def test_a_checkpoint_trained_on_the_bucket_names_maps_canonically():
    mapping, method = class_mapping(dict(enumerate(BUCKETS)))
    assert method == "canonical" and mapping == dict(enumerate(BUCKETS))
    # Before this change "furniture" fell through the keyword rule to clutter.
    assert bucket_label("furniture") == FURNITURE and bucket_label("clutter") == CLUTTER


def test_ramps_are_floor_at_run_time_and_the_buckets_match_navigation():
    assert RUNTIME_BUCKET_OF["ramp"] == FLOOR
    assert set(GROUND_LABELS) | set(OBSTACLE_LABELS) == set(BUCKETS) | {UNKNOWN}
    assert STAIRS in GROUND_LABELS and FLOOR in GROUND_LABELS


def test_an_unknown_label_set_falls_back_to_keywords():
    mapping, method = class_mapping({0: "floor, flooring", 1: "office chair", 2: "potted fern"})
    assert method == "keyword" and mapping == {0: FLOOR, 1: FURNITURE, 2: CLUTTER}
    with pytest.raises(ValueError):
        class_mapping(ADE, method="guess")


# ---- uncertain labels ----------------------------------------------------------------------------------------------------

def test_a_narrow_winner_becomes_unknown_only_when_a_minimum_share_is_set():
    votes = np.array([[0.51, 0.49, 0, 0, 0], [0.9, 0.1, 0, 0, 0], [0, 0, 0, 0, 0]])
    conf_sum, seen = votes.sum(axis=1), np.array([True, True, False])
    assert vote_labels(votes, conf_sum, seen, 0.5) == [FLOOR, FLOOR, UNKNOWN]  # unchanged default behaviour
    assert vote_labels(votes, conf_sum, seen, 0.5, min_share=0.6) == [UNKNOWN, FLOOR, UNKNOWN]
    shares = winning_shares(votes)
    assert shares[0] == pytest.approx(0.51) and shares[2] == 0.0
    hist = share_histogram(shares, votes.sum(axis=1) > 0)
    assert sum(hist.values()) == 2 and hist["0.5-0.6"] == 1 and hist["0.9-1.0"] == 1


# ---- model provenance ----------------------------------------------------------------------------------------------------

def test_stock_models_are_never_fine_tuned_and_carry_their_licence():
    s = Settings()
    for model_id in (s.semantic_segmentation_model, s.grounding_dino_model, f"open_clip:{s.clip_model_name}:{s.clip_pretrained}"):
        assert model_id in STOCK_MODELS, f"default model {model_id} has no licence record"
        p = provenance(model_id, requested_revision="", resolved_revision=None)
        assert p.kind == "stock" and p.fine_tuned is False and p.license
    seg = provenance(s.semantic_segmentation_model, requested_revision="", resolved_revision="x")
    assert seg.commercial_use is False and "unpinned" in " ".join(seg.notes)


def test_an_unidentified_local_checkpoint_is_not_called_fine_tuned(tmp_path):
    p = provenance(str(tmp_path), requested_revision=None, resolved_revision=None)
    assert p.kind == "unregistered" and p.fine_tuned is False


def _card(**over):
    card = {"schema": CARD_SCHEMA, "task": "semantic-segmentation", "base_model": "nvidia/segformer-b0-finetuned-ade-512-512",
            "base_revision": "abc", "created_at": "2026-10-11T00:00:00Z",
            "dataset": {"name": "d", "version": "1", "sha256": "f" * 64, "splits_used": ["train", "val"]},
            "training": {"seed": 0, "steps": 10}}
    card.update(over)
    return card


def test_only_a_valid_model_card_makes_a_checkpoint_fine_tuned(tmp_path):
    (tmp_path / MODEL_CARD).write_text(json.dumps(_card()), encoding="utf-8")
    p = provenance(str(tmp_path), requested_revision=None, resolved_revision=None)
    assert p.fine_tuned is True and p.kind == "trained" and p.dataset["sha256"] == "f" * 64
    assert p.commercial_use is False and "research" in " ".join(p.notes)  # inherits the base model's terms


@pytest.mark.parametrize("bad", [
    {"dataset": {"name": "d", "version": "1", "sha256": "f" * 64, "splits_used": ["train", "test"]}},  # trained on test
    {"dataset": {"name": "d", "version": "1", "splits_used": ["train"]}},  # no dataset hash
    {"training": {"steps": 3}},  # no seed
    {"schema": "something-else"},
    {"base_model": ""},
])
def test_a_card_that_cannot_back_the_claim_is_refused(tmp_path, bad):
    (tmp_path / MODEL_CARD).write_text(json.dumps(_card(**bad)), encoding="utf-8")
    with pytest.raises(ModelCardError):
        read_card(tmp_path)


# ---- detector post-processing across transformers versions ----------------------------------------------------------------

class _Old:
    def post_process_grounded_object_detection(self, outputs, input_ids, box_threshold=0.25, text_threshold=0.25, target_sizes=None):
        return [{"box_threshold": box_threshold, "text_threshold": text_threshold, "sizes": target_sizes}]


class _New:
    def post_process_grounded_object_detection(self, outputs, input_ids=None, threshold=0.25, text_threshold=0.25,
                                               target_sizes=None, text_labels=None):
        return [{"threshold": threshold, "text_threshold": text_threshold, "sizes": target_sizes}]


class _Neither:
    def post_process_grounded_object_detection(self, outputs, input_ids=None, text_threshold=0.25, target_sizes=None):
        return [{}]


def test_the_box_threshold_reaches_both_old_and_new_transformers():
    assert post_process(_Old(), None, None, box_threshold=0.35, text_threshold=0.2, target_size=(4, 5)) == {
        "box_threshold": 0.35, "text_threshold": 0.2, "sizes": [(4, 5)]}
    # transformers >= 4.51 renamed it; passing box_threshold there raises TypeError (verified against 4.57.1).
    assert post_process(_New(), None, None, box_threshold=0.35, text_threshold=0.2, target_size=(4, 5))["threshold"] == 0.35
    with pytest.raises(TypeError):
        post_process(_Neither(), None, None, box_threshold=0.35, text_threshold=0.2, target_size=(4, 5))


# ---- evaluation metrics ----------------------------------------------------------------------------------------------------

def test_confusion_iou_and_accuracy_on_a_hand_computed_case():
    gt = np.array([[0, 0, 1, 255], [1, 1, 2, 2]])
    pred = np.array([[0, 1, 1, 0], [1, 1, 2, -1]])
    c = confusion(gt, pred, 3, 255)
    assert c.tolist() == [[1, 1, 0], [0, 3, 0], [0, 0, 1]]  # the ignored and the abstained pixel are not counted
    assert iou_per_class(c).tolist() == pytest.approx([0.5, 0.75, 1.0])
    assert mean_iou(c) == pytest.approx(0.75) and pixel_accuracy(c) == pytest.approx(5 / 6)
    assert np.isnan(iou_per_class(np.zeros((2, 2)))[0])


def test_bootstrap_intervals_cover_the_point_and_a_paired_difference_of_identical_methods_is_zero():
    rng = np.random.default_rng(1)
    per_image = [np.diag(rng.integers(5, 10, 3)) + rng.integers(0, 3, (3, 3)) for _ in range(20)]
    point, lo, hi = bootstrap_ci(per_image, mean_iou, n_boot=200, seed=0)
    assert lo <= point <= hi
    d, dlo, dhi = paired_bootstrap_diff(per_image, per_image, mean_iou, n_boot=50, seed=0)
    assert d == 0 and dlo == 0 and dhi == 0


def test_detection_matching_and_average_precision():
    assert box_iou((0, 0, 2, 2), (1, 1, 3, 3)) == pytest.approx(1 / 7)
    scored = match_image([((0, 0, 10, 10), 0.9), ((0, 0, 10, 10), 0.8), ((50, 50, 60, 60), 0.7)], [(0, 0, 10, 10), (50, 50, 61, 60)])
    assert scored == [(0.9, True), (0.8, False), (0.7, True)]  # one GT box matches one prediction only
    assert average_precision(scored, 2) == pytest.approx(0.5 * 1.0 + 0.5 * (2 / 3))
    assert average_precision([], 3) == 0.0 and np.isnan(average_precision([], 0))
    p, r, tp, fp = precision_recall_at(scored, 2, 0.75)
    assert (p, r, tp, fp) == (0.5, 0.5, 1, 1)


def test_retrieval_metrics():
    sim = np.array([[0.9, 0.1, 0.5], [0.2, 0.8, 0.3]])
    rel = np.array([[False, False, True], [False, True, False]])
    m = retrieval_metrics(sim, rel, ks=(1, 2))
    assert m["recall@1"] == 0.5 and m["recall@2"] == 1.0 and m["mAP"] == pytest.approx((0.5 + 1.0) / 2) and m["queries"] == 2
    assert top1_accuracy(np.array([[0.1, 0.9], [0.8, 0.2]]), np.array([1, 1])) == 0.5


# ---- search-index compatibility --------------------------------------------------------------------------------------------

def test_worker_vision_service_and_api_agree_on_the_embedding_space():
    s = Settings()
    vision = (ROOT / "services/vision/app/clip_text_encoder.py").read_text(encoding="utf-8")
    assert re.search(r'DEFAULT_MODEL_NAME = os.environ.get\("CLIP_MODEL_NAME", "([^"]+)"\)', vision).group(1) == s.clip_model_name
    assert re.search(r'DEFAULT_PRETRAINED = os.environ.get\("CLIP_PRETRAINED", "([^"]+)"\)', vision).group(1) == s.clip_pretrained
    # The same model id format on both sides, so the API's same-model filter compares like with like.
    assert 'return f"open_clip:{self.model_name}:{self.pretrained}"' in vision
    assert 'return f"open_clip:{self.model_name}:{self.pretrained}"' in (ROOT / "services/reconstruction/chaya_worker/clip_embeddings.py").read_text(encoding="utf-8")
    api = (ROOT / "services/api/src/main/java/dev/chaya/api/search/PoiEmbeddingService.java").read_text(encoding="utf-8")
    from chaya_worker.clip_embeddings import EMBEDDING_DIM

    assert f"EMBEDDING_DIM = {EMBEDDING_DIM};" in api
    assert re.search(r"vision\.py|EMBEDDING_DIM = (\d+)", vision) and f"EMBEDDING_DIM = {EMBEDDING_DIM}" in vision
    migrations = "".join(p.read_text(encoding="utf-8") for p in (ROOT / "services/api/src/main/resources/db/migration").glob("V*.sql"))
    assert f"vector({EMBEDDING_DIM})" in migrations and "image_embedding_model" in migrations


# ---- detections without a phrase -----------------------------------------------------------------------------------------

def test_a_detection_without_a_phrase_is_never_published():
    from chaya_worker.stages.semantic_indexing import has_phrase

    # Grounding DINO returns "" when no text token passes text_threshold (B6: thousands at a low box threshold); the API's
    # poi_version.label CHECK would refuse it, so it must be dropped in the worker.
    assert not has_phrase("") and not has_phrase("   ") and not has_phrase("##")
    assert has_phrase("chair") and has_phrase("desk reception desk counter") and has_phrase("##tinguisher")


def test_detector_canonicalizes_merged_and_fragmented_phrases_to_prompt_vocabulary():
    prompt = Settings().object_detection_prompt
    phrases = prompt_phrases(prompt)
    assert "fire extinguisher" in phrases and "reception desk" in phrases and "restroom sign" in phrases
    # WordPiece fragment resolves to its prompt phrase
    assert resolve_prompt_phrase("##tinguisher", prompt) == "fire extinguisher"
    # Merged multi-phrase output resolves to the highest-scoring prompt phrase when logit scores are provided
    assert resolve_prompt_phrase(
        "desk reception desk counter", prompt,
        phrase_scores={"desk": 0.41, "reception desk": 0.64, "counter": 0.29},
        text_threshold=0.25,
    ) == "reception desk"
    assert resolve_prompt_phrase(
        "sofa couch", prompt,
        phrase_scores={"sofa": 0.31, "couch": 0.58},
        text_threshold=0.25,
    ) == "couch"
    # Deterministic fallback when logits are absent
    assert resolve_prompt_phrase("restroom sign exit sign", prompt) == "restroom sign"
    assert resolve_prompt_phrase("   ", prompt) == ""
    assert phrase_token_spans([101, 2001, 1012, 2002, 2003, 1012, 102], {101, 102, 1012}) == [[1], [3, 4]]


def test_commercial_use_license_gate_blocks_research_only_and_unverified_models(tmp_path):
    s = Settings()
    seg = provenance(s.semantic_segmentation_model, requested_revision=None, resolved_revision=None)
    det = provenance(s.grounding_dino_model, requested_revision=None, resolved_revision=None)
    clip = provenance(f"open_clip:{s.clip_model_name}:{s.clip_pretrained}", requested_revision=None, resolved_revision=None)

    assert check_license_policy(seg, commercial_use_only=False) == []
    problems = check_license_policy(seg, commercial_use_only=True)
    assert len(problems) == 1 and problems[0]["code"] == "MODEL_LICENSE_RESTRICTED"
    with pytest.raises(ModelLicenseError):
        enforce_license_policy(seg, commercial_use_only=True)

    # Stock Grounding DINO (Apache-2.0) and OpenCLIP ViT-B-32 openai (MIT) pass commercial-use policy
    enforce_license_policy(det, commercial_use_only=True)
    enforce_license_policy(clip, commercial_use_only=True)

    # Derivative checkpoint trained from research-only SegFormer inherits commercial_use=False and is blocked
    (tmp_path / MODEL_CARD).write_text(json.dumps(_card()), encoding="utf-8")
    trained_seg = provenance(str(tmp_path), requested_revision=None, resolved_revision=None)
    with pytest.raises(ModelLicenseError):
        enforce_license_policy(trained_seg, commercial_use_only=True)
