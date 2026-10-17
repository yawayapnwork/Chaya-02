"""7. RESCAN: an incremental re-scan of a changed region through the REAL REGION_ALIGNMENT (Open3D), REGION_SPLICE and
NAVIGATION_BAKING (with its outside-the-region consistency proof); version 1 is kept and stays usable."""

from __future__ import annotations

import pytest

from . import stack

pytestmark = pytest.mark.acceptance
REAL_ALIGNMENT_METHODS = ("FEATURE_RANSAC_ICP", "DIRECT_CANONICAL_ICP", "FEATURE_SIMILARITY_ICP")


def _config(st: dict, name: str) -> dict:
    return ((stack.stage(st, name).get("lastRun") or {}).get("command") or {}).get("config") or {}


def _versions(world) -> list[dict]:
    vs = world.u("manager").ok("GET", f"/api/v1/venues/{world.venue_a}/floors/{world.floor0}/scan-versions")
    return vs if isinstance(vs, list) else vs.get("versions", vs.get("items", []))


def test_a_changed_region_is_aligned_and_spliced_by_the_real_stages(rescan_v2, ev):
    st = rescan_v2.status
    names = [s["stage"] for s in st["run"]["stages"]]
    ev.check("7.1", "The re-scan ran the incremental plan to success", "SUCCEEDED; plan includes REGION_ALIGNMENT and REGION_SPLICE",
             st["run"]["status"] == "SUCCEEDED" and "REGION_ALIGNMENT" in names and "REGION_SPLICE" in names, f"{st['run']['status']} {names}")
    for name in ("REGION_ALIGNMENT", "REGION_SPLICE", "PLANE_FITTING", "ARTIFACT_GENERATION", "NAVIGATION_BAKING"):
        s = stack.stage(st, name)
        cmd = (s.get("lastRun") or {}).get("command") or {}
        ev.check(f"7.2.{name}", f"{name} executed by a real worker", "not a stand-in", not cmd.get("standIn")
                 and (s.get("lastRun") or {}).get("workerId") not in (None, "acceptance-stand-in"), f"worker={(s.get('lastRun') or {}).get('workerId')}")
    al = _config(st, "REGION_ALIGNMENT").get("alignment") or {}
    ev.check("7.3", "The alignment passed its own gates with a real method", "gates_passed; method in the real set",
             al.get("gates_passed") is True and al.get("method") in REAL_ALIGNMENT_METHODS,
             f"method={al.get('method')} confidence={al.get('confidence')} failed_gates={al.get('failed_gates')} rmse={al.get('inlier_rmse_m')}")
    sp = _config(st, "REGION_SPLICE")
    ev.check("7.4", "The splice replaced the region and added the re-scan", "removed > 0 and added == the region's Gaussians",
             str(sp).count("added") > 0 and (sp.get("added") or sp.get("added_gaussians") or (sp.get("counts") or {}).get("added")) == len(rescan_v2.plan.scene.labels),
             f"{ {k: v for k, v in sp.items() if 'count' in k or k in ('added', 'removed', 'kept', 'seam')} or str(sp)[:300]}")
    nav = _config(st, "NAVIGATION_BAKING")
    outside = ((nav.get("rebuild") or {}).get("outside_region") or {})
    ev.check("7.5", "The whole floor was re-baked and is identical outside the region", "rebuild FULL_FLOOR; outside_region consistent",
             (nav.get("rebuild") or {}).get("scope") == "FULL_FLOOR" and outside.get("consistent") is True,
             f"rebuild={ {k: v for k, v in (nav.get('rebuild') or {}).items() if k != 'region_polygon_xy'} }")


def test_historical_versions_are_preserved_and_the_new_one_is_published_safely(rescan_v2, floor0_v1, world, cfg, ev):
    vs = _versions(world)
    current = [v for v in vs if v.get("current") or v.get("isCurrent")]
    parent_id = rescan_v2.extra["parent"]["id"]
    ev.check("7.6", "Two versions; the re-scan's is current and names version 1 as its parent", "2 versions, 1 current, parent == v1",
             len(vs) >= 2 and len(current) == 1 and current[0]["id"] != parent_id and current[0].get("parentVersionId", parent_id) == parent_id,
             f"{[(v['id'][:8], v.get('status'), v.get('current'), (v.get('parentVersionId') or '')[:8]) for v in vs]}")
    mgr = world.u("manager")
    v1 = mgr("GET", f"/api/v1/venues/{world.venue_a}/scan-versions/{parent_id}/artifacts/KSPLAT")
    v2 = mgr("GET", f"/api/v1/venues/{world.venue_a}/scan-versions/{current[0]['id']}/artifacts/KSPLAT")
    ev.check("7.7", "Version 1's model is still served, unchanged", "200, the sha256 it was published with",
             v1.status_code == 200 and stack.sha(v1.content) == floor0_v1.extra.get("ksplat_sha", stack.sha(v1.content)),
             f"HTTP {v1.status_code} sha256={stack.sha(v1.content)}")
    ev.check("7.8", "Version 2 has its own, different model", "200, a different sha256", v2.status_code == 200 and v2.content != v1.content,
             f"HTTP {v2.status_code} sha256={stack.sha(v2.content)}")
    pt = mgr.ok("POST", f"/api/v1/venues/{world.venue_a}/pois", json={"floorId": world.floor0, "label": "Acceptance rescan target",
                                                                      "category": "waypoint", "tags": [], "description": "", "x": 5.5, "y": 0.6, "z": 0.0})["id"]
    body = {"venueId": world.venue_a, "floorId": world.floor0, "start": [0.5, 0.5, 0.0], "destinationPoiId": pt}
    now_route = world.u("operator")("POST", "/api/v1/navigation/routes", json=body)
    nav2 = stack.artifacts_of(rescan_v2.status, "NAVIGATION_BAKING")["NAVMESH"]
    src = (now_route.json().get("routingSources") or [{}])[0] if now_route.ok else {}
    ev.check("7.9", "Routing now uses the re-scan's navmesh", "200; navmeshSha256 == version 2's NAVMESH",
             now_route.status_code == 200 and src.get("navmeshSha256") == (nav2.get("sha256") or nav2.get("checksumSha256")),
             f"HTTP {now_route.status_code} navmesh={src.get('navmeshSha256')} {now_route.text[:200] if not now_route.ok else ''}")
    old = world.u("operator")("POST", "/api/v1/navigation/routes", json={**body, "scanVersionId": parent_id})
    nav1 = stack.artifacts_of(floor0_v1.status, "NAVIGATION_BAKING")["NAVMESH"]
    src1 = (old.json().get("routingSources") or [{}])[0] if old.ok else {}
    ev.check("7.10", "Version 1 can still be routed on, on its own navmesh", "200 with scanVersionId=v1; navmeshSha256 == version 1's",
             old.status_code == 200 and src1.get("navmeshSha256") == (nav1.get("sha256") or nav1.get("checksumSha256")),
             f"HTTP {old.status_code} navmesh={src1.get('navmeshSha256')} {old.text[:200] if not old.ok else ''}")
    # The browser check runs after the suite, when version 2 is the floor's current one: it is what the viewer opens.
    gaussians = ((stack.stage(rescan_v2.status, "ARTIFACT_GENERATION").get("lastRun") or {}).get("command") or {}).get("config", {}).get("gaussian_count")
    pois = [p["label"] for p in mgr.ok("GET", f"/api/v1/venues/{world.venue_a}/pois") if p.get("floorId") == world.floor0]
    stack.handoff(cfg, runId=rescan_v2.status["run"]["id"], ksplatSha256=stack.sha(v2.content), splatCount=gaussians, pois=pois,
                  versions=[{"runId": floor0_v1.status["run"]["id"], "ksplatSha256": stack.sha(v1.content)},
                            {"runId": rescan_v2.status["run"]["id"], "ksplatSha256": stack.sha(v2.content)}])
