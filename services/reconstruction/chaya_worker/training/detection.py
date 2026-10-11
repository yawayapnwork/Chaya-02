"""Fine-tunes Grounding DINO on a dataset's object boxes.

    python -m chaya_worker.training.detection --dataset <locked dataset> --base-model IDEA-Research/grounding-dino-tiny \\
        --base-revision <sha> --out <dir> [--epochs 12] [--lr 1e-5] [--batch 2] [--seed 0] [--allow-cpu]

The text prompt is the dataset's object classes joined the way Grounding DINO expects ("chair. sofa. exit sign."), and
each box's class label is its class's position in that prompt (transformers builds the phrase-to-token map from the
prompt's separators). Boxes go in as normalised (cx, cy, w, h). Only approved, non-rejected annotations are used
(chaya_worker.datasets.validate refuses anything else). The best epoch by validation AP@0.5 is kept; the test split is
never read. Output: safetensors weights, the processor, and chaya_model_card.json, whose label_set is the prompt to run
the checkpoint with (Settings.object_detection_prompt).

Evaluate with benchmarks/b6_semantic_models/run.py --det-model <out> on the test split, next to the stock baseline.
"""

from __future__ import annotations

import argparse
import json
import time
from collections import defaultdict
from pathlib import Path

import numpy as np

from ..datasets.jsonl import read_annotations
from .common import TrainingRefused, environment, prepare, seed_everything, write_card


def prompt_for(classes: list[str]) -> str:
    """Pure: Grounding DINO's prompt format for a class list."""
    for c in classes:
        if "." in c or not c.strip():
            raise ValueError(f"class name {c!r} cannot be a Grounding DINO phrase (empty or contains '.')")
    return ". ".join(c.strip().lower() for c in classes) + "."


def target_for(boxes_xyxy: list[tuple[float, float, float, float]], class_names: list[str], classes: list[str], width: int,
               height: int) -> dict[str, np.ndarray]:
    """Pure: one image's training target: class indices into `classes` and boxes as normalised cx, cy, w, h."""
    out = np.zeros((len(boxes_xyxy), 4), dtype=np.float32)
    for i, (x0, y0, x1, y1) in enumerate(boxes_xyxy):
        x0, x1 = max(0.0, x0), min(float(width), x1)
        y0, y1 = max(0.0, y0), min(float(height), y1)
        out[i] = [(x0 + x1) / 2 / width, (y0 + y1) / 2 / height, (x1 - x0) / width, (y1 - y0) / height]
    return {"class_labels": np.array([classes.index(c) for c in class_names], dtype=np.int64), "boxes": out}


def train(args) -> dict:
    prepared = prepare(args.dataset, base_model=args.base_model, accept_base_license=args.accept_base_license,
                       allow_cpu=args.allow_cpu, need_masks=False)
    seed_everything(args.seed)
    import torch
    from PIL import Image
    from transformers import AutoModelForZeroShotObjectDetection, AutoProcessor

    from ..evaluation import average_precision, match_image
    from ..grounding_dino import post_process
    from ..model_loading import resolved_revision

    root = prepared.dataset_root
    classes = list(prepared.manifest["object_classes"])
    prompt = prompt_for(classes)
    by_image = defaultdict(list)
    for a in read_annotations(root / "annotations.jsonl"):
        by_image[a.image_id].append(a)
    revision = args.base_revision or None
    processor = AutoProcessor.from_pretrained(args.processor or args.base_model, revision=None if args.processor else revision)
    model = AutoModelForZeroShotObjectDetection.from_pretrained(args.base_model, revision=revision,
                                                                use_safetensors=not args.allow_pickle)
    base_revision = resolved_revision(model) or revision
    model.to(prepared.device)
    opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=args.lr, weight_decay=1e-4)
    rng = np.random.default_rng(args.seed)

    def load(rec):
        with Image.open(root / "images" / rec.relative_path) as im:
            rgb = np.asarray(im.convert("RGB"))
        anns = by_image[rec.image_id]
        target = target_for([a.bounding_box.to_xyxy(rec.width, rec.height) for a in anns], [a.object_class for a in anns],
                            classes, rec.width, rec.height)
        return rgb, target

    history, best, steps = [], (-1.0, None), 0
    started = time.perf_counter()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    loss = torch.tensor(float("nan"))
    for epoch in range(args.epochs):
        model.train()
        order = rng.permutation(len(prepared.train))
        for i in range(0, len(order), args.batch):
            items = [load(prepared.train[j]) for j in order[i:i + args.batch]]
            inputs = processor(images=[x[0] for x in items], text=[prompt] * len(items), return_tensors="pt", padding=True)
            labels = [{"class_labels": torch.as_tensor(t["class_labels"]).to(prepared.device),
                       "boxes": torch.as_tensor(t["boxes"]).to(prepared.device)} for _, t in items]
            loss = model(**{k: v.to(prepared.device) for k, v in inputs.items()}, labels=labels).loss
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 0.1)
            opt.step()
            steps += 1
            if args.max_steps and steps >= args.max_steps:
                break
        model.eval()
        scored, n_gt = defaultdict(list), defaultdict(int)
        with torch.no_grad():
            for rec in prepared.val:
                rgb, t = load(rec)
                inputs = processor(images=rgb, text=prompt, return_tensors="pt").to(prepared.device)
                res = post_process(processor, model(**inputs), inputs["input_ids"], box_threshold=0.05, text_threshold=0.25,
                                   target_size=rgb.shape[:2])
                labels_out = res["text_labels"] if "text_labels" in res else res["labels"]
                for ci, c in enumerate(classes):
                    gts = [b for b, k in zip(t["boxes"], t["class_labels"], strict=True) if k == ci]
                    gts_xyxy = [((cx - w / 2) * rec.width, (cy - h / 2) * rec.height, (cx + w / 2) * rec.width,
                                 (cy + h / 2) * rec.height) for cx, cy, w, h in gts]
                    preds = [(tuple(float(v) for v in b.tolist()), float(s)) for b, s, lab in
                             zip(res["boxes"], res["scores"], labels_out, strict=True) if str(lab).strip() == c.lower()]
                    n_gt[c] += len(gts_xyxy)
                    scored[c].extend(match_image(preds, gts_xyxy))
        aps = [average_precision(scored[c], n_gt[c]) for c in classes if n_gt[c]]
        val_map = float(np.mean(aps)) if aps else float("nan")
        history.append({"epoch": epoch, "steps": steps, "val_map50": None if np.isnan(val_map) else round(val_map, 5),
                        "last_loss": round(float(loss.item()), 5)})
        score = -1.0 if np.isnan(val_map) else val_map
        if score > best[0] or best[1] is None:
            best = (score, epoch)
            model.save_pretrained(out, safe_serialization=True)
            processor.save_pretrained(out)
        if args.max_steps and steps >= args.max_steps:
            break
    training = {"seed": args.seed, "steps": steps, "epochs_run": len(history), "best_epoch": best[1], "lr": args.lr,
                "batch": args.batch, "prompt": prompt, "optimizer": "AdamW(weight_decay=1e-4), grad clip 0.1",
                "selection": "best validation mAP@0.5", "elapsed_seconds": round(time.perf_counter() - started, 1),
                "environment": environment(prepared.device), "smoke_test_only": bool(args.allow_cpu)}
    return write_card(out, task="zero-shot-object-detection", base_model=args.base_model, base_revision=base_revision,
                      prepared=prepared, training=training, metrics={"val_history": history}, label_set=classes,
                      accept_base_license=args.accept_base_license)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dataset", type=Path, required=True)
    ap.add_argument("--base-model", required=True)
    ap.add_argument("--base-revision", default="")
    ap.add_argument("--processor", default="", help="load the processor from here instead (tests: a random tiny model)")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--max-steps", type=int, default=0)
    ap.add_argument("--lr", type=float, default=1e-5)
    ap.add_argument("--batch", type=int, default=2)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--accept-base-license", choices=["research-only"])
    ap.add_argument("--allow-cpu", action="store_true", help="smoke tests of the harness only")
    ap.add_argument("--allow-pickle", action="store_true")
    args = ap.parse_args(argv)
    try:
        card = train(args)
    except TrainingRefused as exc:
        print(f"training refused: {exc}")
        return 2
    print(json.dumps({"out": str(args.out), "best_epoch": card["training"]["best_epoch"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
