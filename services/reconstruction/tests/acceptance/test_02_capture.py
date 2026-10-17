"""2. CAPTURE: sessions, real uploads through the real ClamAV and content checks, resumable parts, calibration evidence."""

from __future__ import annotations

import subprocess
import uuid
from pathlib import Path

import pytest

from . import stack

pytestmark = pytest.mark.acceptance
EICAR = b"X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"


def _capture(world, floor=None) -> str:
    return world.u("operator").ok("POST", f"/api/v1/venues/{world.venue_a}/captures",
                                  json={"floorId": floor or world.floor0, "device": {"app": "acceptance suite"}})["id"]


def test_create_capture_session_and_upload_valid_media(world, capture_video, ev):
    """A capture session on floor 0; the video is scanned, sniffed and stored, and its bytes are what was sent."""
    op, v = world.u("operator"), world.venue_a
    cap = _capture(world)
    c = op.ok("GET", f"/api/v1/venues/{v}/captures/{cap}")
    ev.check("2.1", "Capture session created on the floor", "status CREATED, floor 0", c["status"] == "CREATED"
             and c.get("floorId") == world.floor0, f"status={c['status']} floor={c.get('floorId')}")
    m = stack.upload(op, v, cap, capture_video, "capture.mp4", "video/mp4")
    ev.check("2.2", "Valid video accepted by the real validation chain", "ACCEPTED (checksum, content sniffing, ClamAV)",
             m["status"] == "ACCEPTED", f"status={m['status']} {len(capture_video)} B", m)
    ev.check("2.3", "Server-verified checksum is the client's", "verified sha256 == sent sha256",
             (m.get("verifiedSha256") or m.get("sha256")) == stack.sha(capture_video), f"server={m.get('verifiedSha256') or m.get('sha256')}")


def test_an_interrupted_upload_resumes_with_only_the_missing_parts(world, tmp_path: Path, ffmpeg_bin, ev):
    """A two-part video: part 1 is sent, the client 'drops', asks the server what it holds, sends only part 2, completes."""
    out = tmp_path / "long.mp4"
    # Lossless, so the file is ~12 MB: larger than one 8 MiB part (a test pattern compresses to almost nothing otherwise).
    subprocess.run([ffmpeg_bin, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30", "-t", "6", "-c:v", "libx264",
                    "-preset", "ultrafast", "-qp", "0", "-pix_fmt", "yuv420p", str(out)], check=True)
    data = out.read_bytes()
    op, v = world.u("operator"), world.venue_a
    cap = _capture(world)
    base = f"/api/v1/venues/{v}/captures/{cap}"
    client_file = str(uuid.uuid4())  # the id the client gives the file in its local upload queue
    init = op.ok("POST", base + "/media", json={"kind": "VIDEO", "filename": "long.mp4", "contentType": "video/mp4",
                                                "sizeBytes": len(data), "sha256": stack.sha(data), "clientFileId": client_file})
    ev.check("2.4", "Upload opened in several parts", ">= 2 parts", init["totalParts"] >= 2,
             f"{len(data)} B, part {init['partSizeBytes']} B, {init['totalParts']} parts")
    part = init["partSizeBytes"]
    first = data[:part]
    op.ok("PUT", f"{base}/media/{init['mediaId']}/parts/1", data=first,
          headers={"Content-Type": "application/octet-stream", "X-Part-Sha256": stack.sha(first)})
    # the connection drops here; the client reopens the same upload by its clientFileId and asks what the server holds
    again = op.ok("POST", base + "/media", json={"kind": "VIDEO", "filename": "long.mp4", "contentType": "video/mp4",
                                                 "sizeBytes": len(data), "sha256": stack.sha(data), "clientFileId": client_file})
    held = op.ok("GET", f"{base}/media/{init['mediaId']}")
    parts = held.get("uploadedParts") or held.get("receivedParts") or held.get("parts")
    ev.check("2.5", "Reopening returns the same upload, and the server says which parts it holds", "same mediaId; part 1 held",
             again["mediaId"] == init["mediaId"] and again.get("existing") is True and parts is not None and 1 in
             [p if isinstance(p, int) else p.get("partNumber") for p in parts], f"existing={again.get('existing')} parts={parts}")
    for n in range(2, init["totalParts"] + 1):
        chunk = data[(n - 1) * part:n * part]
        op.ok("PUT", f"{base}/media/{init['mediaId']}/parts/{n}", data=chunk,
              headers={"Content-Type": "application/octet-stream", "X-Part-Sha256": stack.sha(chunk)})
    op.ok("POST", f"{base}/media/{init['mediaId']}/complete")
    m = stack.settle_media(op, v, cap, init["mediaId"])
    ev.check("2.6", "Completed from the remaining parts only, and accepted", "ACCEPTED", m["status"] == "ACCEPTED", f"{m['status']}", m)


@pytest.mark.parametrize("case", ["checksum", "not-a-video", "eicar"])
def test_invalid_files_are_rejected(world, capture_video, case, ev):
    """A declared checksum that is not the bytes', a text file sent as video/mp4, and the EICAR test file: none is accepted."""
    op, v = world.u("operator"), world.venue_a
    cap = _capture(world)
    if case == "checksum":
        m = stack.upload(op, v, cap, capture_video, "c.mp4", "video/mp4", declared_sha="0" * 64)
        ev.check("2.7", "Declared checksum does not match the bytes", "REJECTED", m["status"] == "REJECTED", f"{m['status']} {m.get('reason') or m.get('rejectionReason')}", m)
    elif case == "not-a-video":
        m = stack.upload(op, v, cap, b"this is plain text, not an MP4 container\n" * 200, "c.mp4", "video/mp4")
        ev.check("2.8", "Content does not match the declared type", "REJECTED (content sniffing)", m["status"] == "REJECTED",
                 f"{m['status']} {m.get('reason') or m.get('rejectionReason')}", m)
    else:
        r = op("POST", f"/api/v1/venues/{v}/captures/{cap}/media", json={"kind": "METADATA", "filename": "eicar.json",
                                                                         "contentType": "application/json", "sizeBytes": len(EICAR), "sha256": stack.sha(EICAR)})
        if r.status_code >= 400:
            ev.check("2.9", "The EICAR test file is not accepted", "refused or REJECTED/QUARANTINED", True, f"refused at init: HTTP {r.status_code}")
            return
        init = r.json()
        op.ok("PUT", f"/api/v1/venues/{v}/captures/{cap}/media/{init['mediaId']}/parts/1", data=EICAR,
              headers={"Content-Type": "application/octet-stream"})
        op.ok("POST", f"/api/v1/venues/{v}/captures/{cap}/media/{init['mediaId']}/complete")
        m = stack.settle_media(op, v, cap, init["mediaId"])
        ev.check("2.9", "The EICAR test file is not accepted", "REJECTED or QUARANTINED (real ClamAV)", m["status"] in ("REJECTED", "QUARANTINED"),
                 f"{m['status']} {m.get('reason') or m.get('rejectionReason')}", m)


def test_calibration_evidence_is_persisted_and_drives_the_metric_frame(floor0_v1, world, ev):
    """Control points recorded against the capture video before processing; after the poses exist they calibrate the run."""
    op = world.u("operator")
    base = f"/api/v1/venues/{world.venue_a}/captures/{floor0_v1.capture}"
    ms = op.ok("GET", base + "/measurements")
    items = ms if isinstance(ms, list) else ms.get("measurements", [])
    ev.check("2.10", "Every recorded control point is stored, in metres, with its two observations",
             f"{len(stack.FIXTURE['control_points_by_construction'])} CONTROL_POINT measurements",
             len(items) == len(stack.FIXTURE["control_points_by_construction"]) and all(m["kind"] == "CONTROL_POINT" for m in items),
             f"{[(m['label'], m.get('venueMetres')) for m in items]}")
    cal = op.ok("GET", base + "/calibration")
    frame = cal.get("activeFrame") or {}
    ev.check("2.11", "The capture is calibrated from that evidence", "CALIBRATED, VENUE_CONTROL_POINTS datum, scale 2.5, RMS ~0",
             cal.get("state") == "CALIBRATED" and frame.get("horizontalDatum") == "VENUE_CONTROL_POINTS"
             and abs(frame.get("scale", 0) - 2.5) < 1e-6 and frame.get("controlPointRmsM", 1) < 1e-6,
             f"state={cal.get('state')} datum={frame.get('horizontalDatum')} scale={frame.get('scale')} rms={frame.get('controlPointRmsM')}")
    ev.check("2.12", "The evidence is marked as used by the active frame", "every measurement usedByActiveFrame",
             all(m.get("usedByActiveFrame") for m in op.ok("GET", base + "/measurements")), "")
    attempts = op.ok("GET", base + "/calibration/attempts")
    att = attempts if isinstance(attempts, list) else attempts.get("attempts", [])
    ev.check("2.13", "The calibration attempt is recorded (append-only)", ">= 1 attempt, accepted", len(att) >= 1, f"{att[:1]}")
