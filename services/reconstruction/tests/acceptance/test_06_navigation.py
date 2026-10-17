"""6. NAVIGATION: routes on the navmesh the REAL NAVIGATION_BAKING (Recast/Detour, chaya-navmesh) baked for this very run,
around the scene's real obstacles; accessibility, unreachable destinations, and a two-floor route."""

from __future__ import annotations

import datetime as dt

import numpy as np
import pytest

from . import stack

pytestmark = pytest.mark.acceptance
PILLARS = [(1.0, 1.0), (4.0, 3.0), (5.0, 1.0)]  # canonical centres; each 0.4 m x 0.4 m (generate_viewer_scene.PILLAR_HALF)


def _poi(user, venue: str, floor: str, label: str, x: float, y: float, category: str = "waypoint") -> str:
    return user.ok("POST", f"/api/v1/venues/{venue}/pois", json={"floorId": floor, "label": label, "category": category, "tags": [],
                                                                 "description": "acceptance waypoint", "x": x, "y": y, "z": 0.0})["id"]


def _route(user, body: dict):
    return user("POST", "/api/v1/navigation/routes", json=body)


def _crosses_pillar(waypoints: list[dict]) -> list:
    """Every waypoint segment sampled every 2 cm: a point inside a pillar's footprint means the route walked through it."""
    bad = []
    for a, b in zip(waypoints, waypoints[1:], strict=False):
        if a.get("floorId") != b.get("floorId"):
            continue
        for t in np.linspace(0, 1, max(2, int(np.hypot(b["x"] - a["x"], b["y"] - a["y"]) / 0.02))):
            x, y = a["x"] + t * (b["x"] - a["x"]), a["y"] + t * (b["y"] - a["y"])
            bad += [(round(x, 2), round(y, 2)) for cx, cy in PILLARS if abs(x - cx) < 0.2 and abs(y - cy) < 0.2]
    return bad


@pytest.fixture(scope="module")
def targets(floor0_v1, world) -> dict:
    mgr = world.u("manager")
    return {"east": _poi(mgr, world.venue_a, world.floor0, "Acceptance east end", 5.5, 3.5),
            "far": _poi(mgr, world.venue_a, world.floor0, "Acceptance far away", 14.0, 2.0)}


def test_a_route_is_generated_from_this_runs_navmesh_and_avoids_the_obstacles(floor0_v1, world, targets, ev):
    """From the west side to the east end, around the green pillar. The start is on the walkable floor: a start inside an
    obstacle's agent-radius margin is joined to the nearest node by a straight line that only avoids REPORTED obstacles
    (docs/navigation.md, "Snapping"), so it can cut a static obstacle's corner (docs/ACCEPTANCE.md, findings)."""
    r = _route(world.u("operator"), {"venueId": world.venue_a, "floorId": world.floor0, "start": [0.5, 2.5, 0.0],
                                     "destinationPoiId": targets["east"]})
    j = r.json() if r.ok else {}
    ev.check("6.1", "Route found", "200 with waypoints", r.status_code == 200 and len(j.get("waypoints", [])) >= 2,
             f"HTTP {r.status_code} {r.text[:300] if not r.ok else str(len(j['waypoints'])) + ' waypoints, ' + str(round(j['distanceMeters'], 2)) + ' m'}")
    src = (j.get("routingSources") or [{}])[0]
    nav_sha = stack.artifacts_of(floor0_v1.status, "NAVIGATION_BAKING")["NAVMESH"]
    ev.check("6.2", "Routed on the navmesh this run's real bake produced", "source RECAST_NAVMESH; navmeshSha256 == the run's NAVMESH artifact",
             src.get("source") == "RECAST_NAVMESH" and src.get("navmeshSha256") == (nav_sha.get("sha256") or nav_sha.get("checksumSha256")),
             f"source={src.get('source')} navmesh={src.get('navmeshSha256')} artifact={nav_sha.get('sha256')} recast={src.get('recastnavigationVersion')} path={src.get('pathMethod')}")
    straight = float(np.hypot(5.5 - 0.5, 3.5 - 2.5))
    ev.check("6.3", "The path is a plausible walk", "distance >= straight line (5.10 m) and < 2x it", straight - 1e-6 <= j.get("distanceMeters", 0) < 2 * straight,
             f"{j.get('distanceMeters')} m")
    bad = _crosses_pillar(j.get("waypoints", []))
    ev.check("6.4", "No segment passes through a pillar", "no sampled point inside a pillar footprint", not bad, f"{bad[:5]}")


def test_accessible_routing_uses_the_step_free_graph(floor0_v1, world, targets, ev):
    """The floor is flat and wide: a STEP_FREE route exists and says which constraints it applied."""
    r = _route(world.u("operator"), {"venueId": world.venue_a, "floorId": world.floor0, "start": [0.5, 2.5, 0.0],
                                     "destinationPoiId": targets["east"], "accessibility": "STEP_FREE"})
    j = r.json() if r.ok else {}
    ev.check("6.5", "Step-free route", "200, profile STEP_FREE, constraints listed", r.status_code == 200
             and j.get("accessibilityProfile") == "STEP_FREE" and j.get("accessibilityConstraintsApplied"),
             f"HTTP {r.status_code} profile={j.get('accessibilityProfile')} constraints={j.get('accessibilityConstraintsApplied')} {'' if r.ok else r.text[:200]}")
    ev.check("6.6", "It also avoids the pillars", "no sampled point inside a pillar", not _crosses_pillar(j.get("waypoints", [])), "")


def test_unreachable_destinations_are_refused_with_a_reason(floor0_v1, world, targets, ev):
    """A destination far off the floor, and one fenced in by a reported obstacle: no route, never a made-up one."""
    op = world.u("operator")
    r = _route(op, {"venueId": world.venue_a, "floorId": world.floor0, "start": [0.5, 0.5, 0.0], "destinationPoiId": targets["far"]})
    ev.check("6.7", "Destination outside the navmesh's footprint", "422 DESTINATION_OUT_OF_BOUNDS", r.status_code == 422
             and "DESTINATION_OUT_OF_BOUNDS" in r.text, f"HTTP {r.status_code} {r.text[:200]}")
    fence = {"floorId": world.floor0, "minX": 4.9, "minY": 2.9, "maxX": 6.2, "maxY": 4.2,
             "observedAt": dt.datetime.now(dt.UTC).isoformat()}
    r = _route(op, {"venueId": world.venue_a, "floorId": world.floor0, "start": [0.5, 0.5, 0.0], "destinationPoiId": targets["east"],
                    "blockedRegions": [fence]})
    ev.check("6.8", "Destination enclosed by a reported obstacle", "no 200: a refusal with a code", r.status_code >= 400
             and ("code" in r.text), f"HTTP {r.status_code} {r.text[:240]}")


def test_a_route_crosses_floors_only_through_a_registered_connection(floor0_v1, floor1_v1, world, ev):
    """Floor 0 to floor 1: by the stairs for STANDARD; STEP_FREE refuses until the elevator's accessibility is verified,
    then takes the elevator, never the stairs."""
    mgr = world.u("manager")
    v = world.venue_a
    target = _poi(mgr, v, world.floor1, "Acceptance level-1 desk", 3.0, 2.0)
    stairs = (_poi(mgr, v, world.floor0, "Stairs (ground)", 5.5, 0.5, "stairs"), _poi(mgr, v, world.floor1, "Stairs (level 1)", 5.5, 0.5, "stairs"))
    lift = (_poi(mgr, v, world.floor0, "Lift (ground)", 0.5, 3.5, "elevator"), _poi(mgr, v, world.floor1, "Lift (level 1)", 0.5, 3.5, "elevator"))
    mgr.ok("POST", f"/api/v1/venues/{v}/floor-connections", json={"connectorType": "STAIRS", "fromFloorId": world.floor0, "fromPoiId": stairs[0],
                                                                  "toFloorId": world.floor1, "toPoiId": stairs[1], "lengthM": 6.0})
    elevator = mgr.ok("POST", f"/api/v1/venues/{v}/floor-connections", json={"connectorType": "ELEVATOR", "fromFloorId": world.floor0,
                                                                             "fromPoiId": lift[0], "toFloorId": world.floor1, "toPoiId": lift[1], "minClearanceM": 1.1})
    body = {"venueId": v, "floorId": world.floor0, "start": [3.0, 0.5, 0.0], "destinationPoiId": target}
    r = _route(world.u("operator"), body)
    j = r.json() if r.ok else {}
    t = j.get("floorTransitions") or []
    ev.check("6.9", "STANDARD crosses to floor 1 through a registered connection", "200; one transition floor 0 -> floor 1",
             r.status_code == 200 and len(t) == 1 and t[0]["fromFloorId"] == world.floor0 and t[0]["toFloorId"] == world.floor1,
             f"HTTP {r.status_code} transitions={[(x['connectorType'], x['distanceMeters']) for x in t]} {'' if r.ok else r.text[:200]}")
    ev.check("6.10", "Each floor's leg is on its own floor's navmesh", "two routing sources, RECAST_NAVMESH, different floors",
             sorted(s["floorId"] for s in j.get("routingSources", [])) == sorted([world.floor0, world.floor1])
             and all(s["source"] == "RECAST_NAVMESH" for s in j.get("routingSources", [])), f"{j.get('routingSources')}")
    r = _route(world.u("operator"), {**body, "accessibility": "STEP_FREE"})
    ev.check("6.11", "STEP_FREE fails closed while no step-free connector is verified", "4xx FLOOR_CONNECTION_UNAVAILABLE (never the stairs)",
             r.status_code >= 400 and "FLOOR_CONNECTION_UNAVAILABLE" in r.text, f"HTTP {r.status_code} {r.text[:200]}")
    mgr.ok("PUT", f"/api/v1/venues/{v}/floor-connections/{elevator['id']}/accessibility-verification", json={"verified": True})
    r = _route(world.u("operator"), {**body, "accessibility": "STEP_FREE"})
    j = r.json() if r.ok else {}
    t = j.get("floorTransitions") or []
    ev.check("6.12", "After verification, STEP_FREE takes the elevator", "200; the one transition is the ELEVATOR",
             r.status_code == 200 and [x["connectorType"] for x in t] == ["ELEVATOR"], f"HTTP {r.status_code} {[x['connectorType'] for x in t]} {'' if r.ok else r.text[:200]}")
