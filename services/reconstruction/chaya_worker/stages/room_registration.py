"""ROOM_REGISTRATION: places independently captured rooms of one floor in one coordinate frame (docs/multi-room.md,
chaya_worker.room_registration).

The work order's ``rooms`` lists the room versions being stitched, each with its own calibrated frame and its cloud
(ROOM_CLOUD) and labels (ROOM_LABELS) by artifact id. Exactly one is the **anchor**: the stitched floor is written in the
anchor room's reconstruction frame, so ``coordinateFrame`` (this run's frame) must be the anchor's. Every room must be
canonical (metric and gravity-aligned): registration is rigid in metres, and NOT_CALIBRATED names the rooms that are not.

  1. Each room in its own canonical metres (its calibration). The rooms are metric, so the relative scale is held at 1
     and only gated (chaya_worker.room_registration, "relative scale").
  2. Every pair of rooms is registered: FPFH feature matches (Open3D), RANSAC with a uniqueness test, rigid ICP on the
     overlap, the alignment gates, and the free-space test (one room may not stand where the other capture saw empty
     space above its floor; ROOM_LABELS say where the floor is). Most pairs of a floor do not see each other and come back NO_SHARED_FEATURES.
  3. The accepted pairs form the room graph. A spanning tree from the anchor places the rooms; every other accepted pair
     closes a loop and is checked; the graph is optimised; registrations that contradict the rest are removed.
  4. Every room must end up placed: a room without a consistent registration to the anchor fails the stage with
     REGISTRATION_REJECTED, whose details name it and the pairs that were tried, and nothing is published. A floor is
     never published with a room left out.
  5. A room only ONE chain of registrations places (no loop through it) is UNCONFIRMED: nothing could have caught a wrong
     registration on that chain. Repeated structure produces exactly that: with real FPFH on the synthetic floor, the
     corridor's registration onto room B verified 5 m off along itself (its two doors are 5 m apart and look alike), was
     consistent with everything every capture observed, and in one of eight trials it was the corridor's only accepted
     registration. Unconfirmed rooms are refused (REGISTRATION_REJECTED) unless the work order's
     `stitchOptions.allowUnconfirmedRooms` says the operator accepts them; they are then placed and listed as
     `unconfirmed`. A floor with no loops (rooms that each open onto one corridor, two rooms) can only be stitched that way.

Published: ROOM_REGISTRATION_REPORT, with each room's pose (room canonical -> anchor canonical) and the transform the stitch
applies (room reconstruction -> anchor reconstruction), every pairwise result with its gates, the loop closures and the
pose-graph optimisation.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass

import numpy as np

from .. import room_registration as reg
from ..contract import ArtifactSpec, InputFile, StageContext, StageError, StageResult
from ..frames import CoordinateFrame, NotCalibrated, Similarity, frame_from_order, frame_provenance, require_canonical
from ..ply import read_ply
from ..region_alignment import fpfh_correspondences
from ..similarity_registration import tilt_degrees
from .base import command_record, run_provenance, write_json
from .region_alignment import _json_safe

REGISTRATION_REJECTED = "REGISTRATION_REJECTED"

# The feature matcher. Tests replace it with a stand-in (tests/multiroom_scene.true_matches); the real one needs Open3D.
match_features = fpfh_correspondences


@dataclass
class RoomInput:
    id: str  # the space (room) id
    name: str
    scan_version_id: str
    run_id: str
    anchor: bool
    frame: CoordinateFrame
    cloud: InputFile
    labels: InputFile | None

    def ref(self) -> dict:
        return {"space_id": self.id, "name": self.name, "scan_version_id": self.scan_version_id, "run_id": self.run_id,
                "anchor": self.anchor, "cloud": {"artifact_id": self.cloud.artifact_id, "sha256": self.cloud.ref.get("sha256")},
                "labels": None if self.labels is None else {"artifact_id": self.labels.artifact_id,
                                                            "sha256": self.labels.ref.get("sha256")},
                "coordinate_frame": frame_provenance(self.frame)}


def room_inputs(ctx: StageContext, stage: str, clouds: list[InputFile], labels_inputs: list[InputFile]) -> list[RoomInput]:
    """The work order's rooms, each with its frame and input files (`clouds`: the ROOM_CLOUD inputs, `labels_inputs`: the
    ROOM_LABELS inputs). INPUT_INVALID unless there are at least two distinct rooms, exactly one anchor whose frame is this
    run's, and every room's cloud is provided; NOT_CALIBRATED unless every room's frame is canonical."""
    raw = ctx.order.get("rooms")
    if not isinstance(raw, list) or len(raw) < 2:
        raise StageError(f"{stage} needs at least two rooms on the work order", code="INPUT_INVALID")
    cloud_by_id = {i.artifact_id: i for i in clouds}
    labels_by_id = {i.artifact_id: i for i in labels_inputs}
    rooms, uncalibrated = [], []
    for r in raw:
        frame = frame_from_order(r)
        if frame is None or not frame.canonical:
            uncalibrated.append(r.get("name") or r.get("spaceId"))
            continue
        cloud = cloud_by_id.get(str(r.get("cloudArtifactId")))
        if cloud is None:
            raise StageError(f"room {r.get('name')}: its cloud {r.get('cloudArtifactId')} was not provided as ROOM_CLOUD",
                             code="INPUT_INVALID")
        labels = labels_by_id.get(str(r.get("labelsArtifactId"))) if r.get("labelsArtifactId") else None
        rooms.append(RoomInput(str(r["spaceId"]), str(r.get("name") or r["spaceId"]), str(r.get("scanVersionId")),
                               str(r.get("runId")), bool(r.get("anchor")), frame, cloud, labels))
    if uncalibrated:
        raise NotCalibrated(f"{stage} registers rooms in metres with a known up direction; these rooms have no canonical "
                            f"calibration: {', '.join(map(str, uncalibrated))}. Calibrate each room's reconstruction and start "
                            "the stitch again.", details={"rooms": uncalibrated})
    if len({r.id for r in rooms}) != len(rooms):
        raise StageError(f"{stage}: a room appears twice on the work order", code="INPUT_INVALID")
    anchors = [r for r in rooms if r.anchor]
    if len(anchors) != 1:
        raise StageError(f"{stage} needs exactly one anchor room, got {len(anchors)}", code="INPUT_INVALID")
    own = frame_from_order(ctx.order)
    if own is None or own.id != anchors[0].frame.id:
        raise StageError(f"{stage}: the run's coordinate frame ({None if own is None else own.id}) is not the anchor room's "
                         f"({anchors[0].frame.id}); the stitched floor is written in the anchor's reconstruction frame",
                         code="INPUT_INVALID")
    return [anchors[0]] + [r for r in rooms if not r.anchor]


def _labels(room: RoomInput, n: int):
    """The room's labels when they describe its cloud; None otherwise (the free-space test then finds the floor by height)."""
    if room.labels is None:
        return None
    labels = json.loads(room.labels.path.read_text(encoding="utf-8")).get("labels")
    return np.array(labels, dtype=object) if isinstance(labels, list) and len(labels) == n else None


class RoomRegistration:
    name = "ROOM_REGISTRATION"

    def run(self, ctx: StageContext) -> StageResult:
        s = ctx.settings
        require_canonical(ctx.order, self.name)  # the anchor's frame, which the stitched floor is written in
        rooms = room_inputs(ctx, self.name, ctx.inputs_of("ROOM_CLOUD"), ctx.inputs_of("ROOM_LABELS"))
        if match_features is fpfh_correspondences:
            ctx.toolchain.require(["py:open3d"], stage=self.name)
        started = time.monotonic()
        canonical = {r.id: r.frame.to_canonical().apply(read_ply(r.cloud.path).positions) for r in rooms}
        floors = {r.id: reg.FloorGrid.of(canonical[r.id], _labels(r, len(canonical[r.id]))) for r in rooms}
        gates = s.room_gates()
        schedule = s.room_schedule()

        pairs: list[reg.PairRegistration] = []
        for i, target in enumerate(rooms):
            for j in range(i + 1, len(rooms)):
                source = rooms[j]
                pairs.append(reg.register_pair(source.id, canonical[source.id], target.id, canonical[target.id], gates=gates,
                                               feature_voxel_m=s.room_feature_voxel_m, icp_voxel_m=s.room_icp_voxel_m,
                                               overlap_radius_m=s.room_overlap_radius_m, icp_schedule_m=schedule,
                                               gravity_aligned=True, correspondences=match_features, seed=97 * i + j,
                                               source_floor=floors[source.id], target_floor=floors[target.id]))
        anchor = rooms[0]
        nodes = {r.id: reg.room_node(r.id, canonical[r.id], fixed=r.anchor, pose=Similarity.identity() if r.anchor else None,
                                     floor=floors[r.id]) for r in rooms}
        edges = [reg.Edge(p.target, p.source, p.transform, weight=max(p.metrics.confidence, 1e-3), id=f"{p.source}->{p.target}")
                 for p in pairs if p.accepted]
        graph = reg.solve_room_graph(nodes, edges, gates=gates, gravity_aligned=False)

        unregistered = dict(graph.unregistered)
        allow_unconfirmed = bool((ctx.order.get("stitchOptions") or {}).get("allowUnconfirmedRooms", False))
        if not allow_unconfirmed:
            for room_id, confirmed in graph.confirmed.items():
                if not confirmed:
                    unregistered[room_id] = ("only one chain of registrations places it: no loop confirms it, so a wrong "
                                             "registration on that chain could not be detected. Capture an overlap with "
                                             "another room, or start the stitch accepting unconfirmed rooms")
        anchor_to_canonical = anchor.frame.to_canonical()
        placed = {}
        for r in rooms:
            pose = graph.poses.get(r.id)
            if pose is None or r.id in unregistered:  # not placed, or placed but refused (unconfirmed)
                continue
            tilt = tilt_degrees(pose.rotation)
            if tilt > gates.alignment.max_tilt_deg:
                unregistered[r.id] = (f"its placement tilts it {tilt:.2f} degrees against the anchor's vertical (limit "
                                      f"{gates.alignment.max_tilt_deg}); its calibration's up direction and the anchor's disagree")
                continue
            to_output = anchor_to_canonical.inverse().compose(pose).compose(r.frame.to_canonical())
            placed[r.id] = {"pose_canonical": pose.to_dict(), "tilt_deg": tilt, "to_output": to_output.to_dict(),
                            "loop_confirmed": graph.confirmed.get(r.id, False)}

        names = {r.id: r.name for r in rooms}
        report = _json_safe({
            "method": reg.METHOD, "status": "ACCEPTED" if not unregistered else "REJECTED",
            "anchor": anchor.id, "rooms": [{**r.ref(), "registered": r.id in placed, "reason": unregistered.get(r.id),
                                            **placed.get(r.id, {})} for r in rooms],
            "registered": [r.id for r in rooms if r.id in placed], "unregistered": unregistered,
            "allow_unconfirmed_rooms": allow_unconfirmed,
            # placed rooms only one chain of registrations places: no loop closure could have caught a wrong one
            "unconfirmed": [r.id for r in rooms if r.id in placed and not placed[r.id]["loop_confirmed"]],
            "pairs": [{**p.as_dict(), "source_name": names[p.source], "target_name": names[p.target]} for p in pairs],
            "graph": {"edges": graph.edges, "loop_closures": graph.loop_closures, "optimisation": graph.optimisation},
            "outcomes": reg.OUTCOMES,
            "scale_policy": "every room is metric through its own calibration; relative scale is held at 1 and gated",
            "output_frame": {"description": "the anchor room's reconstruction frame; to_output maps a room's reconstruction "
                                            "coordinates into it", **frame_provenance(anchor.frame)},
            "timing_seconds": time.monotonic() - started,
            "source": run_provenance(ctx, anchor.frame.id),
        })
        ctx.logger.info("rooms registered", extra={"registered": report["registered"], "unregistered": sorted(unregistered),
                                                   "pairs_accepted": sum(p.accepted for p in pairs), "pairs": len(pairs)})
        if unregistered:
            raise StageError(f"{len(unregistered)} of {len(rooms)} rooms could not be registered: "
                             + "; ".join(f"{names.get(k, k)}: {v}" for k, v in unregistered.items())
                             + ". Nothing was stitched or published.", code=REGISTRATION_REJECTED, details=report)
        path = write_json(ctx.workdir / "room-registration-report.json", report)
        summary = {k: report[k] for k in ("method", "status", "anchor", "registered", "unregistered", "unconfirmed",
                                          "allow_unconfirmed_rooms")}
        summary["pairs"] = [{k: p[k] for k in ("source", "target", "status", "outcome", "failed_gates")} for p in report["pairs"]]
        summary["loop_closures"] = report["graph"]["loop_closures"]
        return StageResult("SUCCEEDED", command_record(ctx, {"registration": summary}), ctx.runner.last_exit_status(),
                           [ArtifactSpec("ROOM_REGISTRATION_REPORT", path, "room-registration-report.json", "application/json")])
