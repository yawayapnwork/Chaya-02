"""The synthetic cleanup scene (benchmarks/b2_geometry_cleanup/synthetic_scene.py) is deterministic and has the parts
the benchmark reports on; the evaluation counts what it should."""

import numpy as np

from benchmarks.b2_geometry_cleanup.synthetic import evaluate
from benchmarks.b2_geometry_cleanup.synthetic_scene import BUCKET, NOISE, STRUCTURE, build, nearest_structure_bucket


def test_the_scene_is_deterministic_and_has_every_part():
    a, b = build(3), build(3)
    assert np.array_equal(a.positions, b.positions) and np.array_equal(a.opacity, b.opacity)
    assert not np.array_equal(build(4).positions[:100], a.positions[:100])
    for part in (*STRUCTURE, *NOISE):
        assert (a.truth == part).sum() > 0, part
    assert ((a.opacity > 0) & (a.opacity < 1)).all()


def test_two_levels_stairs_and_a_ramp_are_where_they_should_be():
    s = build(0)
    assert abs(np.median(s.positions[s.truth == "upper_floor", 2]) - 1.02) < 0.01
    stairs_z = s.positions[s.truth == "stairs", 2]
    assert stairs_z.min() < 0.05 and abs(stairs_z.max() - 1.02) < 0.02
    ramp = s.positions[s.truth == "ramp"]
    slope = np.polyfit(ramp[:, 0], ramp[:, 2], 1)[0]
    assert abs(slope - 1 / 12) < 0.005


def test_noise_takes_the_bucket_of_the_surface_behind_it_and_evaluation_counts_parts():
    s = build(0)
    behind = nearest_structure_bucket(s)
    assert set(behind) <= set(BUCKET.values())
    keep = np.ones(len(s.positions), bool)
    keep[s.truth == "floater"] = False
    keep[np.where(s.truth == "stairs")[0][:10]] = False
    r = evaluate(s, keep)
    noise = np.isin(s.truth, NOISE).sum()
    assert r["noise_recall"] == (s.truth == "floater").sum() / noise
    assert r["kept stairs"] == 1 - 10 / (s.truth == "stairs").sum() and r["kept floor"] == 1.0
