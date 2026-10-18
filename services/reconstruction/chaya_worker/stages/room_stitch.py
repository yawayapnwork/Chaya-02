"""ROOM_STITCH: combines the registered rooms' Gaussians into one floor (docs/multi-room.md, "Stitching";
chaya_worker.room_stitch).

Needs ROOM_REGISTRATION to have placed every room: ROOM_REGISTRATION_REPORT, its required input, is only published when
it did. The report must describe exactly the clouds this stage is given (artifact id and SHA-256 per room); anything else
is INPUT_INVALID.

  1. Each room's Gaussians are moved into the anchor room's reconstruction frame with the report's `to_output`
     similarity (chaya_worker.region_splice.transform_gaussians: positions s R p + t, orientations R q, log-scales + ln s;
     opacity and colour unchanged: the colours are SH degree 0, which no rotation changes).
  2. Where rooms overlap, each voxel keeps the Gaussians of one room only (the one that observed it most densely; the
     anchor on a tie), so shared doorways and hallways are not doubled.
  3. The overlap is verified again in the output frame (the offset ICP still finds), the seam between what is kept on
     either side is measured, and the colour step across it is reported. A misaligned overlap or a visible seam fails
     the stage with STITCH_REJECTED and nothing is published.

Published:
  * SPLAT_MERGED: the stitched floor, each room one contiguous block (STITCH_REPORT `blocks`), in the anchor room's
    reconstruction frame, so the anchor's calibration applies to it unchanged;
  * SEMANTIC_LABELS_MERGED: the rooms' labels with exactly the same selection, naming the cloud they label. Every room
    must have labels for exactly its cloud (INPUT_INVALID otherwise): NAVIGATION_BAKING refuses to bake without them;
  * STITCH_REPORT: the measurements, the blocks, every input (artifact id, SHA-256) and the output;
  * STITCH_INDEX (`stitch-index.npz`): per room, the indices of its Gaussians that were kept.
"""

from __future__ import annotations

import json
import time

import numpy as np

from ..contract import ArtifactSpec, StageContext, StageError, StageResult
from ..frames import Similarity, require_canonical
from ..ply import read_ply, write_ply
from ..region_splice import transform_gaussians
from ..room_stitch import Piece, StitchRejected, stitch_pieces
from .base import command_record, run_provenance, sha256_file, write_json
from .region_alignment import _json_safe
from .room_registration import room_inputs

STITCH_REJECTED = "STITCH_REJECTED"


class RoomStitch:
    name = "ROOM_STITCH"

    def run(self, ctx: StageContext) -> StageResult:
        s = ctx.settings
        frame = require_canonical(ctx.order, self.name)
        rooms = room_inputs(ctx, self.name, ctx.inputs_of("ROOM_CLOUD"), ctx.inputs_of("ROOM_LABELS"))
        reports = ctx.inputs_of("ROOM_REGISTRATION_REPORT")
        if not reports:
            raise StageError("no ROOM_REGISTRATION_REPORT was provided by ROOM_REGISTRATION", code="INPUT_INVALID")
        registration = json.loads(reports[0].path.read_text(encoding="utf-8"))
        placed = {r["space_id"]: r for r in registration.get("rooms", [])}
        problems = []
        for r in rooms:
            entry = placed.get(r.id)
            if entry is None or not entry.get("registered") or "to_output" not in entry:
                problems.append(f"{r.name}: not registered")
            elif (entry.get("cloud") or {}).get("sha256") != sha256_file(r.cloud.path):
                problems.append(f"{r.name}: the registration was computed on another cloud")
        if registration.get("anchor") != rooms[0].id or set(placed) != {r.id for r in rooms}:
            problems.append("the registration report is for another set of rooms or another anchor")
        if problems:
            raise StageError("ROOM_REGISTRATION_REPORT does not describe these rooms: " + "; ".join(problems), code="INPUT_INVALID",
                             details={"problems": problems})

        started = time.monotonic()
        pieces, sources = [], []
        for r in rooms:
            cloud = read_ply(r.cloud.path)
            # The stitched floor is baked with its labels (NAVIGATION_BAKING refuses a cloud without them), so a room without
            # labels for exactly its cloud cannot be stitched: its walls and furniture would vanish from the navmesh.
            if r.labels is None:
                raise StageError(f"room {r.name} has no ROOM_LABELS", code="INPUT_INVALID")
            labels = np.array(json.loads(r.labels.path.read_text(encoding="utf-8"))["labels"], dtype=object)
            if len(labels) != len(cloud):
                raise StageError(f"room {r.name}: {len(labels)} labels for {len(cloud)} Gaussians; they do not describe its cloud",
                                 code="INPUT_INVALID")
            moved = transform_gaussians(cloud, Similarity.from_dict(placed[r.id]["to_output"]))
            pieces.append(Piece(r.id, moved, labels))
            sources.append({**r.ref(), "gaussians": len(cloud)})
        try:
            result = stitch_pieces(pieces, frame.to_canonical(), voxel_m=s.stitch_voxel_m, max_misalignment_m=s.stitch_max_misalignment_m,
                                   max_misalignment_deg=s.stitch_max_misalignment_deg, max_seam_step_m=s.stitch_max_seam_step_m,
                                   seam_band_m=s.stitch_seam_band_m, overlap_radius_m=s.room_overlap_radius_m,
                                   icp_schedule_m=s.room_schedule(), icp_voxel_m=s.room_icp_voxel_m,
                                   colour_warning=s.stitch_colour_warning)
        except StitchRejected as exc:
            raise StageError(f"stitch rejected: {exc}. Nothing was published.", code=STITCH_REJECTED,
                             details=_json_safe({**exc.details, "rooms": sources})) from exc
        elapsed = time.monotonic() - started

        merged_path = write_ply(result.merged, ctx.workdir / "splat-merged.ply")
        merged_ref = {"artifact": "splat-merged.ply", "sha256": sha256_file(merged_path), "gaussians": len(result.merged)}
        index_path = ctx.workdir / "stitch-index.npz"
        kept = {f"kept_{i}": np.flatnonzero(mask) for i, mask in enumerate(result.kept)}
        with open(index_path, "wb") as f:  # a file handle: np.savez would otherwise append .npz to the name
            np.savez_compressed(f, **kept)
        report = _json_safe({
            **result.report,
            "anchor": rooms[0].id, "coordinate_frame_id": frame.id, "source": run_provenance(ctx, frame.id),
            "inputs": {"rooms": sources, "registration_report": {"artifact_id": reports[0].artifact_id,
                                                                 "sha256": sha256_file(reports[0].path)}},
            "output": merged_ref,
            "index": {"artifact": "stitch-index.npz", "sha256": sha256_file(index_path),
                      "arrays": {f"kept_{i}": r.id for i, r in enumerate(rooms)}},
            "timing_seconds": elapsed,
            "appearance": "opacity and SH degree-0 colour of every kept Gaussian are copied unchanged; no colour correction "
                          "is applied across rooms (colour_step reports the difference)",
        })
        labels_path = write_json(ctx.workdir / "semantic-labels-merged.json", {
            "labels": [str(v) for v in result.labels], "source": run_provenance(ctx, frame.id),
            "cloud": {"kind": "SPLAT_MERGED", **merged_ref}})
        report["labels"] = {"stitched": True, "artifact": "semantic-labels-merged.json", "sha256": sha256_file(labels_path)}
        report_path = write_json(ctx.workdir / "stitch-report.json", report)
        artifacts = [ArtifactSpec("SPLAT_MERGED", merged_path, "splat-merged.ply", "application/octet-stream"),
                     ArtifactSpec("SEMANTIC_LABELS_MERGED", labels_path, "semantic-labels-merged.json", "application/json"),
                     ArtifactSpec("STITCH_REPORT", report_path, "stitch-report.json", "application/json"),
                     ArtifactSpec("STITCH_INDEX", index_path, "stitch-index.npz", "application/octet-stream")]
        ctx.logger.info("rooms stitched", extra={"gaussians": len(result.merged), "rooms": len(rooms),
                                                 "duplicate_fraction_after": report["duplicate_fraction_after"]})
        summary = {k: report[k] for k in ("anchor", "blocks", "total_after", "contested_voxels", "duplicates_after",
                                          "duplicate_fraction_after", "colour_seams_visible", "limits", "timing_seconds")}
        summary["pairs"] = [{"pieces": p["pieces"], "offset_m": (p["misalignment"] or {}).get("offset_m"),
                             "rotation_deg": (p["misalignment"] or {}).get("rotation_deg"), "colour_step": p["colour_step"]}
                            for p in report["pairs"]]
        summary["seams"] = [{"pieces": x["pieces"], "median_m": x.get("median_m")} for x in report["seams"]]
        summary["output"], summary["labels"] = merged_ref, report["labels"]
        return StageResult("SUCCEEDED", command_record(ctx, {"stitch": summary}), ctx.runner.last_exit_status(), artifacts)
