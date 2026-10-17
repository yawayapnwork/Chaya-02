"""4. VIEWER: the generated model, served by the real API to the real viewer contract; missing and corrupt models.

The browser half (the real web app and GaussianSplats3D in Chromium, scripts/e2e/viewer_format_check.cjs) runs after this
suite against the same stack, from the handoff these tests write (scripts/acceptance/run-acceptance.sh)."""

from __future__ import annotations

import uuid

import pytest
import requests

from . import stack

pytestmark = pytest.mark.acceptance


def _artifact(cfg, path: str, headers: dict) -> tuple[int, bytes, dict, str | None]:
    """GETs an artifact, reading the whole stream; a stream the API ends early (failed integrity) is reported, not hidden."""
    try:
        r = requests.get(cfg.api + path, headers=headers, timeout=120, stream=True)
        body = b"".join(r.iter_content(65536))
        return r.status_code, body, dict(r.headers), None
    except requests.RequestException as e:
        return -1, b"", {}, f"{type(e).__name__}: {str(e)[:200]}"


def test_the_generated_model_is_retrieved_by_signed_in_and_public_viewers(floor0_v1, world, cfg, ev):
    """The KSPLAT the real ARTIFACT_GENERATION wrote, served under real authorization, meets the production viewer contract."""
    st = floor0_v1.status
    run_id = st["run"]["id"]
    ks = stack.artifacts_of(st, "ARTIFACT_GENERATION")["KSPLAT"]
    want = ks.get("sha256") or ks.get("checksumSha256")
    path = f"/api/v1/venues/{world.venue_a}/reconstructions/{run_id}/artifacts/KSPLAT"
    mgr = world.u("manager")
    code, body, h, err = _artifact(cfg, path, {"Authorization": "Bearer " + mgr.bearer()})
    ev.check("4.1", "Signed-in manager retrieves the model", "200 octet-stream; bytes hash to the recorded sha256",
             code == 200 and h.get("Content-Type", "").startswith("application/octet-stream") and stack.sha(body) == want,
             f"HTTP {code} {h.get('Content-Type')} {len(body)} B sha256={stack.sha(body)} recorded={want} {err or ''}")
    ok, fmt = stack.ksplat_header_ok(body)
    ev.check("4.2", "The served bytes meet the production viewer contract", "chaya_worker.ksplat.validate_file passes", ok, fmt)
    link = mgr.ok("POST", f"/api/v1/venues/{world.venue_a}/public-links", json={"label": f"acceptance {world.tag}", "ttl": "PT2H"})
    tok = requests.post(cfg.api + "/api/v1/public/viewer-token", json={"secret": link["secret"]}, timeout=30)
    ev.check("4.3", "A public viewer link yields a viewer token", "200 with token", tok.status_code == 200 and "token" in tok.json(),
             f"HTTP {tok.status_code}")
    code2, body2, _, err2 = _artifact(cfg, path, {"X-Chaya-Viewer-Token": tok.json()["token"]})
    ev.check("4.4", "The public viewer receives the same bytes", "200, same sha256", code2 == 200 and stack.sha(body2) == want,
             f"HTTP {code2} sha256={stack.sha(body2)} {err2 or ''}")
    for name, headers, expected in (("no credentials", {}, 401),
                                    ("org B's manager", {"Authorization": "Bearer " + world.u("manager_b").bearer()}, 404),
                                    ("operator of another venue", {"Authorization": "Bearer " + world.u("operator_a2").bearer()}, 404)):
        c, _, _, _ = _artifact(cfg, path, headers)
        ev.check(f"4.5.{name}", f"Model refused to {name}", str(expected), c == expected, f"HTTP {c}")
    raw, _, _, _ = _artifact(cfg, path.replace("/KSPLAT", "/SPLAT"), {"Authorization": "Bearer " + mgr.bearer()})
    ev.check("4.6", "Raw reconstruction kinds are not served to viewers", "400", raw == 400, f"HTTP {raw}")
    # POIs at the pillar bases (exact by construction), as the browser check expects them, plus the auto-detected ones.
    for p in stack.FIXTURE["pois_by_construction"]:
        x, y, z = p["canonical"]
        mgr.ok("POST", f"/api/v1/venues/{world.venue_a}/pois", json={"floorId": world.floor0, "label": p["label"], "category": "landmark",
                                                                     "tags": ["pillar"], "description": "synthetic fixture landmark",
                                                                     "x": x, "y": y, "z": z})
    pois = mgr.ok("GET", f"/api/v1/venues/{world.venue_a}/pois", params={"floorId": world.floor0})
    gaussians = ((stack.stage(st, "ARTIFACT_GENERATION").get("lastRun") or {}).get("command") or {}).get("config", {}).get("gaussian_count")
    floor0_v1.extra["ksplat_sha"] = want
    stack.handoff(cfg, viewerLink=link["secret"], managerUsername=f"acc-manager-{world.tag}",
                  managerPassword=world.kc.passwords[f"acc-manager-{world.tag}"], venueId=world.venue_a, floorId=world.floor0,
                  runId=run_id, ksplatSha256=want, splatCount=gaussians, pois=[p["label"] for p in pois])


def test_missing_and_corrupt_models_are_refused_not_served(floor0_v1, floor1_v1, world, cfg, ev):
    """An unknown version has no model (404). A stored model whose bytes no longer hash to the record is never delivered
    intact: the API ends the stream. Floor 1's model is corrupted for the browser check, which restores it afterwards."""
    mgr = world.u("manager")
    auth = {"Authorization": "Bearer " + mgr.bearer()}
    c, _, _, _ = _artifact(cfg, f"/api/v1/venues/{world.venue_a}/scan-versions/{uuid.uuid4()}/artifacts/KSPLAT", auth)
    ev.check("4.7", "A version that does not exist has no model", "404", c == 404, f"HTTP {c}")
    if not (cfg.s3_root_key and cfg.s3_root_secret):
        pytest.skip("BLOCKED: corrupting a stored model needs the object store's root account (ACC_S3_ROOT_*)")
    st = floor1_v1.status
    run_id = st["run"]["id"]
    path = f"/api/v1/venues/{world.venue_a}/reconstructions/{run_id}/artifacts/KSPLAT"
    good_code, good, _, _ = _artifact(cfg, path, auth)
    ks = stack.artifacts_of(st, "ARTIFACT_GENERATION")["KSPLAT"]
    s3 = stack.s3_client(cfg, cfg.s3_root_key, cfg.s3_root_secret)
    # the object the API serves is its sealed copy; find it by size and content among the run's derived objects
    prefix = (ks.get("key") or "").rsplit("/", 1)[0]
    candidates = [o["Key"] for o in s3.list_objects_v2(Bucket=cfg.bucket_derived, Prefix="sealed/" + prefix).get("Contents", [])
                  if o["Key"].endswith(".ksplat")] or [ks.get("key")]
    key = candidates[0]
    original = s3.get_object(Bucket=cfg.bucket_derived, Key=key)["Body"].read()
    corrupt = bytearray(original)
    for i in range(len(corrupt) // 2, len(corrupt) // 2 + 64):
        corrupt[i] ^= 0xFF
    s3.put_object(Bucket=cfg.bucket_derived, Key=key, Body=bytes(corrupt), ContentType="application/octet-stream")
    stack.handoff(cfg, corrupt={"runId": run_id, "floorId": world.floor1, "bucket": cfg.bucket_derived, "key": key,
                                "restoreSha256": stack.sha(original)})
    stack.write_restore(cfg, cfg.bucket_derived, key, original)
    code, body, _, err = _artifact(cfg, path, auth)
    ev.check("4.8", "Before corruption the model is served intact", "200, recorded sha256", good_code == 200 and stack.sha(good) == stack.sha(original),
             f"HTTP {good_code}")
    ev.check("4.9", "A corrupted model is never delivered intact", "the stream ends early or errors; never the full corrupted bytes as 200",
             not (code == 200 and len(body) == len(corrupt) and err is None),
             f"HTTP {code} received {len(body)} of {len(corrupt)} B; {err or 'no transport error'}")
