"""Benchmark 3: semantic search (docs/BENCHMARKS.md).

Runs the dataset's queries through the REAL search endpoint of a running stack, in two conditions:

    semantic   the vision service (CLIP ViT-B/32 text tower) is up: pgvector cosine ranking over POI embeddings
               written by the POI embedding job (dev.chaya.api.search.PoiEmbeddingService)
    lexical    the vision service is stopped: the API's own fallback, pg_trgm trigram similarity on the label -- the
               non-semantic baseline the system would otherwise have

and, offline with the same vision service, an ablation of the text each POI is embedded from:

    label_only              just the label
    label_category_tags     label + category + tags (what PoiEmbeddingService embeds)

Measured: top-1 / top-5 accuracy per query type, correct rejection of zero-result queries, client and server
latency, and how long the embedding job took to make new POIs searchable.

    python benchmarks/b3_semantic_search/run.py --env-file <stack env> [--reps 3]

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
TOPK = 10
TYPES = ("exact", "synonym", "ambiguous", "zero_result")
SEARCH_SPACING_S = 1.1  # the API allows 60 searches per minute per user (chaya.rate-limit.search-per-minute)


def words(s: str) -> set[str]:
    return {w for w in re.split(r"[^a-z0-9]+", s.lower()) if w}


def check_synonym_rule(ds: dict) -> None:
    by_label = {p["label"]: p for p in ds["pois"]}
    bad = []
    for q in ds["queries"]:
        if q["type"] != "synonym":
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


def score(results: dict[str, list[str]], ds: dict) -> dict[str, dict[str, float]]:
    """top1/top5 per type for queries with expected answers; correct-rejection rate for zero-result queries."""
    out: dict[str, dict[str, float]] = {}
    for t in TYPES:
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

    def setup(self) -> dict:
        s = self.s
        s.ensure_test_client()
        tag = uuid.uuid4().hex[:8]
        org = s.create_organization(f"bench-b3-{tag}", "Benchmark B3")
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
        pending = None
        while time.time() - created < 300:
            pending = int(s.psql(
                "SELECT count(*) FROM poi p JOIN LATERAL (SELECT embedding IS NULL AS e FROM poi_version WHERE poi_id = p.id "
                f"ORDER BY version_number DESC LIMIT 1) v ON true WHERE p.venue_id = '{self.venue}' AND v.e"))
            if pending == 0:
                break
            time.sleep(0.5)
        models = s.psql(f"SELECT DISTINCT v.embedding_model FROM poi_version v JOIN poi p ON p.id = v.poi_id WHERE p.venue_id = '{self.venue}'")
        return {"org": org, "venue": self.venue, "create_seconds": created - t0,
                "embedded_all_after_seconds": (time.time() - created) if pending == 0 else None, "pending_left": pending,
                "embedding_models": models.splitlines()}

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

    def condition(self, name: str, expect_match: str) -> dict:
        ranked, sims, closest, relevance, match_types, latencies = {}, {}, {}, {}, set(), []
        for q in self.ds["queries"]:
            r, _ = self.search(q["q"])
            r.raise_for_status()
            body = r.json()
            match_types.add(body["matchType"])
            # `results` is what the API says the venue has; closestMatches (API >= relevance filter) are only suggestions.
            ranked[q["q"]] = [x["label"] for x in body["results"]]
            sims[q["q"]] = [round(x["similarity"], 4) for x in body["results"]]
            closest[q["q"]] = [x["label"] for x in body.get("closestMatches") or []]
            relevance[q["q"]] = body.get("relevance")
        for _ in range(self.reps):
            for q in self.ds["queries"]:
                _, ms = self.search(q["q"])
                latencies.append(ms)
        if match_types != {expect_match}:
            raise RuntimeError(f"{name}: expected matchType {expect_match}, got {match_types}")
        return {"ranked": ranked, "similarities": sims, "closest": closest, "relevance": relevance,
                "client_latency_ms": latencies, "match_types": sorted(match_types)}

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


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--env-file", required=True)
    ap.add_argument("--reps", type=int, default=3, help="timed repetitions per query per condition")
    a = ap.parse_args()
    ds = json.loads(DATASET.read_text(encoding="utf-8"))
    check_synonym_rule(ds)
    stack = Stack(a.env_file)
    run = Run("b3_semantic_search", "Semantic search: CLIP embeddings vs lexical fallback")
    run.inputs = {"dataset": str(DATASET.relative_to(DATASET.parents[2])), "dataset_sha256": sha256_file(DATASET),
                  "pois": len(ds["pois"]), "queries": {t: sum(1 for q in ds["queries"] if q["type"] == t) for t in TYPES}}
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
