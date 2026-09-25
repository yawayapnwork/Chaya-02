"""Tests of the benchmark framework itself (not of Chaya): the result schema refuses to blur kinds, the coverage tool
computes what its docstring says, and B3's scoring and dataset guard behave. Run: python -m pytest benchmarks/tests."""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmarks.b1_capture_path.reconstruction_coverage import coverage, rasterise  # noqa: E402
from benchmarks.b3_semantic_search.run import check_synonym_rule, random_reference, score  # noqa: E402
from benchmarks.common.results import Metric, unavailable  # noqa: E402


def test_a_metric_must_say_what_kind_of_number_it_is():
    with pytest.raises(ValueError):
        Metric("x", 1, "m", "guess")
    with pytest.raises(ValueError, match="only an unavailable"):
        Metric("x", None, "m", "measured")
    with pytest.raises(ValueError, match="must say why"):
        Metric("x", None, "m", "unavailable")
    with pytest.raises(ValueError):
        Metric("x", 3, "m", "unavailable", note="why")  # an unavailable metric cannot carry a number
    m = unavailable("coverage", "%", "no GPU", "run it on a GPU host")
    assert m.value is None and m.command


def test_rasterise_counts_the_cells_inside_a_square():
    inside, origin, nx, ny = rasterise([[[0, 0], [2, 0], [2, 1], [0, 1]]], 0.25)
    assert (nx, ny) == (8, 4) and inside.sum() == 32 and tuple(origin) == (0, 0)


def test_coverage_counts_only_dense_enough_opaque_floor_points():
    plan = {"polygons": [[[0, 0], [1, 0], [1, 1], [0, 1]]], "floor_height": 0.0, "plane": "xz"}  # 4 x 4 = 16 cells at 0.25 m
    rng = np.random.default_rng(0)
    # 5 points on the floor in each of the 8 cells with x < 0.5 ...
    pts = [[x + rng.uniform(0.02, 0.23), rng.uniform(-0.05, 0.05), z + rng.uniform(0.02, 0.23)]
           for x in (0.0, 0.25) for z in (0.0, 0.25, 0.5, 0.75) for _ in range(5)]
    # ... points too high above the floor in the other half, and transparent points on the floor there
    high = [[0.6, 1.5, 0.6]] * 20
    faint = [[0.8, 0.0, 0.8]] * 20
    positions = np.asarray(pts + high + faint, dtype=float)
    opacity = np.asarray([0.9] * len(pts) + [0.9] * 20 + [0.01] * 20)
    r = coverage(positions, opacity, plan, resolution=0.25, min_points=3, min_opacity=0.1, height_tolerance=0.1)
    assert r["target_cells"] == 16 and r["covered_cells"] == 8
    assert math.isclose(r["coverage_pct"], 50.0) and math.isclose(r["uncovered_area_m2"], 0.5)


DS = {"pois": [{"label": "Cafe", "category": "food", "tags": ["coffee"]}, {"label": "ATM", "category": "finance", "tags": []},
               {"label": "Lift", "category": "elevator", "tags": []}],
      "queries": [{"q": "cafe", "type": "exact", "expected": ["Cafe"]},
                  {"q": "espresso", "type": "synonym", "expected": ["Cafe"]},
                  {"q": "money", "type": "ambiguous", "expected": ["ATM", "Cafe"]},
                  {"q": "pool", "type": "zero_result", "expected": []}]}


def test_scoring_top1_top5_and_zero_result_rejection():
    ranked = {"cafe": ["Cafe", "ATM"], "espresso": ["ATM", "Lift", "Cafe"], "money": ["Lift", "Lift", "Lift", "Lift", "Lift", "ATM"],
              "pool": []}
    s = score(ranked, DS)
    assert s["exact"] == {"n": 1, "top1": 1.0, "top5": 1.0}
    assert s["synonym"] == {"n": 1, "top1": 0.0, "top5": 1.0}
    assert s["ambiguous"]["top5"] == 0.0, "the 6th result does not count for top-5"
    assert s["zero_result"]["correct_rejection"] == 1.0
    assert s["all_answerable"]["n"] == 3


def test_random_reference_matches_the_closed_form():
    ref = random_reference(DS)
    assert math.isclose(ref["ambiguous"]["top1"], 2 / 3)
    assert ref["exact"]["top5"] == 1.0  # with only 3 POIs, any 5-list contains everything


def test_the_synonym_guard_rejects_a_query_sharing_a_word_with_its_answer():
    bad = json.loads(json.dumps(DS))
    bad["queries"][1]["q"] = "coffee shop"
    with pytest.raises(SystemExit, match="shares"):
        check_synonym_rule(bad)
    check_synonym_rule(DS)


def test_the_committed_dataset_obeys_its_own_rules():
    ds = json.loads((Path(__file__).resolve().parents[1] / "b3_semantic_search" / "dataset.json").read_text(encoding="utf-8"))
    check_synonym_rule(ds)
    labels = {p["label"] for p in ds["pois"]}
    for q in ds["queries"]:
        assert set(q["expected"]) <= labels, q
        assert (q["type"] == "zero_result") == (not q["expected"]), q
