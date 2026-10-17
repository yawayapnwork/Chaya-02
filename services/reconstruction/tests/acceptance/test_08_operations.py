"""8. OPERATIONS: the persisted processing status, failed-worker detection, retry, and who may see the operations dashboard."""

from __future__ import annotations

import pytest

from .stack import Http

pytestmark = pytest.mark.acceptance


def test_processing_status_is_persisted_and_honest_about_progress(floor0_v1, world, ev):
    op = world.u("operator")
    st = op.ok("GET", f"/api/v1/venues/{world.venue_a}/captures/{floor0_v1.capture}/processing")
    again = op.ok("GET", f"/api/v1/venues/{world.venue_a}/captures/{floor0_v1.capture}/processing")
    ev.check("8.1", "The snapshot is the persisted state: the same revision when nothing changed", "revision equal across reads",
             st.get("revision") is not None and st.get("revision") == again.get("revision"), f"revision={st.get('revision')}/{again.get('revision')}")
    p = st.get("progress") or {}
    ev.check("8.2", "Progress is counted from completed stages, never estimated", "basis COMPLETED_STAGES; succeeded == total",
             p.get("basis") == "COMPLETED_STAGES" and p.get("stagesSucceeded") == p.get("stagesTotal") == len(st["run"]["stages"]),
             f"{p}")
    jobs = op.ok("GET", f"/api/v1/venues/{world.venue_a}/ops/jobs")
    items = jobs if isinstance(jobs, list) else jobs.get("jobs", jobs.get("items", []))
    ev.check("8.3", "The operations dashboard lists the venue's jobs", ">= 12 jobs", len(items) >= 12, f"{len(items)} jobs")


def test_failed_workers_and_failed_stages_are_reported(floor1_v1, failed_scan, world, ev):
    failures = world.u("operator").ok("GET", f"/api/v1/venues/{world.venue_a}/ops/failures")
    items = failures if isinstance(failures, list) else failures.get("failures", failures.get("items", []))
    text = str(items)
    ev.check("8.4", "The lost worker's job is reported", "a WORKER_LOST failure", "WORKER_LOST" in text, f"{len(items)} failures: {text[:400]}")
    code = next((s["lastRun"]["errorCode"] for s in failed_scan.status["run"]["stages"] if s["state"] == "FAILED"), None)
    ev.check("8.5", "The real stage failure is reported with its code", f"a {code} failure", bool(code) and code in text, f"code={code}")


def test_a_retry_re_runs_only_the_failed_stage_and_is_bounded(failed_scan, world, stand_ins, ev):
    """The failed scan's labels are still inconsistent, so its retry fails again the same way: retries are accounted for."""
    from . import stack
    op = world.u("operator")
    base = f"/api/v1/venues/{world.venue_a}/captures/{failed_scan.capture}"
    before = op.ok("GET", base + "/processing")["run"]
    failed = next(s for s in before["stages"] if s["state"] == "FAILED")
    r = op("POST", base + "/processing/retry")
    ev.check("8.6", "The operator may retry a retryable failure", "202", r.status_code == 202, f"HTTP {r.status_code} {r.text[:200]}")
    d = stack.drive(op, stand_ins, world.venue_a, failed_scan.capture, failed_scan.plan, None)
    after = d.status["run"]
    again = stack.stage(d.status, failed["stage"])
    ev.check("8.7", "Only the failed stage ran again, and failed the same way", f"{failed['stage']} attempt 2, same code; earlier stages attempt 1",
             again.get("attempts") == 2 and (again.get("lastRun") or {}).get("errorCode") == (failed.get("lastRun") or {}).get("errorCode")
             and all(s.get("attempts") == 1 for s in after["stages"][: [x["stage"] for x in after["stages"]].index(failed["stage"])]),
             f"attempts={again.get('attempts')} code={(again.get('lastRun') or {}).get('errorCode')}")
    ev.check("8.8", "Retries are counted down", "retriesRemaining decreased by 1",
             before.get("retriesRemaining") is not None and after.get("retriesRemaining") == before["retriesRemaining"] - 1,
             f"{before.get('retriesRemaining')} -> {after.get('retriesRemaining')}")


@pytest.mark.parametrize("who,section,expected", [
    ("viewer", "overview", 200), ("viewer", "jobs", 403), ("viewer", "failures", 403),
    ("operator", "jobs", 200), ("operator", "failures", 200), ("operator", "search-analytics", 403),
    ("manager", "search-analytics", 200), ("manager", "audit", 200),
    ("manager_b", "overview", 404), ("operator_a2", "overview", 404),
])
def test_dashboard_access_is_restricted_by_role_and_venue(floor0_v1, world, who, section, expected, ev):
    r = world.u(who)("GET", f"/api/v1/venues/{world.venue_a}/ops/{section}")
    ev.check(f"8.9.{who}.{section}", f"{who} reads ops/{section} of venue A", str(expected), r.status_code == expected,
             f"HTTP {r.status_code} {r.text[:160] if r.status_code != 200 else ''}")


def test_dashboard_refuses_anonymous_and_service_callers(world, cfg, ev):
    anon = Http(cfg)("GET", f"/api/v1/venues/{world.venue_a}/ops/overview")
    svc = Http(cfg, world.kc, service=True)("GET", f"/api/v1/venues/{world.venue_a}/ops/overview")
    ev.check("8.10", "No token", "401", anon.status_code == 401, f"HTTP {anon.status_code}")
    ev.check("8.11", "A worker service token", "403", svc.status_code == 403, f"HTTP {svc.status_code}")
