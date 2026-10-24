"""scripts/acceptance/real_venue.py, the real-venue acceptance harness, without a stack: the failure cases a real run hit
(VALIDATION_2026-10-19 D4: the token expired mid-run; a resume overwrote the report), and the rules that keep a run honest
(prerequisites BLOCK before upload, thresholds are pre-registered and an unmeasured one fails, the scale is checked against
measurements kept out of the calibration)."""

from __future__ import annotations

import importlib.util
import io
import json
import os
import urllib.error
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[4]
SPEC = importlib.util.spec_from_file_location("real_venue", REPO / "scripts" / "acceptance" / "real_venue.py")
rv = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rv)


# ---- D4: the token outlives no real run --------------------------------------------------------------------------------


class Server:
    """Answers 401 to any token but `valid`, 200 with {"ok": true} otherwise."""

    def __init__(self, valid: str) -> None:
        self.valid, self.seen = valid, []

    def __call__(self, req, timeout):
        token = req.get_header("Authorization").split()[-1]
        self.seen.append(token)
        if token != self.valid:
            raise urllib.error.HTTPError(req.full_url, 401, "expired", {}, io.BytesIO(b""))
        return io.BytesIO(b'{"ok": true}')


class Resp(io.BytesIO):
    status = 200


def _server(valid):
    s = Server(valid)

    def opener(req, timeout):
        body = s(req, timeout)
        r = Resp(body.getvalue())
        return r

    opener.server = s
    return opener


def test_an_expired_token_is_renewed_by_the_token_command_and_the_call_succeeds(tmp_path):
    token_file = tmp_path / "op.token"
    token_file.write_text("old", encoding="utf-8")

    class Ran:
        returncode, stdout, stderr = 0, "fresh\n", ""

    tokens = rv.TokenSource(token_file, "get-token --realm chaya", runner=lambda argv, **kw: Ran())
    api = rv.Api("http://api.test", tokens, opener=_server("fresh"))
    status, body, _ = api.call("GET", "/version")
    assert status == 200 and body == {"ok": True}
    assert tokens.renewals == 1 and token_file.read_text(encoding="utf-8") == "fresh", "the new token is kept for the next calls"


def test_a_token_file_rewritten_by_an_external_refresher_is_picked_up(tmp_path):
    token_file = tmp_path / "op.token"
    token_file.write_text("old", encoding="utf-8")
    tokens = rv.TokenSource(token_file)
    opener = _server("new")
    api = rv.Api("http://api.test", tokens, opener=opener)
    token_file.write_text("new", encoding="utf-8")
    os.utime(token_file, (1, 2_000_000_000))
    assert api.call("GET", "/version")[0] == 200
    assert opener.server.seen == ["new"] and tokens.renewals == 0


def test_a_token_that_stays_invalid_is_an_access_error_not_a_loop(tmp_path):
    token_file = tmp_path / "op.token"
    token_file.write_text("old", encoding="utf-8")
    api = rv.Api("http://api.test", rv.TokenSource(token_file), opener=_server("never"))
    with pytest.raises(rv.Failure) as e:
        api.call("GET", "/version")
    assert e.value.code == 2


# ---- fakes for main() -----------------------------------------------------------------------------------------------------


class FakeApi:
    def __init__(self, workers_stages=None, readable_ops=True):
        self.stages = rv.PLAN_STAGES if workers_stages is None else workers_stages
        self.readable_ops = readable_ops
        self.uploads = 0

    def call(self, method, path, body=None, **kw):
        if path == "/version":
            return 200, {"version": "test"}, b""
        if path.endswith("/floors"):
            return 200, [{"id": "f1"}], b""
        if "/ops/jobs" in path:
            if not self.readable_ops:
                raise rv.Failure(2, "GET ops/jobs -> 403")
            return 200, {"queue": {"workers": [{"workerId": "w", "live": True, "stages": self.stages}]}, "jobs": []}, b""
        if path.endswith("/media") and method == "POST":
            self.uploads += 1
        raise AssertionError(f"unexpected call {method} {path}")


def _inputs(tmp_path, *, measurements=None, consent=True, metadata=True, images=24):
    media = tmp_path / "capture"
    media.mkdir()
    for i in range(images):
        (media / f"f{i:02d}.jpg").write_bytes(b"jpeg %d" % i)
    files = {}
    ms = measurements if measurements is not None else [
        {"kind": "DISTANCE", "label": "door", "method": "TAPE", "unit": "m", "value": 0.91, "uncertainty": 0.003,
         "observations": [{"point": p, "media": f"f0{i}.jpg", "u": 1, "v": 1} for p in "AB" for i in (1, 2)]},
        {"kind": "DISTANCE", "label": "wall", "method": "TAPE", "unit": "m", "value": 4.20, "uncertainty": 0.005,
         "observations": [{"point": p, "media": f"f0{i}.jpg", "u": 1, "v": 1} for p in "AB" for i in (3, 4)]},
        {"kind": "DISTANCE", "label": "table", "role": "check", "method": "TAPE", "unit": "m", "value": 1.60, "uncertainty": 0.003,
         "observations": [{"point": p, "media": f"f0{i}.jpg", "u": 1, "v": 1} for p in "AB" for i in (5, 6)]},
    ]
    files["measurements"] = ms
    if metadata:
        files["metadata"] = {"venue_name": "Test hall", "room": "R1", "floor_label": "Ground", "operator": "op",
                             "device": {"model": "Pixel 8", "os": "Android 15", "app": "Camera 9"},
                             "capture": {"started_at": "2026-10-24T10:00:00Z", "ended_at": "2026-10-24T10:20:00Z",
                                         "settings": "4K30, locked exposure", "orientation": "landscape, gravity from IMU not recorded"},
                             "unavailable_sensors": ["depth", "imu"]}
    if consent:
        files["consent"] = {"authorized_by": "Facilities manager", "role": "venue owner", "date": "2026-10-20",
                            "scope": "capture of hall R1 for Chaya acceptance", "people_policy": "NO_PEOPLE_PRESENT",
                            "sensitive_material_reviewed": True}
    files["thresholds"] = {"min_registered_share": 0.75, "max_check_distance_relative_error": 0.03, "require_quality_gate": True}
    paths = {}
    for k, v in files.items():
        paths[k] = tmp_path / f"{k}.json"
        paths[k].write_text(json.dumps(v), encoding="utf-8")
    token = tmp_path / "op.token"
    token.write_text("t", encoding="utf-8")
    argv = ["--api", "http://api.test", "--token-file", str(token), "--venue", "v1", "--floor", "f1", "--media", str(media),
            "--measurements", str(paths["measurements"]), "--thresholds", str(paths["thresholds"]), "--out", str(tmp_path / "out")]
    if "metadata" in paths:
        argv += ["--capture-metadata", str(paths["metadata"])]
    if "consent" in paths:
        argv += ["--consent", str(paths["consent"])]
    return argv, paths


def _report(tmp_path):
    return json.loads((tmp_path / "out" / "report.json").read_text(encoding="utf-8"))


def test_a_complete_set_of_inputs_is_ready(tmp_path):
    argv, _ = _inputs(tmp_path)
    assert rv.main([*argv, "--check-prerequisites"], api=FakeApi()) == 0
    r = _report(tmp_path)
    assert r["result"] == "READY" and not r["prerequisites"]["blocked"]
    assert (tmp_path / "out" / "source-manifest.json").is_file()


def test_no_gpu_worker_blocks_the_run_before_any_upload(tmp_path):
    """Today's situation: only the CPU worker is live, so POSE_ESTIMATION and SPLAT_RECONSTRUCTION are offered by nobody."""
    argv, _ = _inputs(tmp_path)
    cpu = ["INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS", "ARTIFACT_GENERATION", "NAVIGATION_BAKING"]
    api = FakeApi(workers_stages=cpu)
    assert rv.main(argv, api=api) == 5
    r = _report(tmp_path)
    assert r["result"] == "BLOCKED" and api.uploads == 0
    assert any("POSE_ESTIMATION" in b and "SPLAT_RECONSTRUCTION" in b for b in r["prerequisites"]["blockers"])


@pytest.mark.parametrize(("change", "blocker"), [
    ({"consent": False}, "consent and authorization"),
    ({"metadata": False}, "capture metadata"),
    ({"measurements": [{"kind": "DISTANCE", "label": "only", "unit": "m", "value": 1.0, "uncertainty": 0.01,
                        "observations": [{"point": p, "media": "f01.jpg", "u": 1, "v": 1} for p in "AB"]}]}, "enough measurements"),
    ({"images": 5}, "capture media"),
])
def test_a_missing_prerequisite_is_blocked_and_named(tmp_path, change, blocker):
    argv, _ = _inputs(tmp_path, **change)
    assert rv.main(argv, api=FakeApi()) == 5
    assert any(b.startswith(blocker) for b in _report(tmp_path)["prerequisites"]["blockers"])


def test_measurements_need_an_uncertainty_and_two_media_per_point():
    names = {"a.jpg", "b.jpg"}
    ms = [{"kind": "DISTANCE", "label": "x", "value": 1, "observations": [{"point": "A", "media": "a.jpg"}, {"point": "B", "media": "a.jpg"}]}]
    checks = {c["check"]: c["status"] for c in rv.check_measurements(ms, names)}
    assert checks["measurement uncertainty"] == "FAIL" and checks["measured points marked in >= 2 media"] == "FAIL"


def test_unreadable_worker_status_is_unverified_not_passed():
    assert rv.check_workers(FakeApi(readable_ops=False), "v1")["status"] == "UNVERIFIED"


def test_no_privacy_needs_a_capture_without_people():
    consent = {"authorized_by": "a", "role": "r", "date": "d", "scope": "s", "people_policy": "CONSENT_OBTAINED",
               "sensitive_material_reviewed": True}
    assert rv.check_consent(consent, no_privacy=True)["status"] == "FAIL"
    assert rv.check_consent(consent, no_privacy=False)["status"] == "PASS"


# ---- D4: a resume continues the same report ----------------------------------------------------------------------------


def test_a_resume_keeps_the_report_sections_of_the_first_session(tmp_path):
    argv, _ = _inputs(tmp_path)
    assert rv.main([*argv, "--check-prerequisites"], api=FakeApi()) == 0
    path = tmp_path / "out" / "report.json"
    first = json.loads(path.read_text(encoding="utf-8"))
    first["media"] = [{"file": "f00.jpg", "status": "ACCEPTED"}]
    path.write_text(json.dumps(first), encoding="utf-8")
    assert rv.main([*argv, "--check-prerequisites", "--resume"], api=FakeApi()) == 0
    again = _report(tmp_path)
    assert again["media"] == [{"file": "f00.jpg", "status": "ACCEPTED"}], "the first session's sections survive"
    assert [s["resume"] for s in again["sessions"]] == [False, True]


def test_a_resume_refuses_changed_source_media(tmp_path):
    argv, _ = _inputs(tmp_path)
    assert rv.main([*argv, "--check-prerequisites"], api=FakeApi()) == 0
    (tmp_path / "capture" / "f03.jpg").write_bytes(b"edited")
    assert rv.main([*argv, "--check-prerequisites", "--resume"], api=FakeApi()) == 2
    assert "f03.jpg" in _report(tmp_path)["error"]


def test_thresholds_cannot_be_edited_after_the_run_started(tmp_path):
    argv, paths = _inputs(tmp_path)
    assert rv.main([*argv, "--check-prerequisites"], api=FakeApi()) == 0
    paths["thresholds"].write_text(json.dumps({"min_registered_share": 0.5}), encoding="utf-8")
    assert rv.main([*argv, "--check-prerequisites", "--resume"], api=FakeApi()) == 2
    assert "pre-registered" in _report(tmp_path)["error"]


# ---- thresholds, the independent scale check, the stage table -------------------------------------------------------------


def test_an_unmeasured_threshold_fails_and_measured_ones_compare_in_their_direction():
    results = {r["threshold"]: r["status"] for r in rv.evaluate_thresholds(
        {"min_registered_share": 0.75, "max_check_distance_relative_error": 0.03, "require_quality_gate": True, "min_pois": 5},
        {"min_registered_share": 0.7, "max_check_distance_relative_error": 0.01, "require_quality_gate": None, "min_pois": 5})}
    assert results == {"min_registered_share": "FAIL", "max_check_distance_relative_error": "PASS",
                       "require_quality_gate": "NOT_MEASURED", "min_pois": "PASS"}


def test_the_scale_is_checked_only_against_measurements_kept_out_of_the_calibration():
    measured = [{"label": "door", "role": "calibration", "measuredMetres": 0.91},
                {"label": "table", "role": "check", "measuredMetres": 1.60, "uncertaintyMetres": 0.003}]
    resolved = {"points": {"door": {"A": [0, 0, 0], "B": [0.5, 0, 0]}, "table": {"A": [0, 0, 0], "B": [0, 0.8, 0]}}}
    [table] = rv.check_distances(measured, resolved, scale=2.0)
    assert table["label"] == "table" and table["reconstructedMetres"] == 1.6 and table["relativeError"] == 0.0
    [unmeasured] = rv.check_distances(measured, {"points": {}}, scale=2.0)
    assert unmeasured["status"] == "NOT_MEASURED"
    body = rv.calibration_body([{"id": "m1", "label": "door", "status": "ACTIVE"}, {"id": "m2", "label": "table", "status": "ACTIVE"}],
                               resolved, {"table"})
    assert {p["measurementId"] for p in body["resolvedPoints"]} == {"m1"}, "the check measurement never calibrates"


def test_the_threshold_inputs_come_from_what_the_api_reports():
    run = {"stages": [
        {"stage": "POSE_ESTIMATION", "lastRun": {"command": {"config": {"registered": 18, "frames": 24}}}},
        {"stage": "SPLAT_RECONSTRUCTION", "lastRun": {"command": {"config": {"quality": {"passed": True, "psnr_gain_db": 4.2}}}}},
    ]}
    values = rv.measurements_for_thresholds(run, {"scaleRelativeSpread": 0.01}, [{"status": "MEASURED", "relativeError": 0.02}],
                                            {"pois": {"total": 7}, "reconstruction": {"coordinateFrame": {"canonical": True}}})
    assert values["min_registered_share"] == 0.75 and values["require_quality_gate"] is True and values["min_psnr_gain_db"] == 4.2
    assert values["max_check_distance_relative_error"] == 0.02 and values["require_canonical_frame"] is True
    # A run that stopped before training measured nothing about it.
    assert rv.measurements_for_thresholds({"stages": []}, None, [], {})["require_quality_gate"] is None


def test_the_stage_table_records_inputs_outputs_timing_and_errors():
    run = {"stages": [{"stage": "POSE_ESTIMATION", "state": "FAILED", "attempts": 2, "lastRun": {
        "attempt": 2, "workerId": "gpu-1", "startedAt": "2026-10-24T10:00:00Z", "finishedAt": "2026-10-24T10:00:35.5Z",
        "exitStatus": 1, "errorCode": "FEATURE_EXTRACTION_FAILED", "command": {"tools": {"colmap": "COLMAP 3.9.1"}, "config": {"frames": 24}},
        "inputs": [{"id": "i1", "kind": "FRAME_ARCHIVE_ANON", "stage": "PRIVACY_PREPROCESS", "sha256": "a" * 64}],
        "artifacts": []}}]}
    [row] = rv.stage_table(run)
    assert row["durationSeconds"] == 35.5 and row["errorCode"] == "FEATURE_EXTRACTION_FAILED" and row["attempt"] == 2
    assert row["inputs"][0]["kind"] == "FRAME_ARCHIVE_ANON" and row["tools"] == {"colmap": "COLMAP 3.9.1"}
    assert "FEATURE_EXTRACTION_FAILED" in rv.stage_markdown([row])


# ---- the templates another venue starts from ---------------------------------------------------------------------------

TEMPLATES = REPO / "scripts" / "acceptance" / "templates"


def _template(name):
    return json.loads((TEMPLATES / name).read_text(encoding="utf-8"))


def test_unfilled_templates_never_pass_as_evidence():
    assert rv.check_metadata(_template("capture-metadata.example.json"))["status"] == "FAIL"
    assert rv.check_consent(_template("consent.example.json"), no_privacy=False)["status"] == "FAIL"


def test_the_threshold_and_measurement_templates_are_valid_inputs():
    assert rv.check_thresholds(_template("thresholds.example.json"))["status"] == "PASS"
    ms = _template("measurements.example.json")
    names = {o["media"] for m in ms for o in m["observations"]}
    statuses = {c["check"]: c["status"] for c in rv.check_measurements(ms, names)}
    assert set(statuses.values()) == {"PASS"}, statuses


def test_an_unreachable_api_is_a_blocked_prerequisite_not_a_traceback(tmp_path):
    """Found by running the harness on 2026-10-24 with no stack up: a refused connection raised URLError past every handler,
    and report.json had no result."""
    def refused(req, timeout):
        raise urllib.error.URLError(ConnectionRefusedError(10061, "actively refused"))

    argv, _ = _inputs(tmp_path)
    api = rv.Api("http://localhost:1", "t", opener=refused)
    assert rv.main([*argv, "--check-prerequisites"], api=api) == 5
    r = _report(tmp_path)
    assert r["result"] == "BLOCKED" and any(b.startswith("API reachable") and "cannot be reached" in b
                                            for b in r["prerequisites"]["blockers"])
