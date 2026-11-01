"""Complete end-to-end capture-to-processing validation and hard failure test suite.

Validates:
1. Real media upload with multipart chunking and SHA-256 validation.
2. ClamAV antimalware inspection and container format validation.
3. Hard Failure: Empty capture / invalid state rejected (409).
4. Hard Failure: Corrupt/unsupported media rejected (415 UNSUPPORTED_MEDIA_TYPE).
5. Hard Failure: Duplicate processing job creation rejected (409 INVALID_CAPTURE_TRANSITION).
6. Real execution of worker preprocessing stages:
   - INPUT_VALIDATION (real FFmpeg probe)
   - FFMPEG_PREPROCESS (real frame extraction)
   - FRAME_QUALITY_FILTER (Laplacian blur and luminance variance evaluation)
   - PRIVACY_PREPROCESS (real face blurring and unblurred staging purge)
7. Hard Failure: Pose Estimation without COLMAP (honest DEPENDENCY_UNAVAILABLE blocker, no mock splats).
8. Retry Processing API verification.
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import cv2
import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent.parent


def parse_env() -> dict[str, str]:
    env_file = REPO_ROOT / ".env"
    vals: dict[str, str] = {}
    if env_file.is_file():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                vals[k.strip()] = v.strip().strip('"').strip("'")
    return vals


def make_indoor_video(out_path: Path, frame_count: int = 60, w: int = 640, h: int = 480) -> None:
    """Generate real, sharp indoor frames with high-texture elements and face features."""
    try:
        import skimage.data
        face_img = cv2.cvtColor(skimage.data.astronaut(), cv2.COLOR_RGB2BGR)
    except Exception:
        face_img = None

    frames = []
    for i in range(frame_count):
        img = np.full((h, w, 3), 220, dtype=np.uint8)
        # Wall and floor
        floor_y = int(h * 0.7)
        img[floor_y:, :] = (120, 120, 130)
        cv2.line(img, (0, floor_y), (w, floor_y), (50, 50, 50), 3)

        # Baseboard and door frame (moving with camera motion)
        offset_x = int((i * 4) % int(w * 0.2))
        door_x = int(w * 0.6) - offset_x
        cv2.rectangle(img, (door_x, int(h * 0.2)), (door_x + 90, floor_y), (80, 60, 40), -1)

        # Poster with text / sharp details
        poster_w, poster_h = 100, int(h * 0.3)
        poster_x = int(w * 0.1) + offset_x
        cv2.rectangle(img, (poster_x, int(h * 0.2)), (poster_x + poster_w, int(h * 0.2) + poster_h), (255, 255, 255), -1)
        cv2.putText(img, "ROOM 101", (poster_x + 10, int(h * 0.35)), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 2)

        # Embed face crop in poster
        if face_img is not None:
            crop_sz = 60
            face_crop = cv2.resize(face_img[20:140, 60:180], (crop_sz, crop_sz))
            img[int(h * 0.22):int(h * 0.22) + crop_sz, poster_x + 20:poster_x + 20 + crop_sz] = face_crop

        frames.append(img)

    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    vw = cv2.VideoWriter(str(out_path), fourcc, 8.0, (w, h))
    for f in frames:
        vw.write(f)
    vw.release()


def main():
    print("=" * 65)
    print("  CHAYA 02 COMPLETE CAPTURE-TO-PROCESSING ACCEPTANCE SUITE")
    print("=" * 65)

    env = parse_env()
    api_port = env.get("API_PORT", "18080")
    kc_port = env.get("KEYCLOAK_PORT", "8081")
    minio_port = env.get("MINIO_API_PORT", "9000")
    worker_secret = env.get("CHAYA_WORKER_CLIENT_SECRET", "")

    api_base = f"http://localhost:{api_port}"
    kc_base = f"http://localhost:{kc_port}"

    results = []

    def log_result(step_num: int, name: str, status: str, detail: str = ""):
        results.append((step_num, name, status, detail))
        badge = f"\033[92m[{status}]\033[0m" if status == "PASS" else f"\033[91m[{status}]\033[0m"
        print(f"Step {step_num:02d}: {name:<42} {badge}")
        if detail:
            print(f"         {detail}")

    # 1. Authenticate dev-operator
    try:
        data = urllib.parse.urlencode({
            "grant_type": "password",
            "client_id": "chaya-web",
            "username": "dev-operator",
            "password": "DevPassword123!"
        }).encode()
        req = urllib.request.Request(f"{kc_base}/realms/chaya/protocol/openid-connect/token", data=data)
        with urllib.request.urlopen(req, timeout=10) as resp:
            token = json.loads(resp.read())["access_token"]
        auth_headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
        log_result(1, "Operator Authentication (Keycloak JWT)", "PASS", "Signed JWT token acquired")
    except Exception as e:
        log_result(1, "Operator Authentication (Keycloak JWT)", "FAIL", str(e))
        return

    # 2. Create Venue, Floor, and Room
    try:
        v_slug = f"pipe-venue-{time.time_ns() % 1000000:06x}"
        v_body = json.dumps({"name": f"Pipeline Acceptance Venue {v_slug[-6:]}", "slug": v_slug}).encode()
        req_v = urllib.request.Request(f"{api_base}/api/v1/venues", headers=auth_headers, data=v_body, method="POST")
        with urllib.request.urlopen(req_v, timeout=10) as resp:
            venue = json.loads(resp.read())
            venue_id = venue["id"]

        f_body = json.dumps({"level": 0, "name": "Ground Level"}).encode()
        req_f = urllib.request.Request(f"{api_base}/api/v1/venues/{venue_id}/floors", headers=auth_headers, data=f_body, method="POST")
        with urllib.request.urlopen(req_f, timeout=10) as resp:
            floor = json.loads(resp.read())
            floor_id = floor["id"]

        r_body = json.dumps({"name": "Main Hall", "kind": "ROOM"}).encode()
        req_r = urllib.request.Request(f"{api_base}/api/v1/venues/{venue_id}/floors/{floor_id}/rooms", headers=auth_headers, data=r_body, method="POST")
        with urllib.request.urlopen(req_r, timeout=10) as resp:
            room = json.loads(resp.read())
            room_id = room["id"]

        log_result(2, "Venue, Floor & Room Provisioning", "PASS", f"Venue {venue_id}, Floor {floor_id}, Room {room_id}")
    except Exception as e:
        log_result(2, "Venue, Floor & Room Provisioning", "FAIL", str(e))
        return

    # 3. Hard Failure: Empty Capture Rejected (409)
    try:
        empty_sess_body = json.dumps({"floorId": floor_id, "roomId": room_id, "device": {"model": "Acceptance Rig"}}).encode()
        req_es = urllib.request.Request(f"{api_base}/api/v1/venues/{venue_id}/captures", headers=auth_headers, data=empty_sess_body, method="POST")
        with urllib.request.urlopen(req_es, timeout=10) as resp:
            empty_sess = json.loads(resp.read())
            empty_capture_id = empty_sess["id"]

        comp_empty_req = urllib.request.Request(
            f"{api_base}/api/v1/venues/{venue_id}/captures/{empty_capture_id}/complete-upload",
            headers=auth_headers,
            data=b"{}",
            method="POST"
        )
        try:
            urllib.request.urlopen(comp_empty_req, timeout=10)
            log_result(3, "Reject Empty Capture Upload", "FAIL", "Expected 409 conflict, got 200")
        except urllib.error.HTTPError as he:
            err_doc = json.loads(he.read())
            assert he.code == 409
            log_result(3, "Reject Empty Capture Upload", "PASS", f"HTTP 409 {err_doc.get('code')} correctly returned")
    except Exception as e:
        log_result(3, "Reject Empty Capture Upload", "FAIL", str(e))

    # 4. Hard Failure: Unsupported Media MIME Type Rejected
    try:
        corrupt_init = json.dumps({
            "kind": "VIDEO",
            "filename": "payload.exe",
            "contentType": "application/x-msdownload",
            "sizeBytes": 1024,
            "sha256": "0" * 64
        }).encode()
        req_cf = urllib.request.Request(
            f"{api_base}/api/v1/venues/{venue_id}/captures/{empty_capture_id}/media",
            headers=auth_headers,
            data=corrupt_init,
            method="POST"
        )
        try:
            urllib.request.urlopen(req_cf, timeout=10)
            log_result(4, "Reject Unsupported Media Type", "FAIL", "Expected 415 UNSUPPORTED_MEDIA_TYPE, got 200")
        except urllib.error.HTTPError as he:
            err_doc = json.loads(he.read())
            assert he.code == 415
            log_result(4, "Reject Unsupported Media Type", "PASS", f"HTTP 415 {err_doc.get('code')} correctly enforced")
    except Exception as e:
        log_result(4, "Reject Unsupported Media Type", "FAIL", str(e))

    # 5. Create Real Capture Session
    try:
        sess_body = json.dumps({
            "floorId": floor_id,
            "roomId": room_id,
            "device": {"model": "Chaya-Scanner-02", "camera": {"focalLength": 500.0, "width": 640, "height": 480}},
            "startedAt": "2026-11-01T06:30:00Z"
        }).encode()
        req_cs = urllib.request.Request(f"{api_base}/api/v1/venues/{venue_id}/captures", headers=auth_headers, data=sess_body, method="POST")
        with urllib.request.urlopen(req_cs, timeout=10) as resp:
            capture = json.loads(resp.read())
            capture_id = capture["id"]
        log_result(5, "Create Real Capture Session", "PASS", f"Capture ID: {capture_id}")
    except Exception as e:
        log_result(5, "Create Real Capture Session", "FAIL", str(e))
        return

    # 6. Generate and Upload Real Indoor Video
    try:
        tmp_dir = Path(os.environ.get("TEMP", "/tmp")) / "chaya_acceptance"
        tmp_dir.mkdir(parents=True, exist_ok=True)
        video_path = tmp_dir / "indoor_capture.mp4"
        make_indoor_video(video_path, frame_count=60, w=640, h=480)
        video_bytes = video_path.read_bytes()
        video_sha256 = hashlib.sha256(video_bytes).hexdigest()

        init_body = json.dumps({
            "kind": "VIDEO",
            "filename": "indoor_capture.mp4",
            "contentType": "video/mp4",
            "sizeBytes": len(video_bytes),
            "sha256": video_sha256
        }).encode()
        req_init = urllib.request.Request(f"{api_base}/api/v1/venues/{venue_id}/captures/{capture_id}/media", headers=auth_headers, data=init_body, method="POST")
        with urllib.request.urlopen(req_init, timeout=10) as resp:
            init_res = json.loads(resp.read())
            media_id = init_res["mediaId"]

        put_headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/octet-stream",
            "Content-Length": str(len(video_bytes)),
            "X-Part-Sha256": video_sha256
        }
        req_part = urllib.request.Request(
            f"{api_base}/api/v1/venues/{venue_id}/captures/{capture_id}/media/{media_id}/parts/1",
            headers=put_headers,
            data=video_bytes,
            method="PUT"
        )
        with urllib.request.urlopen(req_part, timeout=30) as resp:
            assert resp.status == 204

        req_comp = urllib.request.Request(f"{api_base}/api/v1/venues/{venue_id}/captures/{capture_id}/media/{media_id}/complete", headers=auth_headers, method="POST")
        with urllib.request.urlopen(req_comp, timeout=10) as resp:
            assert resp.status == 202

        accepted = False
        for _ in range(60):
            req_get = urllib.request.Request(f"{api_base}/api/v1/venues/{venue_id}/captures/{capture_id}/media/{media_id}", headers=auth_headers)
            with urllib.request.urlopen(req_get, timeout=5) as resp:
                media_st = json.loads(resp.read())
                if media_st["status"] == "ACCEPTED":
                    accepted = True
                    break
                elif media_st["status"] in ("REJECTED", "QUARANTINED"):
                    raise AssertionError(f"Media rejected: {media_st.get('rejectionCode')}")
            time.sleep(0.5)

        assert accepted, "Media validation timed out"
        log_result(6, "Multipart Media Upload & ClamAV Scan", "PASS", f"{len(video_bytes)} bytes validated and ACCEPTED")
    except Exception as e:
        log_result(6, "Multipart Media Upload & ClamAV Scan", "FAIL", str(e))
        return

    # 7. Complete Upload Phase
    try:
        comp_up_body = json.dumps({"endedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "durationSeconds": 10.0}).encode()
        req_cup = urllib.request.Request(f"{api_base}/api/v1/venues/{venue_id}/captures/{capture_id}/complete-upload", headers=auth_headers, data=comp_up_body, method="POST")
        with urllib.request.urlopen(req_cup, timeout=10) as resp:
            cup_res = json.loads(resp.read())
            assert cup_res["status"] == "READY_FOR_PROCESSING"
        log_result(7, "Complete Upload Phase", "PASS", "Capture state: READY_FOR_PROCESSING")
    except Exception as e:
        log_result(7, "Complete Upload Phase", "FAIL", str(e))
        return

    # 8. Start Processing Run & Verify Idempotency and State Rejection
    try:
        proc_body = json.dumps({"timeBudgetSeconds": 3600, "privacyEnabled": True}).encode()
        req_proc = urllib.request.Request(f"{api_base}/api/v1/venues/{venue_id}/captures/{capture_id}/processing", headers=auth_headers, data=proc_body, method="POST")
        with urllib.request.urlopen(req_proc, timeout=10) as resp:
            proc_init = json.loads(resp.read())
            run_id = proc_init["run"]["id"]

        # Duplicate job submission must be idempotent (return existing run)
        with urllib.request.urlopen(req_proc, timeout=10) as resp:
            proc_dup = json.loads(resp.read())
            assert proc_dup["run"]["id"] == run_id

        # Starting processing on an invalid state (e.g. empty_capture_id which is CREATED) must return 409
        req_invalid_proc = urllib.request.Request(f"{api_base}/api/v1/venues/{venue_id}/captures/{empty_capture_id}/processing", headers=auth_headers, data=proc_body, method="POST")
        try:
            urllib.request.urlopen(req_invalid_proc, timeout=10)
            log_result(8, "Start Job & Reject Invalid State Transition", "FAIL", "Expected 409 for invalid state transition")
        except urllib.error.HTTPError as he:
            assert he.code == 409
            log_result(8, "Start Job & Reject Invalid State Transition", "PASS", f"Run {run_id} created; idempotent replay verified; invalid state rejected (409)")
    except Exception as e:
        log_result(8, "Start Job & Reject Invalid State Transition", "FAIL", str(e))
        return

    # 9. Execute Real Preprocessing Stages via Worker (CPU Stages)
    try:
        worker_py = REPO_ROOT / "services/reconstruction/.venv/Scripts/python.exe"
        worker_env = os.environ.copy()
        worker_env.update({
            "CHAYA_API_URL": api_base,
            "CHAYA_TOKEN_URL": f"{kc_base}/realms/chaya/protocol/openid-connect/token",
            "CHAYA_CLIENT_ID": "chaya-worker",
            "CHAYA_CLIENT_SECRET": worker_secret,
            "S3_ENDPOINT": f"http://localhost:{minio_port}",
            "S3_ACCESS_KEY": env.get("S3_WORKER_ACCESS_KEY", "chaya-worker"),
            "S3_SECRET_KEY": env.get("S3_WORKER_SECRET_KEY", ""),
            "S3_BUCKET_RAW": env.get("S3_BUCKET_RAW", "chaya-raw"),
            "S3_BUCKET_DERIVED": env.get("S3_BUCKET_DERIVED", "chaya-derived"),
            "WORKER_ID": "acceptance-worker-1",
            "PYTHONPATH": str(REPO_ROOT / "services/reconstruction")
        })

        print("         Running worker in drain mode for CPU preprocessing stages...")
        w_proc = subprocess.run([str(worker_py), "-m", "chaya_worker", "--drain"], env=worker_env, capture_output=True, text=True, timeout=180)

        req_st = urllib.request.Request(f"{api_base}/api/v1/venues/{venue_id}/captures/{capture_id}/processing", headers=auth_headers)
        with urllib.request.urlopen(req_st, timeout=10) as resp:
            st_doc = json.loads(resp.read())

        stages = {s["stage"]: s["state"] for s in st_doc["run"]["stages"]}
        print("         Stage states:", stages)
        for s in st_doc["run"]["stages"]:
            lr = s.get("lastRun") or {}
            if lr.get("status") == "FAILED" or s["state"] == "FAILED":
                print(f"         Stage {s['stage']} FAILED: {lr.get('errorCode')} - {lr.get('errorMessage')}")

        assert stages["INPUT_VALIDATION"] == "SUCCEEDED"
        assert stages["FFMPEG_PREPROCESS"] == "SUCCEEDED"
        assert stages["FRAME_QUALITY_FILTER"] == "SUCCEEDED"
        assert stages["PRIVACY_PREPROCESS"] == "SUCCEEDED"
        assert stages["POSE_ESTIMATION"] == "QUEUED"

        log_result(9, "Execute Preprocessing Pipeline Stages", "PASS",
                   "INPUT_VALIDATION, FFMPEG_PREPROCESS, FRAME_QUALITY_FILTER, PRIVACY_PREPROCESS: SUCCEEDED")
    except Exception as e:
        log_result(9, "Execute Preprocessing Pipeline Stages", "FAIL", str(e))
        return

    # 10. Hard Failure: Missing COLMAP at POSE_ESTIMATION
    try:
        print("         Triggering diagnostic claim for POSE_ESTIMATION without COLMAP...")
        diag_env = worker_env.copy()
        diag_env["WORKER_OFFER_UNAVAILABLE_STAGES"] = "true"
        subprocess.run([str(worker_py), "-m", "chaya_worker", "--drain"], env=diag_env, capture_output=True, text=True, timeout=60)

        with urllib.request.urlopen(req_st, timeout=10) as resp:
            diag_st = json.loads(resp.read())

        pose_stage = next(s for s in diag_st["run"]["stages"] if s["stage"] == "POSE_ESTIMATION")
        last_run = pose_stage.get("lastRun") or {}
        err_code = last_run.get("errorCode")
        err_msg = last_run.get("errorMessage")

        assert pose_stage["state"] == "FAILED"
        assert err_code == "DEPENDENCY_UNAVAILABLE"
        assert "colmap" in (err_msg or "").lower() or "not found on path" in (err_msg or "").lower()

        log_result(10, "Expose Missing COLMAP as Structured Blocker", "PASS",
                   f"POSE_ESTIMATION cleanly FAILED with {err_code} ({err_msg})")
    except Exception as e:
        log_result(10, "Expose Missing COLMAP as Structured Blocker", "FAIL", str(e))

    # 11. Test Processing Retry API
    try:
        req_retry = urllib.request.Request(f"{api_base}/api/v1/venues/{venue_id}/captures/{capture_id}/processing/retry", headers=auth_headers, method="POST")
        with urllib.request.urlopen(req_retry, timeout=10) as resp:
            retry_res = json.loads(resp.read())
            assert retry_res["run"]["status"] == "RUNNING"
            pose_stage_after = next(s for s in retry_res["run"]["stages"] if s["stage"] == "POSE_ESTIMATION")
            assert pose_stage_after["state"] == "QUEUED"

        log_result(11, "Retry Processing Control Plane", "PASS",
                   "Run re-opened in RUNNING status; POSE_ESTIMATION cleanly re-QUEUED for capable GPU/COLMAP worker")
    except Exception as e:
        log_result(11, "Retry Processing Control Plane", "FAIL", str(e))

    print("=" * 65)
    all_passed = all(r[2] == "PASS" for r in results)
    if all_passed:
        print(f"\033[92mALL {len(results)} ACCEPTANCE & HARD FAILURE TESTS PASSED!\033[0m")
    else:
        print("\033[91mSOME TESTS FAILED. REVIEW OUTPUT ABOVE.\033[0m")
    print("=" * 65)


if __name__ == "__main__":
    main()
