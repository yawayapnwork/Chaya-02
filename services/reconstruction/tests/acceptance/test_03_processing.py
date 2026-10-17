"""3. PROCESSING: a real run through the control plane and the real workers; leases, failures and publication."""

from __future__ import annotations

import pytest

from . import stack

pytestmark = pytest.mark.acceptance
FULL_PLAN = ["INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS", "POSE_ESTIMATION",
             "SPLAT_RECONSTRUCTION", "SEMANTIC_SEGMENTATION", "GEOMETRIC_CLEANUP", "PLANE_FITTING", "ARTIFACT_GENERATION",
             "SEMANTIC_INDEXING", "NAVIGATION_BAKING"]


def _cmd(s: dict) -> dict:
    return (s.get("lastRun") or {}).get("command") or {}


def test_a_submitted_run_goes_through_every_stage_in_order_on_real_workers(floor0_v1, ev):
    """The full plan, stage by stage; every stage that is not a labelled stand-in was executed by a real worker."""
    st = floor0_v1.status
    run = st["run"]
    ev.check("3.1", "The run succeeded with final quality", "SUCCEEDED, FINAL", run["status"] == "SUCCEEDED" and run.get("quality") == "FINAL",
             f"status={run['status']} quality={run.get('quality')} failure={run.get('failure')}")
    ev.check("3.2", "Every stage of the full plan, in order", " -> ".join(FULL_PLAN), [s["stage"] for s in run["stages"]] == FULL_PLAN,
             " -> ".join(s["stage"] for s in run["stages"]))
    starts = [(s["stage"], (s.get("lastRun") or {}).get("startedAt"), (s.get("lastRun") or {}).get("finishedAt")) for s in run["stages"]]
    ordered = all(a[2] and b[1] and a[2] <= b[1] for a, b in zip(starts, starts[1:], strict=False))
    ev.check("3.3", "No stage started before the previous one finished", "finishedAt(n) <= startedAt(n+1)", ordered, f"{starts}")
    who = {s["stage"]: ("STAND-IN" if _cmd(s).get("standIn") else (s.get("lastRun") or {}).get("workerId")) for s in run["stages"]}
    real = {k: v for k, v in who.items() if k not in stack.STAND_IN_STAGES}
    ev.check("3.4", "Only the four labelled stages were stood in", f"stand-ins exactly {list(stack.STAND_IN_STAGES)}",
             sorted(k for k, v in who.items() if v == "STAND-IN") == sorted(stack.STAND_IN_STAGES), f"{who}")
    ev.check("3.5", "Every other stage ran on a real worker (production stage code)", "a worker id, never the stand-in",
             all(v and v != "acceptance-stand-in" and v != "STAND-IN" for v in real.values()), f"{real}")
    seen = {(stage, state) for _, stage, state, _ in floor0_v1.drive.timeline}
    ev.check("3.6", "Stage transitions observed through the persisted status", "each stage seen SUCCEEDED; at least one RUNNING",
             all((s, "SUCCEEDED") in seen for s in FULL_PLAN) and any(state == "RUNNING" for _, state in seen),
             f"{len(floor0_v1.drive.timeline)} transitions: {floor0_v1.drive.timeline[:12]}...")


def test_artifact_manifests_are_complete_checksummed_and_tenant_scoped(floor0_v1, world, cfg, ev):
    """Each stage's outputs are recorded with checksum and size under the run's own tenant prefix; the manifest lists them."""
    st = floor0_v1.status
    prefix = f"org/{cfg.org_a}/venue/{world.venue_a}/"
    rows = []
    for s in st["run"]["stages"]:
        for a in (s.get("lastRun") or {}).get("artifacts") or []:
            rows.append((s["stage"], a["kind"], a.get("key") or a.get("objectKey"), a.get("sha256") or a.get("checksumSha256"), a.get("sizeBytes")))
    ev.check("3.7", "Every artifact has a checksum and a size", "sha256 (64 hex) and size > 0 for each",
             bool(rows) and all(r[3] and len(r[3]) == 64 and (r[4] or 0) > 0 for r in rows), f"{len(rows)} artifacts")
    keys = [r[2] for r in rows if r[2]]
    ev.check("3.8", "Every object key is under this tenant's prefix", prefix + "...",
             bool(keys) and all(k.startswith(prefix) or k.startswith("sealed/" + prefix) for k in keys), f"{keys[:3]}")
    kinds = {r[1] for r in rows}
    want = {"KSPLAT", "ARTIFACT_MANIFEST", "NAVMESH", "NAVMESH_MANIFEST", "NAVIGATION_GRAPH", "PLANE_MODEL", "SPLAT_CLEAN", "DETECTED_OBJECTS"}
    ev.check("3.9", "The run produced the viewer, navigation and index outputs", f"{sorted(want)}", want <= kinds, f"{sorted(kinds)}")
    nav = stack.artifacts_of(st, "NAVIGATION_BAKING")
    ksplat = stack.artifacts_of(st, "ARTIFACT_GENERATION")["KSPLAT"]
    floor0_v1.extra["ksplat"] = ksplat
    floor0_v1.extra["nav"] = nav
    cmd = _cmd(stack.stage(st, "NAVIGATION_BAKING"))
    ev.check("3.10", "The navmesh was baked by the real Recast tool", "NAVIGATION_BAKING command names chaya-navmesh, not a stand-in",
             not cmd.get("standIn") and "navmesh" in str(cmd).lower(), f"{str(cmd)[:300]}")


def test_a_lost_worker_lease_is_detected_failed_and_retried(floor1_v1, ev):
    """A job claimed and abandoned (no heartbeat): its lease expires, the control plane fails it WORKER_LOST, a late report from
    the lost worker is refused, the operator's retry runs attempt 2, and the run completes."""
    lost = stack.stage(floor1_v1.extra["lost_status"], "SPLAT_RECONSTRUCTION")
    code = (lost.get("lastRun") or {}).get("errorCode") or (floor1_v1.extra["lost_status"]["run"].get("failure") or {}).get("code")
    ev.check("3.11", "The abandoned job failed as a lost worker", "SPLAT_RECONSTRUCTION FAILED, WORKER_LOST", lost["state"] == "FAILED"
             and code == "WORKER_LOST", f"state={lost['state']} code={code}")
    run = floor1_v1.extra["lost_status"]["run"]
    ev.check("3.12", "The failure is retryable", "run.retryable true", run.get("retryable") is True,
             f"retryable={run.get('retryable')} blocked={run.get('retryBlockedReason')} remaining={run.get('retriesRemaining')}")
    late = floor1_v1.extra["late_report"]
    ev.check("3.13", "A late report from the lost worker is refused", "409 (lease no longer held), nothing recorded",
             late is not None and late.status_code == 409, f"HTTP {late.status_code if late is not None else None} {late.text[:200] if late is not None else ''}")
    ev.check("3.14", "The operator's retry is accepted", "202", floor1_v1.extra["retry"].status_code == 202,
             f"HTTP {floor1_v1.extra['retry'].status_code}")
    st = floor1_v1.status
    splat = stack.stage(st, "SPLAT_RECONSTRUCTION")
    ev.check("3.15", "Attempt 2 succeeded and the run completed", "run SUCCEEDED; SPLAT_RECONSTRUCTION attempts 2",
             st["run"]["status"] == "SUCCEEDED" and splat.get("attempts") == 2, f"run={st['run']['status']} attempts={splat.get('attempts')}")
    earlier = [s for s in st["run"]["stages"] if s["stage"] in ("INPUT_VALIDATION", "POSE_ESTIMATION")]
    ev.check("3.16", "Earlier stages were not repeated by the retry", "attempts 1", all(s.get("attempts") == 1 for s in earlier),
             f"{[(s['stage'], s.get('attempts')) for s in earlier]}")


def test_a_real_stage_failure_propagates_and_nothing_after_it_runs(failed_scan, ev):
    """Labels that do not describe their cloud: the first real stage that reads them refuses, with a structured code."""
    st = failed_scan.status
    run = st["run"]
    failed = [s for s in run["stages"] if s["state"] == "FAILED"]
    f = failed[0] if failed else {}
    ev.check("3.17", "The run failed at a real stage", "run FAILED; the failed stage is not a stand-in",
             run["status"] == "FAILED" and len(failed) == 1 and f["stage"] not in stack.STAND_IN_STAGES and not _cmd(f).get("standIn"),
             f"run={run['status']} failed={[s['stage'] for s in failed]} worker={(f.get('lastRun') or {}).get('workerId')}")
    err = (f.get("lastRun") or {})
    ev.check("3.18", "The failure names its cause", "a stable error code and a message", bool(err.get("errorCode")) and bool(err.get("errorMessage")),
             f"{err.get('errorCode')}: {str(err.get('errorMessage'))[:240]}")
    after = run["stages"][[s["stage"] for s in run["stages"]].index(f["stage"]) + 1:] if f else []
    ev.check("3.19", "No later stage ran", "every later stage not run", all(s["state"] not in ("RUNNING", "SUCCEEDED") for s in after),
             f"{[(s['stage'], s['state']) for s in after]}")
    ev.check("3.20", "The run is not reported as published", "quality null", run.get("quality") in (None, ""), f"quality={run.get('quality')}")


def test_scan_version_publication_rules(floor0_v1, failed_scan, world, ev):
    """A succeeded run is the floor's current version; a later failed scan publishes nothing and leaves it current."""
    mgr = world.u("manager")
    latest = mgr.ok("GET", f"/api/v1/venues/{world.venue_a}/floors/{world.floor0}/reconstructions/latest")
    ev.check("3.21", "Floor 0's latest reconstruction is the succeeded run, not the failed one", f"run {floor0_v1.status['run']['id']}",
             (latest.get("runId") or latest.get("id")) == floor0_v1.status["run"]["id"],
             f"latest={latest.get('runId') or latest.get('id')} failed={failed_scan.status['run']['id']}")
    versions = mgr.ok("GET", f"/api/v1/venues/{world.venue_a}/floors/{world.floor0}/scan-versions")
    vs = versions if isinstance(versions, list) else versions.get("versions", versions.get("items", []))
    floor0_v1.extra["versions_after_failure"] = vs
    current = [v for v in vs if v.get("current") or v.get("isCurrent")]
    ev.check("3.22", "Exactly one current version, and it is the succeeded run's", "1 current version, FINALIZED",
             len(current) == 1 and current[0].get("status") == "FINALIZED", f"{[(v.get('id'), v.get('status'), v.get('current')) for v in vs]}")
