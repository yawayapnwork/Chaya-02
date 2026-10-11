"""Fine-tunes a semantic segmentation model on Chaya's own label set (floor, wall, furniture, clutter, stairs).

    python -m chaya_worker.training.segmentation --dataset <locked dataset> --base-model <hub id or dir> \\
        --base-revision <sha> --out <dir> [--epochs 20] [--lr 6e-5] [--batch 4] [--image-size 512] [--seed 0] \\
        [--accept-base-license research-only] [--allow-cpu]

The classification head is replaced by one with the five runtime buckets (a dataset ``ramp`` trains as ``floor``,
chaya_worker.semantic_classes.RUNTIME_BUCKET_OF; mask value 255 is ignored). The model's id2label is then the buckets
themselves, so SEMANTIC_SEGMENTATION maps it with ``class_mapping`` = ``canonical`` -- no keyword or table in between.
The best epoch by validation mIoU is kept; the test split is never read. Output: safetensors weights, the image
processor, and chaya_model_card.json.

Evaluate the result with benchmarks/b6_semantic_models/run.py --seg-model <out> on the dataset's test split, next to the
stock baseline on the same lock.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np

from ..semantic_classes import BUCKETS, RUNTIME_BUCKET_OF
from .common import TrainingRefused, environment, prepare, seed_everything, write_card

IGNORE = 255


def label_lut(dataset_classes: list[str]) -> np.ndarray:
    """Pure: dataset mask value -> training label (index into BUCKETS); unknown classes and 255 -> IGNORE."""
    lut = np.full(256, IGNORE, dtype=np.int64)
    for value, name in enumerate(dataset_classes):
        bucket = RUNTIME_BUCKET_OF.get(name)
        if bucket is not None:
            lut[value] = BUCKETS.index(bucket)
    return lut


def _load(root: Path, rec, lut: np.ndarray, size: int):
    from PIL import Image

    with Image.open(root / "images" / rec.relative_path) as im:
        rgb = np.asarray(im.convert("RGB").resize((size, size), Image.BILINEAR))
    with Image.open(root / "masks" / rec.semantic_mask) as m:
        mask = lut[np.asarray(m.resize((size, size), Image.NEAREST))]
    return rgb, mask


def train(args) -> dict:
    prepared = prepare(args.dataset, base_model=args.base_model, accept_base_license=args.accept_base_license,
                       allow_cpu=args.allow_cpu, need_masks=True)
    seed_everything(args.seed)
    import torch
    from transformers import AutoImageProcessor, AutoModelForSemanticSegmentation

    from ..evaluation import confusion, mean_iou
    from ..model_loading import resolved_revision

    classes = list(prepared.manifest["semantic_classes"])
    lut = label_lut(classes)
    id2label = dict(enumerate(BUCKETS))
    revision = args.base_revision or None
    processor = AutoImageProcessor.from_pretrained(args.base_model, revision=revision)
    model = AutoModelForSemanticSegmentation.from_pretrained(
        args.base_model, revision=revision, num_labels=len(BUCKETS), id2label=id2label,
        label2id={v: k for k, v in id2label.items()}, ignore_mismatched_sizes=True, use_safetensors=not args.allow_pickle)
    model.config.semantic_loss_ignore_index = IGNORE
    base_revision = resolved_revision(model) or revision
    model.to(prepared.device)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    rng = np.random.default_rng(args.seed)
    root = prepared.dataset_root

    def batches(records, shuffle):
        order = rng.permutation(len(records)) if shuffle else np.arange(len(records))
        for i in range(0, len(order), args.batch):
            items = [_load(root, records[j], lut, args.image_size) for j in order[i:i + args.batch]]
            pix = processor(images=[x[0] for x in items], return_tensors="pt", do_resize=False)["pixel_values"]
            yield pix.to(prepared.device), torch.as_tensor(np.stack([x[1] for x in items])).to(prepared.device)

    history, best, steps = [], (-1.0, None), 0
    started = time.perf_counter()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    for epoch in range(args.epochs):
        model.train()
        for pix, labels in batches(prepared.train, shuffle=True):
            loss = model(pixel_values=pix, labels=labels).loss
            opt.zero_grad()
            loss.backward()
            opt.step()
            steps += 1
            if args.max_steps and steps >= args.max_steps:
                break
        model.eval()
        conf = np.zeros((len(BUCKETS), len(BUCKETS)), dtype=np.int64)
        with torch.no_grad():
            for pix, labels in batches(prepared.val, shuffle=False):
                logits = model(pixel_values=pix).logits
                logits = torch.nn.functional.interpolate(logits, size=labels.shape[-2:], mode="bilinear", align_corners=False)
                conf += confusion(labels.cpu().numpy(), logits.argmax(1).cpu().numpy(), len(BUCKETS), IGNORE)
        val_miou = mean_iou(conf)
        history.append({"epoch": epoch, "steps": steps, "val_miou": None if np.isnan(val_miou) else round(val_miou, 5),
                        "last_loss": round(float(loss.item()), 5)})
        score = -1.0 if np.isnan(val_miou) else val_miou
        if score > best[0] or best[1] is None:
            best = (score, epoch)
            model.save_pretrained(out, safe_serialization=True)
            processor.save_pretrained(out)
        if args.max_steps and steps >= args.max_steps:
            break
    training = {"seed": args.seed, "steps": steps, "epochs_run": len(history), "best_epoch": best[1], "lr": args.lr,
                "batch": args.batch, "image_size": args.image_size, "optimizer": "AdamW(weight_decay=0.01)",
                "loss": "cross-entropy, ignore_index 255", "selection": "best validation mIoU",
                "elapsed_seconds": round(time.perf_counter() - started, 1), "environment": environment(prepared.device),
                "smoke_test_only": bool(args.allow_cpu)}
    return write_card(out, task="semantic-segmentation", base_model=args.base_model, base_revision=base_revision,
                      prepared=prepared, training=training, metrics={"val_history": history}, label_set=list(BUCKETS),
                      accept_base_license=args.accept_base_license)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dataset", type=Path, required=True)
    ap.add_argument("--base-model", required=True)
    ap.add_argument("--base-revision", default="")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--max-steps", type=int, default=0)
    ap.add_argument("--lr", type=float, default=6e-5)
    ap.add_argument("--batch", type=int, default=4)
    ap.add_argument("--image-size", type=int, default=512)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--accept-base-license", choices=["research-only"])
    ap.add_argument("--allow-cpu", action="store_true", help="smoke tests of the harness only")
    ap.add_argument("--allow-pickle", action="store_true", help="accept pickle-format base weights (review the source first)")
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
