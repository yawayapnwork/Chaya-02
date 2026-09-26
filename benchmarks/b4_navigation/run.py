"""Benchmark 4: navigation (docs/BENCHMARKS.md).

Shortest (STANDARD) route vs accessible (STEP_FREE) route for a set of origin-destination pairs, through the REAL
routing endpoint (POST /api/v1/navigation/routes). Per pair and profile:

    distance             distanceMeters of the route
    transitions          floor transitions, by connector type (STAIRS / ELEVATOR)
    stairs avoided       STAIRS transitions + non-step-free edges the standard route uses that the accessible one does not
    clearance violations route edges whose measured min_clearance_m is below chaya.navigation.min-accessible-clearance-m

Route edges are recovered by matching consecutive waypoints to the profile's ACTIVE graph edges, and read from
navigation_edge (step_free, min_clearance_m) -- the same rows the router used.

Two modes:

    --venue <id> --pairs pairs.json   a real venue whose graphs NAVIGATION_BAKING produced (none exists yet: baking needs
                                      a reconstruction, i.e. a CUDA GPU, plus chaya-navmesh)
    --synthetic                       a SELF-TEST on a hand-made two-floor graph inserted through the same DRAFT -> ACTIVE
                                      path the pipeline uses. It validates this benchmark and the router's rules; it says
                                      nothing about real venues. Those graphs are SYNTHETIC (not baked from a navmesh), which
                                      the API refuses with NAVMESH_NOT_READY unless it was started with
                                      CHAYA_NAVIGATION_ACCEPTSYNTHETICGRAPHS=true -- a self-test setting, never production.

    python benchmarks/b4_navigation/run.py --env-file <stack env> --synthetic
"""

from __future__ import annotations

import argparse
import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmarks.common.results import Metric, Run, unavailable  # noqa: E402
from benchmarks.common.stack import Stack  # noqa: E402

MIN_CLEARANCE = 0.9  # chaya.navigation.min-accessible-clearance-m default (NavigationProperties)


class Nav:
    def __init__(self, stack: Stack, user: str, org: str, venue: str):
        self.s, self.user, self.org, self.venue = stack, user, org, venue

    def route(self, floor: str, start: list[float], dest_poi: str, profile: str) -> dict:
        r = self.s.call(self.user, "POST", "/api/v1/navigation/routes", json={
            "venueId": self.venue, "floorId": floor, "start": start, "destinationPoiId": dest_poi, "accessibility": profile})
        body = r.json()
        return {"status": r.status_code, "body": body}

    def edges_on(self, waypoints: list[dict], profile: str) -> list[dict]:
        """The ACTIVE-graph edges between consecutive waypoints on the same floor (matched by exact node position)."""
        out = []
        for a, b in zip(waypoints, waypoints[1:], strict=False):
            if a["floorId"] != b["floorId"]:
                continue
            row = self.s.psql(f"""
                SELECT e.step_free, e.min_clearance_m, e.length_m FROM navigation_edge e
                  JOIN navigation_graph g ON g.id = e.graph_id AND g.status = 'ACTIVE' AND g.profile = '{profile}'
                  JOIN navigation_node n1 ON n1.id = e.from_node_id JOIN navigation_node n2 ON n2.id = e.to_node_id
                 WHERE g.venue_id = '{self.venue}' AND g.floor_id = '{a['floorId']}'
                   AND ((n1.x = {a['x']} AND n1.y = {a['y']} AND n1.z = {a['z']} AND n2.x = {b['x']} AND n2.y = {b['y']} AND n2.z = {b['z']})
                     OR (e.bidirectional AND n2.x = {a['x']} AND n2.y = {a['y']} AND n2.z = {a['z']}
                         AND n1.x = {b['x']} AND n1.y = {b['y']} AND n1.z = {b['z']}))
                 ORDER BY e.length_m LIMIT 1""")
            if row:
                step_free, clearance, length = row.split("|")
                out.append({"step_free": step_free == "t", "min_clearance_m": float(clearance) if clearance else None,
                            "length_m": float(length)})
        return out


def summarise(nav: Nav, pairs: list[dict]) -> list[dict]:
    rows = []
    for p in pairs:
        for profile in ("STANDARD", "STEP_FREE"):
            r = nav.route(p["floor"], p["start"], p["poi"], profile)
            row = {"pair": p["name"], "profile": profile, "http": r["status"]}
            if r["status"] == 200:
                b = r["body"]
                edges = nav.edges_on(b["waypoints"], profile)
                row.update({
                    "distance_m": b["distanceMeters"], "duration_s": b["estimatedDurationSeconds"],
                    "transitions": [t["connectorType"] for t in b["floorTransitions"]],
                    "stairs_transitions": sum(1 for t in b["floorTransitions"] if t["connectorType"] == "STAIRS"),
                    "non_step_free_edges": sum(1 for e in edges if not e["step_free"]),
                    "clearance_violations": sum(1 for e in edges if e["min_clearance_m"] is not None and e["min_clearance_m"] < MIN_CLEARANCE),
                    "edges_matched": len(edges)})
            else:
                row["error"] = r["body"].get("code") or r["body"].get("detail")
            rows.append(row)
    return rows


def seed_synthetic(stack: Stack) -> tuple[Nav, list[dict]]:
    """Two floors (z = 0 and z = 4, canonical metres, +Z up; docs/coordinate-frames.md). Floor 0: a 10 m grid corridor
    loop, a two-step shortcut (not step-free) and a 0.7 m passage (step-free but too narrow). Stairs and an elevator
    connect to floor 1. Hand-made: a SELF-TEST only.

    Routing only runs on graphs and POIs in a floor's current calibrated coordinate frame, and across floors only when
    both are registered to one venue datum. Each floor therefore gets a SYNTHETIC identity frame with the
    VENUE_CONTROL_POINTS datum, on a placeholder SUCCEEDED run -- self-test scaffolding that makes the hand-made
    coordinates canonical by definition, not a calibration of anything."""
    s = stack
    s.ensure_test_client()
    tag = uuid.uuid4().hex[:8]
    org = s.create_organization(f"bench-b4-{tag}", "Benchmark B4 synthetic")
    admin, user = f"bench-b4-{tag}-admin", f"bench-b4-{tag}"
    s.create_user(admin, "admin", org, [])
    venue = s.call(admin, "POST", "/api/v1/venues", json={"slug": f"b4-{tag}", "name": "B4 synthetic"}).json()["id"]
    s.create_user(user, "venue-manager", org, [venue])
    floors = {lvl: s.call(user, "POST", f"/api/v1/venues/{venue}/floors", json={"level": lvl, "name": f"Level {lvl}"}).json()["id"]
              for lvl in (0, 1)}
    height = {0: 0.0, 1: 4.0}
    frame = {}
    for lvl, f in floors.items():
        session = s.psql(f"INSERT INTO capture_session (organization_id, venue_id, floor_id, operator_id) "
                         f"VALUES ('{org}', '{venue}', '{f}', 'bench-b4') RETURNING id")
        scan = s.psql(f"INSERT INTO scan (organization_id, venue_id, capture_session_id) VALUES ('{org}', '{venue}', '{session}') RETURNING id")
        run = s.psql(f"INSERT INTO pipeline_run (organization_id, venue_id, scan_id, capture_session_id, status, quality, stages, "
                     f"time_budget_seconds, deadline_at, finished_at, requested_by) VALUES ('{org}', '{venue}', '{scan}', '{session}', "
                     f"'SUCCEEDED', 'FINAL', '{{}}', 3600, now(), now(), 'bench-b4-synthetic') RETURNING id")
        s.psql(f"UPDATE pipeline_run SET reconstruction_frame_run_id = id WHERE id = '{run}'")
        frame[lvl] = s.psql(
            f"INSERT INTO coordinate_frame (organization_id, venue_id, floor_id, source_run_id, version, metric_status, gravity_status, "
            f"horizontal_datum, scale, rotation_w, rotation_x, rotation_y, rotation_z, translation_x, translation_y, translation_z, "
            f"scale_source, gravity_source, method, inputs, residuals, calibrated_by) VALUES ('{org}', '{venue}', '{f}', '{run}', 1, "
            f"'METRIC', 'ALIGNED', 'VENUE_CONTROL_POINTS', 1, 1, 0, 0, 0, 0, 0, 0, 'CONTROL_POINTS', 'CONTROL_POINTS', "
            f"'SYNTHETIC_BENCHMARK_SELF_TEST', '{{\"synthetic\": true}}', '{{}}', 'bench-b4') RETURNING id")
        s.psql(f"UPDATE floor SET current_coordinate_frame_id = '{frame[lvl]}' WHERE id = '{f}'")
    layout = {
        0: {"nodes": {"A": (0, 0), "B": (10, 0), "C": (20, 0), "D": (20, 10), "E": (10, 10), "S": (5, 5), "L": (25, 5)},
            "edges": [("A", "B", 2.0, True), ("B", "C", 2.0, True), ("C", "D", 2.0, True), ("D", "E", 2.0, True),
                      ("B", "E", 0.7, True),    # narrow passage: step-free, but below the accessible clearance
                      ("A", "E", 1.5, False),   # two steps: shortest, not step-free
                      ("A", "S", 2.0, True), ("C", "L", 2.0, True)]},
        1: {"nodes": {"S": (5, 5), "F": (10, 5), "G": (20, 5), "L": (25, 5)},  # (x, y) horizontal, canonical metres
            "edges": [("S", "F", 2.0, True), ("F", "G", 2.0, True), ("G", "L", 2.0, True)]}}
    for lvl, spec in layout.items():
        f = floors[lvl]
        for profile in ("STANDARD", "STEP_FREE"):
            g = s.psql(f"INSERT INTO navigation_graph (organization_id, venue_id, floor_id, profile, status, coordinate_frame_id, source) "
                       f"VALUES ('{org}', '{venue}', '{f}', '{profile}', 'DRAFT', '{frame[lvl]}', 'SYNTHETIC') RETURNING id")
            ids = {k: s.psql(f"INSERT INTO navigation_node (organization_id, venue_id, graph_id, floor_id, kind, x, y, z) "
                             f"VALUES ('{org}', '{venue}', '{g}', '{f}', 'WAYPOINT', {x}, {hy}, {height[lvl]}) RETURNING id")
                   for k, (x, hy) in spec["nodes"].items()}
            for a, b, clearance, step_free in spec["edges"]:
                if profile == "STEP_FREE" and not step_free:
                    continue  # the STEP_FREE graph is baked without them (navmesh.build_routing_graphs)
                (xa, ya), (xb, yb) = spec["nodes"][a], spec["nodes"][b]
                length = ((xa - xb) ** 2 + (ya - yb) ** 2) ** 0.5
                s.psql(f"INSERT INTO navigation_edge (organization_id, venue_id, graph_id, from_node_id, to_node_id, length_m, step_free, "
                       f"bidirectional, min_clearance_m) VALUES ('{org}', '{venue}', '{g}', '{ids[a]}', '{ids[b]}', {length}, "
                       f"{str(step_free).lower()}, true, {clearance})")
            s.psql(f"UPDATE navigation_graph SET status = 'ACTIVE' WHERE id = '{g}'")

    def poi(lvl: int, label: str, category: str, x: float, y: float) -> str:
        r = s.call(user, "POST", f"/api/v1/venues/{venue}/pois", json={"floorId": floors[lvl], "label": label, "category": category,
                                                                    "tags": [], "x": x, "y": y, "z": height[lvl]})
        r.raise_for_status()
        return r.json()["id"]
    poi(0, "Stairs (ground)", "stairs", 5, 5)
    poi(1, "Stairs (level 1)", "stairs", 5, 5)
    poi(0, "Elevator (ground)", "elevator", 25, 5)
    poi(1, "Elevator (level 1)", "elevator", 25, 5)
    room_e = poi(0, "Room E", "room", 10, 10)
    cafe = poi(1, "Cafe", "food", 20, 5)
    pairs = [{"name": "A -> Room E (same floor)", "floor": floors[0], "start": [0.0, 0.0, 0.0], "poi": room_e},
             {"name": "B -> Room E (same floor)", "floor": floors[0], "start": [10.0, 0.0, 0.0], "poi": room_e},
             {"name": "A -> Cafe (level 1)", "floor": floors[0], "start": [0.0, 0.0, 0.0], "poi": cafe}]
    return Nav(s, user, org, venue), pairs


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--env-file", required=True)
    ap.add_argument("--synthetic", action="store_true")
    ap.add_argument("--venue")
    ap.add_argument("--pairs", type=Path, help="JSON list of {name, floor, start: [x,y,z], poi} for --venue")
    ap.add_argument("--user", help="an existing user with access to --venue (its password must be registered in Stack)")
    a = ap.parse_args()
    stack = Stack(a.env_file)
    run = Run("b4_navigation", "Navigation: shortest (STANDARD) vs accessible (STEP_FREE) route")
    run.add(Metric("minimum accessible clearance", MIN_CLEARANCE, "m", "configuration", "all", "chaya.navigation.min-accessible-clearance-m"),
            Metric("walking speed standard / accessible", "1.3 / 1.0", "m/s", "configuration", "all", "chaya.navigation.*-walking-speed-mps"))
    if a.synthetic:
        nav, pairs = seed_synthetic(stack)
        cond_prefix = "SYNTHETIC self-test graph"
        run.add(Metric("graph", "hand-made 2-floor graph: 10 m corridor loop, 2-step shortcut, 0.7 m passage, stairs + elevator", "",
                       "assumption", cond_prefix, "benchmarks/b4_navigation/run.py seed_synthetic",
                       note="validates the benchmark and the router's rules only; not a venue measurement"))
    else:
        sys.exit("--venue mode needs a venue with baked navigation graphs; none exists yet (see docs/BENCHMARKS.md)")
    rows = summarise(nav, pairs)
    run.details["routes"] = rows
    for r in rows:
        c = f"{cond_prefix} | {r['pair']} | {r['profile']}"
        if r["http"] != 200:
            run.add(Metric("route found", "no", "", "measured", c, "POST /navigation/routes", note=str(r.get("error"))))
            continue
        run.add(Metric("distance", round(r["distance_m"], 2), "m", "measured", c, "POST /navigation/routes"),
                Metric("estimated duration", round(r["duration_s"], 1), "s", "estimate", c, "router",
                       note="distance / configured walking speed + configured transition time"),
                Metric("floor transitions", ", ".join(r["transitions"]) or "none", "", "measured", c, "floorTransitions"),
                Metric("stairs used (transitions + non-step-free edges)", r["stairs_transitions"] + r["non_step_free_edges"], "count",
                       "measured", c, "floorTransitions + navigation_edge.step_free"),
                Metric("clearance violations (edges < minimum)", r["clearance_violations"], "count", "measured", c, "navigation_edge.min_clearance_m"))
    by = {(r["pair"], r["profile"]): r for r in rows}
    for pair in {r["pair"] for r in rows}:
        std, acc = by.get((pair, "STANDARD")), by.get((pair, "STEP_FREE"))
        if std and acc and std["http"] == 200 and acc["http"] == 200:
            c = f"{cond_prefix} | {pair} | STEP_FREE vs STANDARD"
            run.add(Metric("extra distance for the accessible route", round(acc["distance_m"] - std["distance_m"], 2), "m", "measured", c, "difference"),
                    Metric("stairs avoided", (std["stairs_transitions"] + std["non_step_free_edges"])
                           - (acc["stairs_transitions"] + acc["non_step_free_edges"]), "count", "measured", c, "difference"),
                    Metric("clearance violations avoided", std["clearance_violations"] - acc["clearance_violations"], "count", "measured", c,
                           "difference"))
    why = ("no venue has navigation graphs: NAVIGATION_BAKING needs a reconstruction (SPLAT_RECONSTRUCTION, CUDA GPU), plane "
           "fitting (Open3D) and the chaya-navmesh Recast/Detour tool")
    cmd = "python benchmarks/b4_navigation/run.py --env-file <env> --venue <venue id> --pairs <pairs.json> --user <user>"
    for metric, unit in (("distance, standard vs accessible", "m"), ("floor transitions", "count"), ("stairs avoided", "count"),
                         ("clearance violations", "count")):
        run.add(unavailable(metric, unit, why, cmd, condition="real venue"))
    path = run.write()
    for m in run.metrics:
        print(f"  [{m.kind:13}] {m.condition:70} {m.metric}: {m.value} {m.unit}")
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
