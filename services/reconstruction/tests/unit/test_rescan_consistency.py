"""What keeps an incremental re-scan regional (docs/rescan.md): downstream stages use the merged venue, never the region
alone, with that cloud's own labels (chaya_worker.stages.base.venue_cloud); and the re-bake is checked against the parent
scene outside the region (chaya_worker.navmesh.compare_outside_region, obstacle_cells_under_navmesh). Pure: SYNTHETIC
labelled points (tests/navmesh/venue_scene.py), no Recast."""

from __future__ import annotations

from types import SimpleNamespace

import numpy as np
import pytest

from chaya_worker.errors import StageError
from chaya_worker.navmesh import (
    GROUND_LABELS,
    OBSTACLE_LABELS,
    RESCAN_INFLUENCE_CELLS,
    Polygon,
    build_surface_model,
    cells_outside_region,
    compare_outside_region,
    obstacle_cells_under_navmesh,
)
from chaya_worker.settings import Settings
from chaya_worker.stages.base import venue_cloud
from tests.navmesh import venue_scene as V

S = Settings()
REGION = np.array([[7.2, 0.2], [12.0, 0.2], [12.0, 2.8], [7.2, 2.8]])  # the step lane
MARGIN = RESCAN_INFLUENCE_CELLS * S.navmesh_floor_grid_m


# ---- venue_cloud: the merged venue, with its own labels -------------------------------------------------------------------


def _ctx(kinds: list[str], *, rescan: bool):
    inputs = {k: [SimpleNamespace(kind=k)] for k in kinds}
    order = {"regionGeometry": {"points": REGION.tolist()}} if rescan else {}
    return SimpleNamespace(order=order, inputs_of=lambda *ks: [f for k in ks for f in inputs.get(k, [])])


def test_a_rescan_stage_uses_the_merged_cloud_with_the_merged_labels_not_the_regions():
    """A re-scan run holds the region's SPLAT_CLEAN and SEMANTIC_LABELS_CLEAN next to the merged ones. PLANE_FITTING used to
    pair SPLAT_MERGED with the region's labels, which cannot match it."""
    ctx = _ctx(["SPLAT_CLEAN", "SEMANTIC_LABELS_CLEAN", "SPLAT_MERGED", "SEMANTIC_LABELS_MERGED"], rescan=True)
    splat_kind, _, labels_kind, labels = venue_cloud(ctx, "PLANE_FITTING")
    assert (splat_kind, labels_kind) == ("SPLAT_MERGED", "SEMANTIC_LABELS_MERGED") and labels[0].kind == "SEMANTIC_LABELS_MERGED"


def test_without_merged_labels_a_rescan_stage_gets_no_labels_rather_than_the_regions():
    ctx = _ctx(["SPLAT_CLEAN", "SEMANTIC_LABELS_CLEAN", "SPLAT_MERGED"], rescan=True)
    assert venue_cloud(ctx, "PLANE_FITTING")[2:] == ("SEMANTIC_LABELS_MERGED", [])


def test_a_rescan_stage_never_falls_back_to_the_region_alone():
    with pytest.raises(StageError) as err:
        venue_cloud(_ctx(["SPLAT_CLEAN", "SEMANTIC_LABELS_CLEAN"], rescan=True), "ARTIFACT_GENERATION")
    assert err.value.code == "INPUT_INVALID" and "SPLAT_MERGED" in str(err.value)


def test_a_full_reconstruction_uses_its_cleaned_cloud():
    assert venue_cloud(_ctx(["SPLAT", "SPLAT_CLEAN", "SEMANTIC_LABELS_CLEAN"], rescan=False), "X")[::2] == ("SPLAT_CLEAN", "SEMANTIC_LABELS_CLEAN")


# ---- compare_outside_region: the parent scene, unchanged outside the region -----------------------------------------------


def _model(points: np.ndarray, labels: np.ndarray):
    return build_surface_model(points[np.isin(labels, GROUND_LABELS)], points[np.isin(labels, OBSTACLE_LABELS)], reference_z=0.0,
                               cell_m=S.navmesh_floor_grid_m, min_points_per_cell=S.navmesh_floor_min_points_per_cell,
                               ground_band_m=S.navmesh_ground_band_m, agent_max_climb_m=S.navmesh_agent_max_climb_m,
                               agent_height_m=S.navmesh_agent_height_m, step_min_rise_m=S.navmesh_step_min_rise_m,
                               max_level_above_reference_m=S.navmesh_max_level_above_floor_m,
                               accessible_width_m=S.navmesh_accessible_width_m, width_cap_m=S.navmesh_width_cap_m)


@pytest.fixture(scope="module")
def venue():
    return V.build()


@pytest.fixture(scope="module")
def parent(venue):
    return _model(*venue)


def _with_crate(points, labels, box, z0=0.0):
    """The scene with a new 1 m crate standing on the floor in `box` (the floor under it now hidden)."""
    under = V.in_box(points, box) & (labels == "floor")
    crate = V._block(box, z0 + 0.2, z0 + 1.0)
    return (np.vstack([points[~under], crate]),
            np.concatenate([labels[~under], np.array(["furniture"] * len(crate), dtype=object)]))


def test_an_unchanged_scene_is_consistent(venue, parent):
    report = compare_outside_region(parent, _model(*venue), REGION, margin_m=MARGIN)
    assert report["consistent"] and report["cells_differing"] == 0
    assert report["cells_compared"] > 3000 and report["obstacle_cells_outside_region"]["parent"] > 100


def test_a_change_inside_the_region_is_the_re_scans_business(venue, parent):
    merged = _model(*_with_crate(*venue, (10.4, 11.2, 0.8, 1.6), z0=V.STEP_RISE_M))
    report = compare_outside_region(parent, merged, REGION, margin_m=MARGIN)
    assert report["consistent"], report
    assert merged.obstacle.sum() > parent.obstacle.sum(), "the crate is in the merged model, inside the region"


@pytest.mark.parametrize("change", ["sofa_gone", "sofa_relabelled_floor", "new_obstacle_outside"])
def test_a_change_outside_the_region_is_detected(venue, parent, change):
    points, labels = venue
    if change == "sofa_gone":
        keep = ~(V.in_box(points, V.SOFA) & (labels == "furniture"))
        merged = _model(points[keep], labels[keep])
    elif change == "sofa_relabelled_floor":
        merged = _model(points, np.where(V.in_box(points, V.SOFA) & (labels == "furniture"), "floor", labels))
    else:
        merged = _model(*_with_crate(points, labels, (5.0, 5.6, 3.0, 3.6)))
    report = compare_outside_region(parent, merged, REGION, margin_m=MARGIN)
    assert not report["consistent"] and report["cells_differing"] > 0
    if change == "new_obstacle_outside":
        assert report["obstacle_cells_added"] > 0
    else:
        assert report["obstacle_cells_lost"] > 0
        assert V.in_box(np.array(report["differing_cell_centres_xy"]), V.SOFA, margin=0.3).all()


def test_a_merged_scene_that_grows_inside_the_region_still_lines_up_with_the_parent(venue, parent):
    """The re-scan saw floor beyond the parent's extent (inside a region reaching past it). The two models then have
    different origins and shapes; they are compared on their common grid, and agree outside the region."""
    points, labels = venue
    x, y = np.meshgrid(np.arange(12.025, 12.6, 0.05), np.arange(0.525, 2.5, 0.05))
    extra = np.stack([x.ravel(), y.ravel(), np.full(x.size, V.STEP_RISE_M)], axis=1)
    merged = _model(np.vstack([points, extra]), np.concatenate([labels, np.array(["floor"] * len(extra), dtype=object)]))
    assert merged.ground_z.shape != parent.ground_z.shape
    grown = np.array([[7.2, 0.2], [12.8, 0.2], [12.8, 2.8], [7.2, 2.8]])
    assert compare_outside_region(parent, merged, grown, margin_m=MARGIN)["consistent"]


def test_the_margin_covers_exactly_the_surface_models_reach():
    """A cell is compared only when its centre is more than the margin outside the polygon."""
    polygon = np.array([[1.0, 1.0], [2.0, 1.0], [2.0, 2.0], [1.0, 2.0]])
    outside = cells_outside_region(np.zeros(2), (30, 30), 0.1, polygon, MARGIN)
    assert not outside[15, 15], "inside"
    assert not outside[21, 15], "2.15 m: 0.15 m from the edge, within the margin"
    assert outside[23, 15], "2.35 m: 0.35 m from the edge, beyond it"


# ---- obstacle_cells_under_navmesh ---------------------------------------------------------------------------------------


def test_obstacle_cells_a_navmesh_polygon_covers_are_found(parent):
    sofa_cells = V.in_box(parent.origin + (np.argwhere(parent.obstacle) + 0.5) * parent.cell_m, V.SOFA)
    assert sofa_cells.any()
    over_sofa = Polygon(0, [(0.9, 3.5, 0.0), (2.1, 3.5, 0.0), (2.1, 4.9, 0.0), (0.9, 4.9, 0.0)])
    beside_sofa = Polygon(1, [(2.5, 3.5, 0.0), (3.5, 3.5, 0.0), (3.5, 4.9, 0.0)])
    everywhere = np.ones(parent.obstacle.shape, dtype=bool)
    found = obstacle_cells_under_navmesh([beside_sofa, over_sofa], parent, everywhere)
    assert len(found) == int(sofa_cells.sum()) and V.in_box(found, V.SOFA).all()
    assert len(obstacle_cells_under_navmesh([beside_sofa], parent, everywhere)) == 0
    assert len(obstacle_cells_under_navmesh([over_sofa], parent, np.zeros_like(everywhere))) == 0, "outside the mask: not checked"
