"""Benchmark 3: semantic search (docs/BENCHMARKS.md).

Runs the dataset's queries through the REAL search endpoint of a running stack, in two conditions:

    semantic   the vision service (CLIP ViT-B/32 text tower) is up: pgvector cosine ranking over POI embeddings
               written by the POI embedding job (dev.chaya.api.search.PoiEmbeddingService)
    lexical    the vision service is stopped: the API's own fallback, pg_trgm trigram similarity on the label -- the
               non-semantic baseline the system would otherwise have

and, offline with the same vision service, an ablation of the text each POI is embedded from:

    label_only              just the label
    label_category_tags     label + category + tags (what PoiEmbeddingService embeds)

then, in the same venue, the mixed condition (detected_objects.json): real object crops are added as AUTO_DETECTED POIs,
stored exactly as the API under test stores a SEMANTIC_INDEXING detection, and every query (the manual set plus the
detected-object set) runs again:

    mixed       manual POIs + detected objects, vision up

Measured: top-1 / top-5 accuracy per query type, correct rejection of zero-result queries, client and server
latency, and how long the embedding job took to make new POIs searchable.

    python benchmarks/b3_semantic_search/run.py --env-file <stack env> [--reps 3] [--api-note "..."] [--only-mixed]

Needs the E2E stack (docs/E2E_VALIDATION.md section 2) with the API image that contains the embedding job.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
import time
import uuid
from pathlib import Path

import numpy as np
import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmarks.common.results import Metric, Run, sha256_file  # noqa: E402
from benchmarks.common.stack import Stack  # noqa: E402

HERE = Path(__file__).resolve().parent
DATASET = HERE / "dataset.json"
DETECTED = HERE / "detected_objects.json"
DETECTED_VECTORS = HERE / "detected_vectors.json"
TOPK = 10
TYPES = ("exact", "synonym", "ambiguous", "zero_result")
DETECTED_TYPES = ("detected_exact", "detected_synonym", "visual_attribute", "mixed")
SEARCH_SPACING_S = 1.1  # the API allows 60 searches per minute per user (chaya.rate-limit.search-per-minute)


def words(s: str) -> set[str]:
    return {w for w in re.split(r"[^a-z0-9]+", s.lower()) if w}


def check_synonym_rule(ds: dict, det: dict | None = None) -> None:
    """A synonym query shares no word with the text of any expected POI (a detected object's text is its label)."""
    by_label = {p["label"]: p for p in ds["pois"]}
    queries = list(ds["queries"])
    if det:
        by_label.update({p["label"]: p for p in det["extra_manual_pois"]})
        by_label.update({o["id"]: {"label": o["label"], "category": None, "tags": []} for o in det["detected"]})
        queries += det["queries"]
    bad = []
    for q in queries:
        if q["type"] not in ("synonym", "detected_synonym"):
            continue
        for label in q["expected"]:
            p = by_label[label]
            vocab = words(p["label"]) | words(p["category"] or "") | set().union(*[words(t) for t in p["tags"]] or [set()])
            shared = words(q["q"]) & vocab
            if shared:
                bad.append(f"{q['q']!r} shares {sorted(shared)} with {label!r}")
    if bad:
        sys.exit("dataset violates the synonym rule:\n  " + "\n  ".join(bad))


def percentile(xs: list[float], p: float) -> float:
    s = sorted(xs)
    k = (len(s) - 1) * p
    lo, hi = math.floor(k), math.ceil(k)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def score(results: dict[str, list[str]], ds: dict, types: tuple[str, ...] = TYPES) -> dict[str, dict[str, float]]:
    """top1/top5 per type for queries with expected answers; correct-rejection rate for zero-result queries."""
    out: dict[str, dict[str, float]] = {}
    for t in types:
        qs = [q for q in ds["queries"] if q["type"] == t]
        if t == "zero_result":
            out[t] = {"n": len(qs), "correct_rejection": sum(1 for q in qs if not results[q["q"]]) / len(qs)}
            continue
        top1 = sum(1 for q in qs if results[q["q"]][:1] and results[q["q"]][0] in q["expected"])
        top5 = sum(1 for q in qs if set(results[q["q"]][:5]) & set(q["expected"]))
        out[t] = {"n": len(qs), "top1": top1 / len(qs), "top5": top5 / len(qs)}
    answerable = [q for q in ds["queries"] if q["expected"]]
    out["all_answerable"] = {
        "n": len(answerable),
        "top1": sum(1 for q in answerable if results[q["q"]][:1] and results[q["q"]][0] in q["expected"]) / len(answerable),
        "top5": sum(1 for q in answerable if set(results[q["q"]][:5]) & set(q["expected"])) / len(answerable)}
    return out


def random_reference(ds: dict) -> dict[str, dict[str, float]]:
    """Expected top-1/top-5 of a uniformly random ranking of the venue's N POIs (m acceptable answers)."""
    n = len(ds["pois"])
    out = {}
    for t in ("exact", "synonym", "ambiguous"):
        qs = [q for q in ds["queries"] if q["type"] == t]
        t1 = statistics.mean(len(q["expected"]) / n for q in qs)
        # With fewer than 5 POIs every top-5 list contains all of them.
        t5 = statistics.mean(1 - math.comb(n - len(q["expected"]), 5) / math.comb(n, 5) if n >= 5 else 1.0 for q in qs)
        out[t] = {"top1": t1, "top5": t5}
    return out


class Bench:
    def __init__(self, stack: Stack, ds: dict, reps: int):
        self.s, self.ds, self.reps = stack, ds, reps
        self.user = f"bench-b3-{uuid.uuid4().hex[:8]}"
        self._last = 0.0
        self.rate_limited = 0
        self.detected_ids: dict[str, str] = {}  # poi id -> detected_objects.json id

    def ident(self, result: dict) -> str:
        """The dataset's name for a result: a manual POI's (unique) label, a detected object's id."""
        return self.detected_ids.get(result["poiId"], result["label"])

    def setup(self) -> dict:
        s = self.s
        s.ensure_test_client()
        tag = uuid.uuid4().hex[:8]
        org = s.create_organization(f"bench-b3-{tag}", "Benchmark B3")
        self.org = org
        admin = f"{self.user}-admin"
        s.create_user(admin, "admin", org, [])
        v = s.call(admin, "POST", "/api/v1/venues", json={"slug": f"b3-{tag}", "name": "B3 conference centre"})
        v.raise_for_status()
        self.venue = v.json()["id"]
        s.create_user(self.user, "venue-manager", org, [self.venue])
        f = s.call(self.user, "POST", f"/api/v1/venues/{self.venue}/floors", json={"level": 0, "name": "Ground floor"})
        f.raise_for_status()
        self.floor = f.json()["id"]
        t0 = time.time()
        for p in self.ds["pois"]:
            r = s.call(self.user, "POST", f"/api/v1/venues/{self.venue}/pois", json={
                "floorId": self.floor, "label": p["label"], "category": p["category"], "tags": p["tags"],
                "x": p["x"], "y": 0.0, "z": p["z"]})
            r.raise_for_status()
        created = time.time()
        pending = self.wait_embedded(created)
        models = s.psql(f"SELECT DISTINCT v.embedding_model FROM poi_version v JOIN poi p ON p.id = v.poi_id WHERE p.venue_id = '{self.venue}'")
        return {"org": org, "venue": self.venue, "create_seconds": created - t0,
                "embedded_all_after_seconds": (time.time() - created) if pending == 0 else None, "pending_left": pending,
                "embedding_models": models.splitlines()}

    def wait_embedded(self, since: float) -> int | None:
        """Polls until every live POI of the venue has its text embedding (or 300 s pass); returns how many are left."""
        pending = None
        while time.time() - since < 300:
            pending = int(self.s.psql(
                "SELECT count(*) FROM poi p JOIN LATERAL (SELECT embedding IS NULL AS e FROM poi_version WHERE poi_id = p.id "
                f"ORDER BY version_number DESC LIMIT 1) v ON true WHERE p.venue_id = '{self.venue}' AND p.deleted_at IS NULL AND v.e"))
            if pending == 0:
                break
            time.sleep(0.5)
        return pending

    def add_detected(self, det: dict, vectors: dict) -> dict:
        """Adds detected_objects.json to the venue: its extra manual POIs through the API, and each crop as an AUTO_DETECTED
        POI stored the way the API under test stores a SEMANTIC_INDEXING detection (PipelineService#insertDetectedPoi).
        With V21__search_embedding_spaces the crop's CLIP image vector goes to image_embedding and the label's text vector
        is left to the embedding backfill; before it, the image vector went to embedding. Provenance is a SUCCEEDED
        pipeline run of the benchmark's own, as the schema requires for machine-written POIs. The boxes are COCO's human
        annotations, so there is no detector confidence (stored as NULL)."""
        s = self.s
        t0 = time.time()
        for p in det["extra_manual_pois"]:
            r = s.call(self.user, "POST", f"/api/v1/venues/{self.venue}/pois", json={
                "floorId": self.floor, "label": p["label"], "category": p["category"], "tags": p["tags"],
                "x": p["x"], "y": 0.0, "z": p["z"]})
            r.raise_for_status()
        session = s.psql("INSERT INTO capture_session (organization_id, venue_id, floor_id, operator_id) VALUES "
                         f"('{self.org}', '{self.venue}', '{self.floor}', 'bench-b3') RETURNING id")
        scan = s.psql(f"INSERT INTO scan (organization_id, venue_id, capture_session_id) VALUES ('{self.org}', '{self.venue}', "
                      f"'{session}') RETURNING id")
        run = s.psql("INSERT INTO pipeline_run (organization_id, venue_id, scan_id, capture_session_id, status, quality, stages, "
                     "time_budget_seconds, deadline_at, finished_at, requested_by) VALUES "
                     f"('{self.org}', '{self.venue}', '{scan}', '{session}', 'SUCCEEDED', 'FINAL', '{{SEMANTIC_INDEXING}}', 3600, "
                     "now(), now(), 'bench-b3') RETURNING id")
        split = s.psql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'poi_version' "
                       "AND column_name = 'image_embedding'") == "1"
        cols = "image_embedding, image_embedding_model" if split else "embedding, embedding_model"
        model = vectors["image_model"]
        for o in det["detected"]:
            vec = "[" + ",".join(repr(float(x)) for x in vectors["crops"][o["id"]]["embedding"]) + "]"
            x, y, w, h = o["bbox_xywh"]
            bbox = json.dumps({"x": int(x), "y": int(y), "width": int(x + w) - int(x), "height": int(y + h) - int(y),
                               "sourceFrame": o["url"].rsplit("/", 1)[-1]})
            poi = s.psql(f"INSERT INTO poi (organization_id, venue_id, floor_id) VALUES ('{self.org}', '{self.venue}', "
                         f"'{self.floor}') RETURNING id")
            s.psql(f"INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, tags, x, y, z, {cols}, "
                   "source, detection_confidence, bounding_box, pipeline_run_id, created_by) VALUES "
                   f"('{self.org}', '{self.venue}', '{poi}', 1, '{o['label']}', '{{}}', {o['x']}, 0, {o['z']}, "
                   f"CAST('{vec}' AS vector), '{model}', 'AUTO_DETECTED', NULL, CAST('{bbox}' AS jsonb), '{run}', "
                   "'system:semantic-indexing')")
            self.detected_ids[poi] = o["id"]
        inserted = time.time()
        pending = self.wait_embedded(inserted)
        return {"image_embedding_column": split, "detected": len(det["detected"]), "extra_manual": len(det["extra_manual_pois"]),
                "insert_seconds": inserted - t0, "embedded_all_after_seconds": (time.time() - inserted) if pending == 0 else None,
                "pending_left": pending}

    def search(self, q: str) -> tuple[requests.Response, float]:
        wait = SEARCH_SPACING_S - (time.time() - self._last)
        if wait > 0:
            time.sleep(wait)
        for _ in range(3):
            t0 = time.perf_counter()
            r = self.s.call(self.user, "GET", f"/api/v1/venues/{self.venue}/search", params={"q": q, "topK": TOPK})
            ms = (time.perf_counter() - t0) * 1000
            self._last = time.time()
            if r.status_code != 429:
                return r, ms
            self.rate_limited += 1
            time.sleep(61 - time.time() % 60)
        r.raise_for_status()
        return r, ms

    def condition(self, name: str, expect_match: str, queries: list[dict] | None = None, reps: int | None = None) -> dict:
        queries = self.ds["queries"] if queries is None else queries
        ranked, sims, closest, relevance, match_types, latencies = {}, {}, {}, {}, set(), []
        sources, matched_by = {}, {}
        for q in queries:
            r, _ = self.search(q["q"])
            r.raise_for_status()
            body = r.json()
            match_types.add(body["matchType"])
            # `results` is what the API says the venue has; closestMatches (API >= relevance filter) are only suggestions.
            ranked[q["q"]] = [self.ident(x) for x in body["results"]]
            sims[q["q"]] = [round(x["similarity"], 4) for x in body["results"]]
            sources[q["q"]] = [x["source"] for x in body["results"]]
            matched_by[q["q"]] = [x.get("matchedBy") for x in body["results"]]
            closest[q["q"]] = [self.ident(x) for x in body.get("closestMatches") or []]
            relevance[q["q"]] = body.get("relevance")
        for _ in range(self.reps if reps is None else reps):
            for q in queries:
                _, ms = self.search(q["q"])
                latencies.append(ms)
        if match_types != {expect_match}:
            raise RuntimeError(f"{name}: expected matchType {expect_match}, got {match_types}")
        return {"ranked": ranked, "similarities": sims, "closest": closest, "relevance": relevance, "sources": sources,
                "matched_by": matched_by, "client_latency_ms": latencies, "match_types": sorted(match_types)}

    def server_latency(self, since_rows: int) -> list[int]:
        rows = self.s.psql(f"SELECT latency_ms FROM search_query WHERE venue_id = '{self.venue}' ORDER BY created_at OFFSET {since_rows}")
        return [int(x) for x in rows.splitlines() if x]

    def query_rows(self) -> int:
        return int(self.s.psql(f"SELECT count(*) FROM search_query WHERE venue_id = '{self.venue}'"))


def vision_embed(stack: Stack, text: str) -> np.ndarray:
    r = requests.post(stack.vision + "/v1/embed-text", json={"text": text}, timeout=60)
    r.raise_for_status()
    v = np.asarray(r.json()["embedding"], dtype=np.float64)
    return v / np.linalg.norm(v)


def ablation(stack: Stack, ds: dict) -> dict:
    """Same model, same cosine ranking as pgvector's <=>, different POI text. Queries are lower-cased exactly as the API
    does (SemanticSearchService normalises before embedding)."""
    compositions = {
        "label_only": lambda p: p["label"],
        # Mirrors dev.chaya.api.search.PoiEmbeddingService.embeddingText: label, category, tags, de-duplicated.
        "label_category_tags": lambda p: ", ".join(dedup_ci(
            [x.strip() for x in [p["label"], p["category"] or "", *p["tags"]] if x and x.strip()])),
    }
    qvec = {q["q"]: vision_embed(stack, q["q"].strip().lower()) for q in ds["queries"]}
    out = {}
    for name, fn in compositions.items():
        texts = [fn(p) for p in ds["pois"]]
        mat = np.stack([vision_embed(stack, t) for t in texts])
        ranked = {}
        for q in ds["queries"]:
            sims = mat @ qvec[q["q"]]
            order = np.argsort(-sims, kind="stable")[:TOPK]
            ranked[q["q"]] = [ds["pois"][i]["label"] for i in order]
        out[name] = {"texts": texts, "ranked": ranked}
    return out


def dedup_ci(items: list[str]) -> list[str]:
    seen, out = set(), []
    for x in items:
        if x.lower() not in seen:
            seen.add(x.lower())
            out.append(x)
    return out


def mixed(run: Run, b: Bench, ds: dict, det: dict, vectors: dict) -> None:
    """Adds the detected objects to the venue and runs every query (manual set + detected set) against the mix."""
    setup = b.add_detected(det, vectors)
    run.details["mixed setup"] = setup
    if setup["pending_left"] != 0:
        sys.exit(f"the embedding job did not embed every POI within 300 s ({setup['pending_left']} left)")
    queries = ds["queries"] + det["queries"]
    rows_before = b.query_rows()
    data = b.condition("mixed", "embedding", queries=queries, reps=1)
    server = b.server_latency(rows_before)
    cond = "mixed: manual + detected"
    det_ids = {o["id"] for o in det["detected"]}

    sc = score(data["ranked"], ds)  # the manual query set, now with detected objects in the venue
    for t in ("exact", "synonym", "ambiguous", "all_answerable"):
        run.add(Metric(f"top-1 accuracy [{t}]", round(sc[t]["top1"], 4), "fraction", "measured", cond, "API /search",
                       note=f"n={sc[t]['n']}; manual query set"),
                Metric(f"top-5 accuracy [{t}]", round(sc[t]["top5"], 4), "fraction", "measured", cond, "API /search",
                       note=f"n={sc[t]['n']}; manual query set"))
    run.add(Metric("zero-result queries correctly returning no results", round(sc["zero_result"]["correct_rejection"], 4),
                   "fraction", "measured", cond, "API /search", note=f"n={sc['zero_result']['n']}"))
    dsc = score(data["ranked"], {"queries": det["queries"]}, DETECTED_TYPES)
    for t in DETECTED_TYPES:
        run.add(Metric(f"top-1 accuracy [{t}]", round(dsc[t]["top1"], 4), "fraction", "measured", cond, "API /search",
                       note=f"n={dsc[t]['n']}"),
                Metric(f"top-5 accuracy [{t}]", round(dsc[t]["top5"], 4), "fraction", "measured", cond, "API /search",
                       note=f"n={dsc[t]['n']}"))

    wants_detected = [q for q in det["queries"] if set(q["expected"]) & det_ids]
    reached = [q["q"] for q in wants_detected if set(data["ranked"][q["q"]][:5]) & det_ids]
    run.add(Metric("queries whose answer includes a detected object that return one in the top 5", f"{len(reached)}/{len(wants_detected)}",
                   "queries", "measured", cond, "API /search",
                   note=", ".join(sorted({q["q"] for q in wants_detected} - set(reached))) or None))
    mixed_qs = [q for q in det["queries"] if q["type"] == "mixed"]
    both = [q["q"] for q in mixed_qs if any(x in q["expected"] and x in det_ids for x in data["ranked"][q["q"]][:5])
            and any(x in q["expected"] and x not in det_ids for x in data["ranked"][q["q"]][:5])]
    run.add(Metric("mixed queries with an expected manual AND an expected detected POI in the top 5", f"{len(both)}/{len(mixed_qs)}",
                   "queries", "measured", cond, "API /search"))
    intruded = [q["q"] for q in ds["queries"] if set(data["ranked"][q["q"]]) & det_ids]
    run.add(Metric("manual-set queries whose results include a detected object", f"{len(intruded)}/{len(ds['queries'])}", "queries",
                   "measured", cond, "API /search", note=", ".join(intruded) or None))
    by = [m for ms in data["matched_by"].values() for m in ms]
    run.add(Metric("results admitted by the image channel alone (matchedBy = IMAGE)", f"{by.count('IMAGE')}/{len(by)}", "results",
                   "measured", cond, "API /search", note="0/n on an API without matchedBy"))
    run.add(Metric("server latency p50", percentile(server, 0.5), "ms", "measured", cond, "search_query.latency_ms",
                   note=f"{len(server)} requests"),
            Metric("server latency p95", percentile(server, 0.95), "ms", "measured", cond, "search_query.latency_ms",
                   note=f"{len(server)} requests"))
    run.details[cond] = {"scores_manual_set": sc, "scores_detected_set": dsc,
                         "ranked_top5": {k: v[:5] for k, v in data["ranked"].items()},
                         "sources_top5": {k: v[:5] for k, v in data["sources"].items()},
                         "similarities_top5": {k: v[:5] for k, v in data["similarities"].items()},
                         "matched_by_top5": {k: v[:5] for k, v in data["matched_by"].items()},
                         "closest": data["closest"], "relevance": data["relevance"]}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--env-file", required=True)
    ap.add_argument("--reps", type=int, default=3, help="timed repetitions per query per condition")
    ap.add_argument("--only-mixed", action="store_true", help="only the mixed condition (skip semantic, lexical, ablation)")
    ap.add_argument("--no-mixed", action="store_true", help="skip the mixed condition")
    ap.add_argument("--api-note", help="which API build is under test (recorded with the results)")
    ap.add_argument("--project", default="chaya-e2e", help="docker compose project of the stack")
    a = ap.parse_args()
    ds = json.loads(DATASET.read_text(encoding="utf-8"))
    det = None if a.no_mixed else json.loads(DETECTED.read_text(encoding="utf-8"))
    vectors = None if a.no_mixed else json.loads(DETECTED_VECTORS.read_text(encoding="utf-8"))
    check_synonym_rule(ds, det)
    stack = Stack(a.env_file, a.project)
    run = Run("b3_semantic_search", "Semantic search: CLIP embeddings vs lexical fallback")
    run.inputs = {"dataset": str(DATASET.relative_to(DATASET.parents[2])), "dataset_sha256": sha256_file(DATASET),
                  "pois": len(ds["pois"]), "queries": {t: sum(1 for q in ds["queries"] if q["type"] == t) for t in TYPES}}
    if det:
        run.inputs.update({
            "detected_objects": str(DETECTED.relative_to(DATASET.parents[2])), "detected_objects_sha256": sha256_file(DETECTED),
            "detected_vectors_sha256": sha256_file(DETECTED_VECTORS), "detected": len(det["detected"]),
            "extra_manual_pois": len(det["extra_manual_pois"]),
            "detected_queries": {t: sum(1 for q in det["queries"] if q["type"] == t) for t in DETECTED_TYPES}})
    run.details["api_note"] = a.api_note
    run.environment = {"api_image": stack.image_id("api"), "vision_image": stack.image_id("vision"),
                       "vision_model": requests.get(stack.vision + "/health/ready", timeout=30).json().get("model")}

    b = Bench(stack, ds, a.reps)
    setup = b.setup()
    run.details["setup"] = setup
    if setup["pending_left"] != 0:
        sys.exit(f"the embedding job did not embed every POI within 300 s ({setup['pending_left']} left)")
    run.add(Metric("time until all new POIs searchable by meaning", round(setup["embedded_all_after_seconds"], 2), "s", "measured",
                   "embedding job", "poll of poi_version.embedding after the last POI was created",
                   note=f"{len(ds['pois'])} POIs; job interval chaya.search.embedding-backfill-interval"))
    run.add(Metric("embedding backfill interval", 5, "s", "configuration", "embedding job", "application.yml"),
            Metric("search rate limit", 60, "requests/min/user", "configuration", "all", "chaya.rate-limit.search-per-minute",
                   note="the runner paces requests below it; pacing does not affect per-request latency"),
            Metric("top-k requested", TOPK, "results", "configuration", "all", "runner"))

    if not a.only_mixed:
        rows_before = b.query_rows()
        semantic = b.condition("semantic", "embedding")
        rows_mid = b.query_rows()
        server_sem = b.server_latency(rows_before)

        stack.docker("stop", f"{stack.project}-vision-1")
        try:
            lexical = b.condition("lexical", "lexical_fallback")
            server_lex = b.server_latency(rows_mid)
        finally:
            stack.docker("start", f"{stack.project}-vision-1")
            for _ in range(120):
                try:
                    if requests.get(stack.vision + "/health/ready", timeout=5).ok:
                        break
                except requests.RequestException:
                    pass
                time.sleep(2)

        abl = ablation(stack, ds)
        agreement = sum(1 for q in ds["queries"] if abl["label_category_tags"]["ranked"][q["q"]][:5] == semantic["ranked"][q["q"]][:5])

        for cond, data, server in (("semantic (CLIP)", semantic, server_sem), ("lexical fallback (pg_trgm)", lexical, server_lex)):
            sc = score(data["ranked"], ds)
            for t in ("exact", "synonym", "ambiguous", "all_answerable"):
                run.add(Metric(f"top-1 accuracy [{t}]", round(sc[t]["top1"], 4), "fraction", "measured", cond, "API /search",
                               note=f"n={sc[t]['n']}"),
                        Metric(f"top-5 accuracy [{t}]", round(sc[t]["top5"], 4), "fraction", "measured", cond, "API /search",
                               note=f"n={sc[t]['n']}"))
            run.add(Metric("zero-result queries correctly returning no results", round(sc["zero_result"]["correct_rejection"], 4),
                           "fraction", "measured", cond, "API /search", note=f"n={sc['zero_result']['n']}"))
            lat = data["client_latency_ms"]
            run.add(Metric("client latency p50", round(percentile(lat, 0.5), 1), "ms", "measured", cond,
                           "HTTP round trip from the benchmark host", note=f"{len(lat)} requests"),
                    Metric("client latency p95", round(percentile(lat, 0.95), 1), "ms", "measured", cond,
                           "HTTP round trip from the benchmark host", note=f"{len(lat)} requests"),
                    Metric("server latency p50", percentile(server, 0.5), "ms", "measured", cond, "search_query.latency_ms",
                           note=f"{len(server)} requests"),
                    Metric("server latency p95", percentile(server, 0.95), "ms", "measured", cond, "search_query.latency_ms",
                           note=f"{len(server)} requests"))
            # Answerable queries the relevance filter emptied, whose correct answer is still offered as the first closest match.
            rescued = [q["q"] for q in ds["queries"] if q["expected"] and not data["ranked"][q["q"]]
                       and data["closest"][q["q"]][:1] and data["closest"][q["q"]][0] in q["expected"]]
            emptied = [q["q"] for q in ds["queries"] if q["expected"] and not data["ranked"][q["q"]]]
            if any(v == "FILTERED" for v in data["relevance"].values()):
                run.add(Metric("answerable queries returning no results (filtered out)", f"{len(emptied)}/{sum(1 for q in ds['queries'] if q['expected'])}",
                               "queries", "measured", cond, "API /search", note=", ".join(emptied) or None),
                        Metric("of those, correct answer offered as the first closest match", f"{len(rescued)}/{len(emptied)}", "queries",
                               "measured", cond, "API /search closestMatches", note=", ".join(rescued) or None))
            run.details[cond] = {"scores": sc, "ranked_top5": {k: v[:5] for k, v in data["ranked"].items()},
                                 "similarities_top5": {k: v[:5] for k, v in data["similarities"].items()},
                                 "closest": data["closest"], "relevance": data["relevance"]}

        # How far apart the top similarity of answerable and zero-result queries lie (does a cut-off exist?).
        top_sim = {q["q"]: (semantic["similarities"][q["q"]] or [None])[0] for q in ds["queries"]}
        ans = [top_sim[q["q"]] for q in ds["queries"] if q["expected"] and semantic["ranked"][q["q"]][:1]
               and semantic["ranked"][q["q"]][0] in q["expected"]]
        zero = [top_sim[q["q"]] for q in ds["queries"] if not q["expected"] and top_sim[q["q"]] is not None]
        if ans and zero:
            run.add(Metric("top-1 similarity, correctly answered queries (min / median)", f"{min(ans):.3f} / {statistics.median(ans):.3f}",
                           "cosine", "measured", "semantic (CLIP)", "API /search"),
                    Metric("top-1 similarity, zero-result queries (median / max)", f"{statistics.median(zero):.3f} / {max(zero):.3f}",
                           "cosine", "measured", "semantic (CLIP)", "API /search",
                           note="if max(zero) >= min(correct), no single similarity cut-off separates them on this dataset"))

        for name in ("label_only", "label_category_tags"):
            sc = score(abl[name]["ranked"], ds)
            for t in ("exact", "synonym", "ambiguous", "all_answerable"):
                run.add(Metric(f"top-1 accuracy [{t}]", round(sc[t]["top1"], 4), "fraction", "measured", f"ablation: {name}",
                               "vision /v1/embed-text + cosine (offline)", note=f"n={sc[t]['n']}"),
                        Metric(f"top-5 accuracy [{t}]", round(sc[t]["top5"], 4), "fraction", "measured", f"ablation: {name}",
                               "vision /v1/embed-text + cosine (offline)", note=f"n={sc[t]['n']}"))
            run.details[f"ablation {name}"] = {"scores": sc, "texts": abl[name]["texts"]}
        run.add(Metric("offline ablation reproduces the API's top-5 (label_category_tags)", f"{agreement}/{len(ds['queries'])}",
                       "queries", "measured", "consistency check", "offline ranking vs API ranking"))

    if det:
        mixed(run, b, ds, det, vectors)

    for t, v in random_reference(ds).items():
        run.add(Metric(f"top-1 accuracy [{t}]", round(v["top1"], 4), "fraction", "reference", "random ranking",
                       "analytic: m/N", note=f"N={len(ds['pois'])} POIs"),
                Metric(f"top-5 accuracy [{t}]", round(v["top5"], 4), "fraction", "reference", "random ranking",
                       "analytic: 1 - C(N-m,5)/C(N,5)", note=f"N={len(ds['pois'])} POIs"))
    run.add(Metric("dataset", f"author-constructed, {len(ds['pois'])} POIs / {len(ds['queries'])} queries", "", "assumption", "all", "dataset.json",
                   note="stands in for real venue metadata and real user queries, which do not exist yet"))
    run.details["rate_limited_retries"] = b.rate_limited
    path = run.write()
    print(f"wrote {path}")
    for m in run.metrics:
        print(f"  [{m.kind:12}] {m.condition:28} {m.metric}: {m.value} {m.unit} {('(' + m.note + ')') if m.note else ''}")


if __name__ == "__main__":
    main()
