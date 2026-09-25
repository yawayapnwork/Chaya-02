"""The Chaya-canonical <-> Recast axis boundary (chaya_worker.recast_boundary) and the two navmesh functions that
are allowed to cross it."""

from __future__ import annotations

import numpy as np
import pytest

from chaya_worker.navmesh import parse_recast_polygons, polygon_slope_degrees, write_walkable_obj
from chaya_worker.recast_boundary import CANONICAL_TO_RECAST, canonical_to_recast, recast_to_canonical


def test_canonical_up_is_recast_up_and_handedness_is_preserved():
    np.testing.assert_array_equal(canonical_to_recast(np.array([0.0, 0.0, 1.0])), [0.0, 1.0, 0.0])
    np.testing.assert_array_equal(canonical_to_recast(np.array([1.0, 0.0, 0.0])), [1.0, 0.0, 0.0])
    assert np.linalg.det(CANONICAL_TO_RECAST) == 1.0  # a proper rotation: no mirror image of the venue
    np.testing.assert_allclose(CANONICAL_TO_RECAST @ CANONICAL_TO_RECAST.T, np.eye(3))


def test_round_trip_is_exact():
    rng = np.random.default_rng(3)
    pts = rng.uniform(-100, 100, (500, 3))
    np.testing.assert_array_equal(recast_to_canonical(canonical_to_recast(pts)), pts)


def test_write_walkable_obj_writes_recast_axes(tmp_path):
    vertices = np.array([[0.0, 0.0, 2.0], [1.0, 0.0, 2.0], [0.0, 1.0, 2.0]])  # a horizontal triangle 2 m up (canonical)
    path = tmp_path / "w.obj"
    write_walkable_obj(path, vertices, np.array([[0, 1, 2]]), np.array([True]))
    v_lines = [ln.split()[1:] for ln in path.read_text().splitlines() if ln.startswith("v ")]
    recast = np.array(v_lines, dtype=float)
    assert np.allclose(recast[:, 1], 2.0), "the height lands on Recast's +Y"
    np.testing.assert_allclose(recast, canonical_to_recast(vertices), atol=1e-6)


def test_parse_recast_polygons_returns_canonical_coordinates_and_slope_is_against_canonical_up():
    # A flat Recast polygon (constant Recast y) is flat in canonical terms; a polygon tilted in Recast's y is sloped.
    flat_recast = [[0, 1.5, 0], [1, 1.5, 0], [1, 1.5, -1], [0, 1.5, -1]]
    ramp_recast = [[0, 0, 0], [1, 0, 0], [1, 0.5, -1], [0, 0.5, -1]]
    polygons = parse_recast_polygons({"polygons": [{"id": 0, "vertices": flat_recast, "neighbors": [1]},
                                                   {"id": 1, "vertices": ramp_recast, "neighbors": [0]}]})
    assert all(abs(v[2] - 1.5) < 1e-12 for v in polygons[0].vertices), "Recast +Y height becomes canonical z"
    assert polygon_slope_degrees(polygons[0]) < 1e-9
    assert polygon_slope_degrees(polygons[1]) == pytest.approx(np.degrees(np.arctan(0.5)), abs=1e-9)

