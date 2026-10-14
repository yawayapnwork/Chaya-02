"""Texts whose real CLIP vectors the in-process B3 harness needs (services/api SearchBenchmarkTest).

The harness runs B3 (dataset.json, detected_objects.json, phrasing.json) through the real SemanticSearchService on a
Testcontainers Postgres, with real CLIP vectors instead of a running vision service. This writes the clip_vectors.py
request for every text it embeds: each POI's embedding text (PoiEmbeddingService.embeddingText), each detector label,
each query, its phrasings and misspelling (phrasing.json), plus any extra texts given (e.g. the search texts the query
interpretation derives, which the harness lists when one is missing).

    python benchmarks/b3_semantic_search/inprocess_texts.py <request.json> [extra-texts.txt]
    docker run --rm -v chaya-e2e_vision-cache:/home/vision/.cache -v "<repo>:/repo:ro" -v "<dir>:/out" \\
        chaya-e2e-vision python /repo/benchmarks/b3_semantic_search/clip_vectors.py /out/request.json /out/vectors.json
    python benchmarks/b3_semantic_search/inprocess_texts.py --write-fixture <dir>/vectors.json
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
FIXTURE = REPO / "packages/contracts/fixtures/search/b3-clip-vit-b32-openai.json"


def embedding_text(label: str, category: str | None, tags: list[str]) -> str:
    """PoiEmbeddingService.embeddingText: label, category, tags; stripped, de-duplicated case-insensitively."""
    seen: set[str] = set()
    parts: list[str] = []
    for c in [label, category, *tags]:
        if c is None or not c.strip():
            continue
        s = c.strip()
        if s.lower() not in seen:
            seen.add(s.lower())
            parts.append(s)
    return ", ".join(parts)


def misspell(q: str) -> str | None:
    """phrasing.json's misspelling rule."""
    words = q.split(" ")
    longest = max(words, key=len)  # max keeps the first on a tie
    if len(longest) < 5:
        return None
    i = len(longest) // 2
    words[words.index(longest)] = longest[:i] + longest[i + 1:]
    return " ".join(words)


def derived_queries(ds: dict, phrasing: dict) -> list[str]:
    out = []
    for q in ds["queries"]:
        if q["type"] in phrasing["template_types"]:
            out += [t.replace("{q}", q["q"]) for t in phrasing["templates"]]
        if q["type"] in phrasing["misspelling"]["types"]:
            m = misspell(q["q"])
            if m:
                out.append(m)
    return out


def texts(extra: list[str]) -> list[str]:
    ds = json.loads((HERE / "dataset.json").read_text(encoding="utf-8"))
    det = json.loads((HERE / "detected_objects.json").read_text(encoding="utf-8"))
    phrasing = json.loads((HERE / "phrasing.json").read_text(encoding="utf-8"))
    out = [embedding_text(p["label"], p.get("category"), p.get("tags", [])) for p in ds["pois"] + det["extra_manual_pois"]]
    out += sorted({d["label"] for d in det["detected"]})
    out += [q["q"] for q in ds["queries"] + det["queries"]]
    out += derived_queries(ds, phrasing)
    out += extra
    # The vision service's tokenizer lower-cases; the harness looks texts up lower-cased and stripped.
    return sorted({t.strip().lower() for t in out})


def main() -> None:
    if sys.argv[1] == "--write-fixture":
        vectors = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
        FIXTURE.write_text(json.dumps({
            "provenance": "REAL CLIP ViT-B/32 text-tower vectors (open_clip 'ViT-B-32' + 'openai', the production "
                          "configuration) of every text the in-process B3 harness embeds, computed by "
                          "benchmarks/b3_semantic_search/clip_vectors.py in the services/vision image with the production "
                          "encoder unchanged; request from inprocess_texts.py. Used by SearchBenchmarkTest.",
            "text_model": vectors["text_model"],
            "texts": vectors["texts"],
        }, sort_keys=True), encoding="utf-8")
        print(f"wrote {len(vectors['texts'])} texts to {FIXTURE}")
        return
    extra = Path(sys.argv[2]).read_text(encoding="utf-8").splitlines() if len(sys.argv) > 2 else []
    Path(sys.argv[1]).write_text(json.dumps({"texts": texts([e for e in extra if e.strip()]), "crops": []}), encoding="utf-8")
    print(f"{len(texts(extra))} texts")


if __name__ == "__main__":
    main()
