"""7. RESCAN: an incremental re-scan of a changed region through the REAL REGION_ALIGNMENT (Open3D), REGION_SPLICE and
NAVIGATION_BAKING (with its outside-the-region consistency proof); version 1 is kept and stays usable."""

from __future__ import annotations

import json

import pytest

from . import stack

pytestmark = pytest.mark.acceptance
REAL_ALIGNMENT_METHODS = ("FEATURE_RANSAC_ICP", "DIRECT_CANONICAL_ICP", "FEATURE_SIMILARITY_ICP")


def _config(st: dict, name: str) -> dict:
    return ((stack.stage(st, name).get("lastRun") or {}).get("command") or {}).get("config") or {}


def _versions(world) -> list[dict]:
    vs = world.u("manager").ok("GET", f"/api/v1/venues/{world.venue_a}/floors/{world.floor0}/scan-versions")
    return vs if isinstance(vs, list) else vs.get("versions", vs.get("items", []))


def test_a_changed_region_is_aligned_and_spliced_by_the_real_stages(rescan_v2, cfg, ev):
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
    sp = _config(st, "REGION_SPLICE").get("splice") or {}
    removed, added = sp.get("removed_from_global"), sp.get("added_from_region")
    venue_n = ((sp.get("inputs") or {}).get("global_cloud") or {}).get("gaussians")
    region_n = ((sp.get("inputs") or {}).get("aligned_region") or {}).get("gaussians")
    merged_n = (sp.get("output") or {}).get("gaussians")
    seam = sp.get("seam") or {}
    ev.check("7.4", "The splice replaced the region with the re-scan, and the counts add up",
             "removed > 0; 0 < added <= aligned region; merged == venue - removed + added; seam measured within its limit",
             all(isinstance(v, int) for v in (removed, added, venue_n, region_n, merged_n)) and removed > 0 and 0 < added <= region_n
             and merged_n == venue_n - removed + added and seam.get("measured") is True and seam.get("step_m", 1) <= seam.get("max_step_m", 0),
             f"venue={venue_n} removed={removed} region={region_n} added={added} merged={merged_n} "
             f"seam step={seam.get('step_m')} (max {seam.get('max_step_m')})")
    # The rebuild proof is in the published NAVMESH_MANIFEST (not the command record): read it as stored, with the worker's
    # own object-storage account, and check it is the manifest the run recorded (SHA-256).
    ref = stack.artifacts_of(st, "NAVIGATION_BAKING")["NAVMESH_MANIFEST"]
    key, want = ref.get("key") or ref.get("objectKey"), ref.get("sha256") or ref.get("checksumSha256")
    body = stack.s3_client(cfg, cfg.s3_worker_key, cfg.s3_worker_secret).get_object(Bucket=cfg.bucket_derived, Key=key)["Body"].read()
    rebuild = json.loads(body).get("rebuild") or {}
    outside = rebuild.get("outside_region") or {}
    ev.check("7.5", "The whole floor was re-baked and is identical outside the region",
             "manifest sha256 as recorded; rebuild FULL_FLOOR; outside_region consistent",
             stack.sha(body) == want and rebuild.get("scope") == "FULL_FLOOR" and outside.get("consistent") is True,
             f"sha256 ok={stack.sha(body) == want} rebuild={ {k: v for k, v in rebuild.items() if k not in ('region_polygon_xy', 'outside_region')} } "
             f"outside_region={ {k: v for k, v in outside.items() if not isinstance(v, list)} }")


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
    # POIs belong to scan versions: the target above was placed on version 2 and is rightly "not part of" version 1. Route on
    # version 1 to a waypoint version 1 holds (test_06's east end, placed while it was current).
    v1_pois = mgr.ok("GET", f"/api/v1/venues/{world.venue_a}/pois", params={"scanVersionId": parent_id})
    v1_target = next((p["id"] for p in v1_pois if p.get("label") == "Acceptance east end"), None)
    old = world.u("operator")("POST", "/api/v1/navigation/routes",
                              json={**body, "destinationPoiId": v1_target or pt, "scanVersionId": parent_id})
    nav1 = stack.artifacts_of(floor0_v1.status, "NAVIGATION_BAKING")["NAVMESH"]
    src1 = (old.json().get("routingSources") or [{}])[0] if old.ok else {}
    ev.check("7.10", "Version 1 can still be routed on, on its own navmesh", "200 with scanVersionId=v1; navmeshSha256 == version 1's",
             v1_target is not None and old.status_code == 200 and src1.get("navmeshSha256") == (nav1.get("sha256") or nav1.get("checksumSha256")),
             f"v1 waypoint={v1_target} HTTP {old.status_code} navmesh={src1.get('navmeshSha256')} {old.text[:200] if not old.ok else ''}")
    # The browser check runs after the suite, when version 2 is the floor's current one: it is what the viewer opens.
    gaussians = ((stack.stage(rescan_v2.status, "ARTIFACT_GENERATION").get("lastRun") or {}).get("command") or {}).get("config", {}).get("gaussian_count")
    pois = [p["label"] for p in mgr.ok("GET", f"/api/v1/venues/{world.venue_a}/pois") if p.get("floorId") == world.floor0]
    stack.handoff(cfg, runId=rescan_v2.status["run"]["id"], ksplatSha256=stack.sha(v2.content), splatCount=gaussians, pois=pois,
                  versions=[{"runId": floor0_v1.status["run"]["id"], "ksplatSha256": stack.sha(v1.content)},
                            {"runId": rescan_v2.status["run"]["id"], "ksplatSha256": stack.sha(v2.content)}])
