"""5. SEARCH: the real search API, with the real vision service embedding queries, over the objects SEMANTIC_INDEXING indexed.

The DETECTED_OBJECTS were published by the SEMANTIC_INDEXING stand-in (labelled; detector labels at the pillars' known
positions, CLIP text vectors from the running vision service). Ingestion into POIs, query embedding, ranking, venue scoping
and positions are the real product."""

from __future__ import annotations

import math

import pytest

pytestmark = pytest.mark.acceptance


def _search(user, venue: str, q: str, **params):
    return user("GET", f"/api/v1/venues/{venue}/search", params={"q": q, **params})


DETECTOR_LABELS = {"red pillar", "green pillar", "blue pillar"}  # exactly what the SEMANTIC_INDEXING stand-in reported


def _auto(p: dict) -> bool:
    """A POI ingested from DETECTED_OBJECTS: it carries the detector's label verbatim (the suite's manual POIs are 'Red pillar')."""
    return p.get("label") in DETECTOR_LABELS


def _pos(r: dict) -> list[float] | None:
    p = r.get("position") or r.get("canonicalPosition")
    if isinstance(p, dict):
        return [p.get("x"), p.get("y"), p.get("z")]
    if p is None and r.get("x") is not None:
        return [r["x"], r["y"], r.get("z")]
    return p


def test_a_query_finds_the_indexed_objects_at_their_positions(floor0_v1, world, cfg, ev):
    """'red pillar' finds the auto-detected red pillar, at (1, 1) on floor 0, with the real CLIP embedding path."""
    if not cfg.vision:
        pytest.skip("BLOCKED: no vision service on this stack (ACC_VISION_URL): the stand-in index and the query need real CLIP")
    mgr = world.u("manager")
    pois = [p for p in mgr.ok("GET", f"/api/v1/venues/{world.venue_a}/pois") if p.get("floorId") == world.floor0]
    auto = [p for p in pois if _auto(p)]
    ev.check("5.1", "SEMANTIC_INDEXING's objects were ingested as POIs on floor 0", "3 auto-detected POIs (red, green, blue pillar)",
             sorted(p["label"].lower() for p in auto) == ["blue pillar", "green pillar", "red pillar"],
             f"{[(p['label'], p.get('category')) for p in pois]}")
    r = _search(mgr, world.venue_a, "red pillar")
    j = r.json() if r.ok else {}
    results = j.get("results", [])
    ev.check("5.2", "The query is embedded by the real vision service", "200, matchType embedding", r.status_code == 200
             and j.get("matchType") == "embedding", f"HTTP {r.status_code} matchType={j.get('matchType')}")
    top = results[0] if results else {}
    ev.check("5.3", "The red pillar ranks first", "first result labelled 'red pillar' (auto or manual)",
             (top.get("label") or "").lower() == "red pillar", f"{[(x.get('label'), x.get('matchedBy'), x.get('source')) for x in results[:6]]}")
    detected = next((x for x in results if _auto(x) and x["label"].lower() == "red pillar"), None)
    pos = _pos(detected or {})
    ev.check("5.4", "The detected object's position is its canonical position on floor 0", "(1.0, 1.0) within 0.01 m; floor 0",
             detected is not None and pos is not None and math.dist(pos[:2], [1.0, 1.0]) < 0.01 and detected.get("floorId") in (None, world.floor0),
             f"position={pos} floor={detected.get('floorId') if detected else None} spatialStatus={detected.get('spatialStatus') if detected else None}")


def test_search_is_scoped_to_the_venue(floor0_v1, world, cfg, ev):
    """A same-named POI in another venue of the same organization never appears; other tenants cannot search at all."""
    if not cfg.vision:
        pytest.skip("BLOCKED: no vision service on this stack (ACC_VISION_URL)")
    a2 = world.u("admin")
    floor = a2.ok("POST", f"/api/v1/venues/{world.venue_a2}/floors", json={"level": 0, "name": "A2 ground"})["id"]
    other = a2.ok("POST", f"/api/v1/venues/{world.venue_a2}/pois", json={"floorId": floor, "label": "Red pillar", "category": "landmark",
                                                                         "tags": ["pillar"], "description": "another venue", "x": 1.0, "y": 1.0, "z": 0.0})
    results = _search(world.u("manager"), world.venue_a, "red pillar").json().get("results", [])
    ev.check("5.5", "Venue A's results never include venue A2's POI", f"no result with id {other['id']}",
             all(x.get("poiId", x.get("id")) != other["id"] for x in results), f"{len(results)} results")
    for who, user in (("org B manager", world.u("manager_b")), ("operator of venue A2", world.u("operator_a2"))):
        r = _search(user, world.venue_a, "red pillar")
        ev.check(f"5.6.{who}", f"{who} searches venue A", "404", r.status_code == 404, f"HTTP {r.status_code}")


def test_a_query_with_no_match_returns_an_empty_result(floor0_v1, world, cfg, ev):
    """Nothing in the venue is a submarine periscope: 200 with no results, not the nearest unrelated object."""
    if not cfg.vision:
        pytest.skip("BLOCKED: no vision service on this stack (ACC_VISION_URL)")
    # Relevance is judged only with >= 8 distinct POI texts in scope (chaya.search.relevance-min-pois; docs/search.md):
    # below that a search is plain top-k and says UNFILTERED. A venue has more than a few pillars; so does this one now.
    mgr = world.u("manager")
    existing = {p["label"] for p in mgr.ok("GET", f"/api/v1/venues/{world.venue_a}/pois") if p.get("floorId") == world.floor0}
    for label, cat, x, y in (("Reception desk", "service", 2.0, 2.0), ("Accessible restroom", "restroom", 3.0, 3.6),
                             ("Cafe", "food", 2.5, 1.5), ("Emergency exit", "exit", 0.4, 3.6), ("Information point", "service", 3.0, 0.6),
                             ("Cloakroom", "service", 1.8, 3.4)):
        if label not in existing:
            mgr.ok("POST", f"/api/v1/venues/{world.venue_a}/pois", json={"floorId": world.floor0, "label": label, "category": cat,
                                                                         "tags": [], "description": "", "x": x, "y": y, "z": 0.0})
    r = _search(mgr, world.venue_a, "submarine periscope")
    j = r.json() if r.ok else {}
    ev.check("5.7", "No relevant result in a venue large enough to judge", "200, relevance FILTERED, empty results (closest offered separately)",
             r.status_code == 200 and j.get("relevance") == "FILTERED" and j.get("results") == [],
             f"HTTP {r.status_code} relevance={j.get('relevance')} "
             f"{[(x.get('label'), x.get('matchedBy'), x.get('similarity'), x.get('relevanceMargin')) for x in j.get('results', [])]} "
             f"closest={[x.get('label') for x in j.get('closestMatches', [])]}", j)
