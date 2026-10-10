"""The worker side of the stage artifact contract (packages/contracts/pipeline/stage-artifacts.json).

The control plane hands each stage every earlier successful output (dev.chaya.api PipelineService#inputs) and refuses a
SUCCEEDED report that lacks the stage's requiredOutputs. So a stage that reads a kind nothing upstream publishes, or
publishes under another name than the contract says, breaks a real run only when that run reaches it -- after hours of
GPU work. These tests read what every stage really reads and writes (its source) and check:

  * every kind a stage reads or writes is declared, and every declared kind is really read or written;
  * every plan the control plane creates -- full and incremental, with and without privacy preprocessing, with and
    without navigation baking -- can satisfy every stage's required inputs from the required outputs of the stages
    before it (or the inputs the control plane supplies from outside the run);
  * every stage of every plan is a real implementation on this worker.

The Java side (OpenPipelineContractTest) checks the plans and requiredOutputs against PipelineDefinition.
"""

from __future__ import annotations

import inspect
import json
import re
from itertools import product
from pathlib import Path

import pytest

from chaya_worker.stages import default_registry
from chaya_worker.stages.base import LABELS_FOR_SPLAT
from chaya_worker.stages.unimplemented import PlannedStage

CONTRACT = json.loads((Path(__file__).resolve().parents[4] / "packages" / "contracts" / "pipeline" / "stage-artifacts.json")
                      .read_text(encoding="utf-8"))
STAGES = CONTRACT["stages"]
DEFAULT_CLOUDS = ("SPLAT_MERGED", "SPLAT_CLEAN", "SPLAT")


def _literals(text: str) -> list[str]:
    return re.findall(r'"([A-Z][A-Z0-9_]+)"', text)


def scanned(stage_name: str) -> tuple[set[str], set[str]]:
    """(kinds read, kinds written) by a stage, from its module's source."""
    src = inspect.getsource(inspect.getmodule(type(default_registry()[stage_name])))
    reads: set[str] = set()
    for args in re.findall(r"inputs_of\(([^)]*)\)", src):
        reads |= set(_literals(args))
    for m in re.finditer(r"venue_cloud\(ctx, self\.name(?:, \(([^)]*)\))?\)", src):
        clouds = tuple(_literals(m.group(1))) if m.group(1) else DEFAULT_CLOUDS
        reads |= set(clouds) | {LABELS_FOR_SPLAT[c] for c in clouds}
    if "frame_archives(ctx)" in src:
        reads |= {"FRAME_ARCHIVE_ANON", "FRAME_ARCHIVE_SELECTED"}
    if "privacy_masks.from_inputs(ctx)" in src:
        reads.add("PRIVACY_MASKS")
    if "capture_calibration(ctx)" in src:
        reads.add("RAW_METADATA")
    writes = set(re.findall(r'ArtifactSpec\(\s*"([A-Z][A-Z0-9_]+)"', src))
    if "ArtifactSpec(privacy_masks.KIND" in src:
        writes.add("PRIVACY_MASKS")
    if '"SPLAT_PARTIAL" if partial else "SPLAT"' in src:
        writes |= {"SPLAT", "SPLAT_PARTIAL"}
    return reads, writes


def declared_inputs(stage: str) -> set[str]:
    c = STAGES[stage]
    return {k for group in c["requiredInputs"] for k in group} | set(c["optionalInputs"])


def declared_outputs(stage: str) -> set[str]:
    return set(STAGES[stage]["requiredOutputs"]) | set(STAGES[stage]["optionalOutputs"])


@pytest.mark.parametrize("stage", sorted(STAGES))
def test_a_stage_reads_and_writes_exactly_what_the_contract_declares(stage):
    reads, writes = scanned(stage)
    assert writes == declared_outputs(stage), f"{stage} writes {sorted(writes)}; the contract declares {sorted(declared_outputs(stage))}"
    assert reads == declared_inputs(stage), f"{stage} reads {sorted(reads)}; the contract declares {sorted(declared_inputs(stage))}"


def plans() -> list[tuple[str, list[str]]]:
    out = []
    for kind, privacy, navigation in product(("full", "incremental"), (True, False), (True, False)):
        if kind == "full" and not navigation:
            continue  # a full run always bakes navigation (PipelineDefinition#plan)
        plan = [s for s in CONTRACT["plans"][kind]
                if (privacy or s != "PRIVACY_PREPROCESS") and (navigation or s != "NAVIGATION_BAKING")]
        out.append((f"{kind}{'' if privacy else '-no-privacy'}{'' if navigation else '-no-navigation'}", plan))
    return out


@pytest.mark.parametrize(("name", "plan"), plans(), ids=[n for n, _ in plans()])
def test_every_plan_satisfies_every_stage_from_the_stages_before_it(name, plan):
    available = set(CONTRACT["externalInputs"]["always"])
    if name.startswith("incremental"):
        available |= set(CONTRACT["externalInputs"]["incremental"])
    for stage in plan:
        for group in STAGES[stage]["requiredInputs"]:
            assert available & set(group), f"plan {name}: {stage} needs one of {group}, but nothing before it publishes one"
        available |= set(STAGES[stage]["requiredOutputs"])


def test_every_planned_stage_is_implemented_on_this_worker():
    registry = default_registry()
    for stage in set(CONTRACT["plans"]["full"]) | set(CONTRACT["plans"]["incremental"]):
        assert stage in STAGES, f"{stage} is planned but has no contract entry"
        assert not isinstance(registry[stage], PlannedStage), f"{stage} is planned but not implemented"


def test_privacy_disabled_runs_train_on_the_selected_frames_and_privacy_runs_never_do(tmp_path):
    """The plan without PRIVACY_PREPROCESS has no FRAME_ARCHIVE_ANON: its stages read FRAME_ARCHIVE_SELECTED, which the
    control plane offers in such a run. A privacy-enabled run never falls back to the unblurred archive."""
    import logging

    from chaya_worker.contract import InputFile, StageContext
    from chaya_worker.runner import CommandRunner
    from chaya_worker.settings import Settings
    from chaya_worker.stages.base import frame_archives
    from chaya_worker.toolchain import Toolchain

    def ctx(privacy, kinds):
        inputs = [InputFile({"artifactId": k, "kind": k}, tmp_path / k) for k in kinds]
        return StageContext({"stage": "POSE_ESTIMATION", "privacyEnabled": privacy}, inputs, tmp_path, logging.getLogger("t"),
                            CommandRunner(tmp_path / "o", tmp_path / "e"), Toolchain(env={}), Settings(), None)

    assert [i.kind for i in frame_archives(ctx(False, ["FRAME_ARCHIVE_SELECTED"]))] == ["FRAME_ARCHIVE_SELECTED"]
    assert frame_archives(ctx(True, ["FRAME_ARCHIVE_SELECTED"])) == [], "privacy on: unblurred frames are never used"
    assert [i.kind for i in frame_archives(ctx(False, ["FRAME_ARCHIVE_ANON", "FRAME_ARCHIVE_SELECTED"]))] == ["FRAME_ARCHIVE_ANON"]


REPO = Path(__file__).resolve().parents[4]


def _stages_claimed_by(text: str, pattern: str) -> set[str]:
    m = re.search(pattern, text)
    assert m, f"no WORKER_STAGES found by {pattern}"
    return {s.strip() for s in m.group(1).split(",") if s.strip()}


def test_every_stage_of_every_plan_is_claimed_by_a_shipped_worker():
    """A stage no deployed worker claims stays QUEUED until the run's deadline: the run can never finish. The production
    compose's CPU worker and the GPU worker image (Dockerfile.gpu) must together claim every planned stage, and the CPU
    worker must claim nothing its image cannot run."""
    cpu = _stages_claimed_by((REPO / "infra" / "deploy" / "docker-compose.yml").read_text(encoding="utf-8"),
                             r"WORKER_ID: cpu-worker[\s\S]*?WORKER_STAGES: ([A-Z_,]+)")
    gpu = _stages_claimed_by((REPO / "services" / "reconstruction" / "Dockerfile.gpu").read_text(encoding="utf-8"),
                             r"WORKER_STAGES=([A-Z_,]+)")
    planned = set(CONTRACT["plans"]["full"]) | set(CONTRACT["plans"]["incremental"])
    assert planned - (cpu | gpu) == set(), f"no shipped worker claims {sorted(planned - (cpu | gpu))}"
    needs_gpu_host = {"POSE_ESTIMATION", "SPLAT_RECONSTRUCTION", "SEMANTIC_SEGMENTATION", "GEOMETRIC_CLEANUP", "PLANE_FITTING",
                      "SEMANTIC_INDEXING", "REGION_ALIGNMENT"}  # COLMAP, gsplat/CUDA, transformers, Open3D: not in the CPU image
    assert cpu & needs_gpu_host == set(), f"the CPU worker claims stages its image cannot run: {sorted(cpu & needs_gpu_host)}"
