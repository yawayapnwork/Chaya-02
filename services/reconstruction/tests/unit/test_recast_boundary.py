"""The Chaya-canonical <-> Recast axis boundary (chaya_worker.recast_boundary), and the chaya_worker.recast functions
that are the only callers allowed to cross it."""

from __future__ import annotations

import ast
from pathlib import Path

import numpy as np
import pytest

from chaya_worker.navmesh import NavGeometry, polygon_slope_degrees
from chaya_worker.recast import RecastConfig, polygons_from_report, write_recast_obj
from chaya_worker.recast_boundary import (
    CANONICAL_TO_RECAST,
    canonical_half_extents_to_recast,
    canonical_to_recast,
    recast_to_canonical,
)
from chaya_worker.settings import Settings

WORKER = Path(__file__).resolve().parents[2] / "chaya_worker"


def test_canonical_up_is_recast_up_and_handedness_is_preserved():
    np.testing.assert_array_equal(canonical_to_recast(np.array([0.0, 0.0, 1.0])), [0.0, 1.0, 0.0])
    np.testing.assert_array_equal(canonical_to_recast(np.array([1.0, 0.0, 0.0])), [1.0, 0.0, 0.0])
    assert np.linalg.det(CANONICAL_TO_RECAST) == 1.0  # a proper rotation: no mirror image of the venue
    np.testing.assert_allclose(CANONICAL_TO_RECAST @ CANONICAL_TO_RECAST.T, np.eye(3))


def test_round_trip_is_exact():
    rng = np.random.default_rng(3)
    pts = rng.uniform(-100, 100, (500, 3))
    np.testing.assert_array_equal(recast_to_canonical(canonical_to_recast(pts)), pts)


def test_half_extents_map_horizontal_and_vertical_to_recast_axes():
    np.testing.assert_array_equal(canonical_half_extents_to_recast(0.5, 2.0), [0.5, 2.0, 0.5])


def test_only_chaya_worker_recast_crosses_the_boundary():
    """No other worker module imports the conversion functions: there is exactly one boundary."""
    importers = set()
    for path in WORKER.rglob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module and node.module.endswith("recast_boundary"):
                importers.add(path.relative_to(WORKER).as_posix())
    assert importers == {"recast.py"}


def test_write_recast_obj_writes_recast_axes_and_groups(tmp_path):
    vertices = np.array([[0.0, 0.0, 2.0], [1.0, 0.0, 2.0], [0.0, 1.0, 2.0], [0.0, 0.0, 3.0]])  # 2 m up (canonical)
    path = tmp_path / "w.obj"
    write_recast_obj(path, NavGeometry(vertices, np.array([[0, 1, 2], [0, 1, 3]]), np.array([False, True])))
    lines = path.read_text().splitlines()
    recast = np.array([ln.split()[1:] for ln in lines if ln.startswith("v ")], dtype=float)
    assert np.allclose(recast[:3, 1], 2.0), "the height lands on Recast's +Y"
    np.testing.assert_allclose(recast, canonical_to_recast(vertices), atol=1e-6)
    assert lines.index("g obstacle") > lines.index("g walkable_candidates")
    # the walkable face keeps its upward normal through the rotation (Recast's slope test reads +Y)
    a, b, c = recast[:3]
    assert np.cross(b - a, c - a)[1] > 0


def test_polygons_from_report_returns_canonical_coordinates_and_slope_is_against_canonical_up():
    # A flat Recast polygon (constant Recast y) is flat in canonical terms; a polygon tilted in Recast's y is sloped.
    flat_recast = [[0, 1.5, 0], [1, 1.5, 0], [1, 1.5, -1], [0, 1.5, -1]]
    ramp_recast = [[0, 0, 0], [1, 0, 0], [1, 0.5, -1], [0, 0.5, -1]]
    report = {"polygons": [{"id": 0, "vertices": flat_recast, "links": [{"neighbor": 1, "portal": [[1, 1.5, 0], [1, 1.5, -1]]}]},
                           {"id": 1, "vertices": ramp_recast, "links": []}]}
    polygons = polygons_from_report(report)
    assert all(abs(v[2] - 1.5) < 1e-12 for v in polygons[0].vertices), "Recast +Y height becomes canonical z"
    assert polygons[0].links[0].portal == ((1.0, 0.0, 1.5), (1.0, 1.0, 1.5))
    assert polygon_slope_degrees(polygons[0]) < 1e-9
    assert polygon_slope_degrees(polygons[1]) == pytest.approx(np.degrees(np.arctan(0.5)), abs=1e-9)


def test_recast_config_is_metre_denominated_and_complete():
    config = RecastConfig.from_settings(Settings())
    argv = config.argv()
    assert argv[argv.index("--agent-radius-m") + 1] == "0.35"
    assert argv[argv.index("--verts-per-poly") + 1] == "6"
    flags = {a for a in argv if a.startswith("--")}
    assert flags == {f"--{k.replace('_', '-')}" for k in config.as_dict()}
    assert all(k.endswith(("_m", "_m2", "_deg")) or k == "verts_per_poly" for k in config.as_dict()), "every value names its unit"
