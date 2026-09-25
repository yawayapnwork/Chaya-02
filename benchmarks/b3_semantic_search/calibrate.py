"""Chooses the semantic-search relevance threshold on a CALIBRATION venue and reports it, unchanged, on the held-out
TEST venue (docs/BENCHMARKS.md B3, "Relevance threshold").

Why: CLIP text embeddings put almost any two short phrases at cosine ~0.8, so the raw similarity of the best match
cannot tell "the venue has this" from "the venue has nothing like this" (on the test set, correct answers go as low
as 0.804 while nonsense queries reach 0.868). A score relative to the venue may: a real match stands out from the
venue's other POIs, a nonsense query is about equally (dis)similar to all of them.

Candidate scores for a result with cosine s, over the venue's POIs with mean m and standard deviation d:
    raw      s
    margin   s - m
    z        (s - m) / d
A result is kept when its score >= threshold; a query with nothing kept returns no results.

Objective (calibration set only): balanced accuracy =
    0.5 * (answerable queries whose top kept result is correct) + 0.5 * (zero-result queries left empty)
The threshold is the midpoint of the best-scoring interval. The test set is evaluated once, with that threshold.

    python benchmarks/b3_semantic_search/calibrate.py --vision http://127.0.0.1:18090
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmarks.common.results import Metric, Run, sha256_file  # noqa: E402

HERE = Path(__file__).resolve().parent
SCORES = ("raw", "margin", "z")


def dedup_ci(items: list[str]) -> list[str]:
    seen, out = set(), []
    for x in items:
        if x.lower() not in seen:
            seen.add(x.lower())
            out.append(x)
    return out


def poi_text(p: dict) -> str:  # mirrors PoiEmbeddingService.embeddingText
    return ", ".join(dedup_ci([x.strip() for x in [p["label"], p.get("category") or "", *p["tags"]] if x and x.strip()]))


def embed(vision: str, text: str) -> np.ndarray:
    r = requests.post(vision + "/v1/embed-text", json={"text": text}, timeout=60)
    r.raise_for_status()
    v = np.asarray(r.json()["embedding"], dtype=np.float64)
    return v / np.linalg.norm(v)


def score_matrix(vision: str, ds: dict) -> tuple[np.ndarray, list[str]]:
    pois = np.stack([embed(vision, poi_text(p)) for p in ds["pois"]])
    queries = np.stack([embed(vision, q["q"].strip().lower()) for q in ds["queries"]])
    return queries @ pois.T, [p["label"] for p in ds["pois"]]


def transform(sims: np.ndarray, kind: str) -> np.ndarray:
    m = sims.mean(axis=1, keepdims=True)
    d = sims.std(axis=1, keepdims=True)  # population std, as PostgreSQL stddev_pop
    return {"raw": sims, "margin": sims - m, "z": (sims - m) / d}[kind]


def evaluate(sims: np.ndarray, labels: list[str], ds: dict, kind: str, tau: float) -> dict:
    sc = transform(sims, kind)
    ans_ok = ans_n = zero_ok = zero_n = kept_wrong_zero = 0
    by_type: dict[str, list[int]] = {}
    for i, q in enumerate(ds["queries"]):
        order = np.argsort(-sims[i], kind="stable")          # ranking stays cosine order
        kept = [j for j in order if sc[i, j] >= tau]
        if q["expected"]:
            ans_n += 1
            ok = bool(kept) and labels[kept[0]] in q["expected"]
            ans_ok += ok
            by_type.setdefault(q["type"], []).append(int(ok))
        else:
            zero_n += 1
            zero_ok += not kept
            kept_wrong_zero += bool(kept)
    return {"answerable_top1_kept": ans_ok / ans_n, "zero_rejected": zero_ok / zero_n,
            "balanced": 0.5 * (ans_ok / ans_n + zero_ok / zero_n),
            "by_type_top1": {t: sum(v) / len(v) for t, v in by_type.items()}}


def choose(sims: np.ndarray, labels: list[str], ds: dict, kind: str) -> tuple[float, dict]:
    top = np.sort(np.unique(transform(sims, kind)))
    cands = np.concatenate([[top[0] - 1e-6], (top[:-1] + top[1:]) / 2, [top[-1] + 1e-6]])
    results = [(t, evaluate(sims, labels, ds, kind, t)["balanced"]) for t in cands]
    best = max(r[1] for r in results)
    best_ts = [t for t, b in results if b == best]
    # Midpoint of the widest run of best thresholds, rather than its edge.
    tau = float((min(best_ts) + max(best_ts)) / 2)
    return tau, evaluate(sims, labels, ds, kind, tau)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--vision", default="http://127.0.0.1:18090")
    a = ap.parse_args()
    cal = json.loads((HERE / "calibration.json").read_text(encoding="utf-8"))
    test = json.loads((HERE / "dataset.json").read_text(encoding="utf-8"))
    model = requests.get(a.vision + "/health/ready", timeout=30).json()["model"]
    cal_s, cal_l = score_matrix(a.vision, cal)
    test_s, test_l = score_matrix(a.vision, test)

    run = Run("b3_relevance_calibration", "Semantic search relevance threshold: chosen on calibration, reported on test")
    run.environment = {"vision_model": model}
    run.inputs = {"calibration": {"sha256": sha256_file(HERE / "calibration.json"), "pois": len(cal["pois"]), "queries": len(cal["queries"])},
                  "test": {"sha256": sha256_file(HERE / "dataset.json"), "pois": len(test["pois"]), "queries": len(test["queries"])}}
    run.add(Metric("calibration venue", "author-constructed hospital floor, 30 POIs / 45 queries", "", "assumption", "calibration",
                   "calibration.json", note="different venue type from the test set; the test set is never used to choose"))
    chosen = {}
    for kind in SCORES:
        tau, cal_r = choose(cal_s, cal_l, cal, kind)
        test_r = evaluate(test_s, test_l, test, kind, tau)
        chosen[kind] = {"threshold": tau, "calibration": cal_r, "test": test_r}
        run.add(Metric("threshold chosen on calibration", round(tau, 4), kind, "measured", f"score = {kind}", "calibrate.choose"))
        for split, r in (("calibration", cal_r), ("test (held out)", test_r)):
            c = f"score = {kind} | {split}"
            run.add(Metric("answerable queries still answered correctly (top-1 kept)", round(r["answerable_top1_kept"], 4), "fraction",
                           "measured", c, "offline, same model and cosine as pgvector"),
                    Metric("zero-result queries left empty", round(r["zero_rejected"], 4), "fraction", "measured", c, "offline"),
                    Metric("balanced accuracy", round(r["balanced"], 4), "fraction", "measured", c, "offline"))
    # Unfiltered reference on the test set (what the API did before).
    base = evaluate(test_s, test_l, test, "raw", -1.0)
    run.add(Metric("answerable top-1 / zero-result empty, no threshold", f"{base['answerable_top1_kept']:.4f} / {base['zero_rejected']:.4f}",
                   "fraction", "measured", "no filter | test (held out)", "offline"))
    run.details["chosen"] = chosen
    print(json.dumps(chosen, indent=2))
    print(f"wrote {run.write()}")


if __name__ == "__main__":
    main()
