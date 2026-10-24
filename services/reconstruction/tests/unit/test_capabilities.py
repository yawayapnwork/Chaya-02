"""A worker offers (claims) only the stages it can execute (chaya_worker.capabilities).

The control plane counts a stage as served when a live worker offers it, so a worker that offered a stage it cannot run
made that stage look served and burnt its jobs' retries on DEPENDENCY_UNAVAILABLE. These tests pin the stage sets of the
shipped images against the toolchains those images really have.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from chaya_worker import capabilities, recast
from chaya_worker.capabilities import StageOffer, assess, stage_requirements
from chaya_worker.orchestrator import Orchestrator
from chaya_worker.settings import Settings
from chaya_worker.stages import STAGE_ORDER
from chaya_worker.toolchain import Toolchain, ToolStatus

ROOT = Path(__file__).resolve().parents[2]
REPO = ROOT.parents[1]


class FakeToolchain(Toolchain):
    """An environment by what it has: `have` holds requirement names (ffmpeg, colmap, py:open3d, model:..., exe:...).
    `gpu_stack`: CUDA torch and compiled gsplat are installed (the GPU image). `gpu`: "ok" (a usable CUDA device) or
    "no-device" (nothing NVIDIA visible, e.g. started without --gpus)."""

    def __init__(self, have: set[str], gpu: str = "no-device", gpu_stack: bool | None = None) -> None:
        super().__init__(env={}, which=lambda _n: None)
        self.have, self.gpu = have, gpu
        self.gpu_stack = gpu == "ok" if gpu_stack is None else gpu_stack

    def status(self, requirement: str) -> ToolStatus:
        return ToolStatus(requirement, requirement in self.have, detail=None if requirement in self.have else f"{requirement} missing")

    def module(self, name):
        return ToolStatus(name, self.gpu_stack or name in ("numpy", "scipy", "cv2"))

    def module_version(self, name):
        return {"torch": "2.7.1+cu126", "gsplat": "1.5.3"}.get(name, "1.0")

    def torch_build(self):
        return {"version": "2.7.1+cu126", "cuda": "12.6", "error": None}

    def cuda(self):
        return ToolStatus("cuda", self.gpu == "ok", detail=None if self.gpu == "ok" else "torch is installed but reports no CUDA device")

    def cuda_devices(self):
        return [{"index": 0, "name": "L4", "compute_capability": "8.9", "compute_capability_value": 8.9}] if self.gpu == "ok" else []

    def cuda_memory(self, index):
        return {"free_bytes": 20 * 2**30, "total_bytes": 24 * 2**30}

    def gsplat_backend(self, *, allow_jit):
        return ToolStatus("gsplat-cuda-backend", self.gpu_stack, version="prebuilt")

    def colmap(self):
        return self.status("colmap")

    def glomap(self):
        return ToolStatus("glomap", False)

    def nvidia_driver(self):
        if self.gpu == "no-device":
            return {"present": False, "version": None, "gpus": [], "source": None, "detail": None}
        return {"present": True, "version": "560.35.03", "gpus": [], "source": "nvidia-smi", "detail": None}


def _env_stages(text: str, pattern: str) -> tuple[str, ...]:
    return tuple(re.search(pattern, text).group(1).split(","))


CPU_DEPLOYED = _env_stages((REPO / "infra/deploy/docker-compose.yml").read_text(encoding="utf-8"), r"WORKER_STAGES: ([A-Z_,]+)")
GPU_IMAGE = _env_stages((ROOT / "Dockerfile.gpu").read_text(encoding="utf-8"), r"WORKER_STAGES=([A-Z_,]+)")
SETTINGS = Settings()
MODELS = {f"model:{SETTINGS.semantic_segmentation_model}", f"model:{SETTINGS.grounding_dino_model}"}
# What each image really contains (services/reconstruction/Dockerfile and Dockerfile.gpu).
CPU_IMAGE_TOOLS = {"ffmpeg", recast.TOOL_REQUIREMENT}
GPU_IMAGE_TOOLS = {"colmap", "py:torch", "py:transformers", "py:open_clip", "py:PIL", "py:open3d"}


def _settings(stages=(), **kw) -> Settings:
    return Settings(stages=tuple(stages), **kw)


def test_the_cpu_image_offers_exactly_its_deployed_stages_even_when_configured_for_all():
    tc = FakeToolchain(CPU_IMAGE_TOOLS, gpu="no-device")
    deployed = assess(_settings(CPU_DEPLOYED), tc)
    assert deployed.offered == list(CPU_DEPLOYED) and deployed.refused == {}
    everything = assess(_settings(), tc)  # WORKER_STAGES empty: it used to claim every stage
    assert set(everything.offered) == set(CPU_DEPLOYED)
    assert "SPLAT_RECONSTRUCTION" in everything.refused and "POSE_ESTIMATION" in everything.refused
    # The CPU image has no torch or gsplat at all: that, not a missing GPU, is the reason it gives.
    assert {r["code"] for r in everything.refused["SPLAT_RECONSTRUCTION"]} >= {"PACKAGE_MISSING", "TOOL_MISSING"}


def test_a_gpu_worker_with_its_gpu_and_weights_offers_its_whole_configured_set():
    a = assess(_settings(GPU_IMAGE), FakeToolchain(GPU_IMAGE_TOOLS | MODELS, gpu="ok"))
    assert a.offered == list(GPU_IMAGE) and a.refused == {}
    assert a.splat_preflight["ok"] and a.splat_preflight["selected_device"]["name"] == "L4"


def test_a_gpu_image_started_without_its_gpu_does_not_claim_splat_reconstruction_but_still_serves_the_rest():
    a = assess(_settings(GPU_IMAGE), FakeToolchain(GPU_IMAGE_TOOLS | MODELS, gpu="no-device", gpu_stack=True))
    assert "SPLAT_RECONSTRUCTION" not in a.offered
    assert {r["code"] for r in a.refused["SPLAT_RECONSTRUCTION"]} >= {"GPU_NOT_PRESENT"}
    assert "POSE_ESTIMATION" in a.offered and "GEOMETRIC_CLEANUP" in a.offered  # CPU COLMAP and Open3D need no GPU


def test_semantic_stages_are_not_offered_until_their_pinned_weights_are_in_the_model_volume():
    a = assess(_settings(GPU_IMAGE), FakeToolchain(GPU_IMAGE_TOOLS, gpu="ok"))
    assert {"SEMANTIC_SEGMENTATION", "SEMANTIC_INDEXING"} <= set(a.refused)
    assert all(r["code"] == "DEPENDENCY_UNAVAILABLE" and r["missing"].startswith("model:") for r in a.refused["SEMANTIC_SEGMENTATION"])
    assert "SPLAT_RECONSTRUCTION" in a.offered


def test_every_stage_has_requirements_and_they_cover_what_its_source_requires():
    """The table is kept in step with the stages: every literal requirement a stage's source passes to
    toolchain.require(...) is in its row (model ids are f-strings and checked by the test above)."""
    table = stage_requirements(SETTINGS)
    assert set(table) == set(STAGE_ORDER)
    stage_dir = ROOT / "chaya_worker" / "stages"
    for path in stage_dir.glob("*.py"):
        text = path.read_text(encoding="utf-8")
        name = re.search(r'name = "([A-Z_]+)"', text)
        if not name or name.group(1) not in table:
            continue
        for call in re.findall(r"toolchain\.require\(\[([^\]]*)\]", text):
            for literal in re.findall(r'"([^"{}]+)"', call):
                assert literal in table[name.group(1)], f"{name.group(1)} requires {literal!r} but capabilities does not know it"


class CountingPlane:
    def __init__(self) -> None:
        self.claims: list[list[str]] = []

    def claim(self, stages, worker_id):
        self.claims.append(list(stages))
        return None


def test_a_worker_that_can_run_none_of_its_stages_claims_nothing_and_does_not_report_itself_alive(tmp_path):
    plane, alive = CountingPlane(), []
    settings = _settings(("SPLAT_RECONSTRUCTION",), workdir=tmp_path)
    worker = Orchestrator(plane, None, settings, toolchain=FakeToolchain(set(), gpu="no-device"), on_alive=lambda: alive.append(1))
    assert worker.run_once() is False
    assert plane.claims == [] and alive == [], "no claim poll: the stage must not look served, and liveness stays unrefreshed"


def test_the_claim_offers_only_capable_stages(tmp_path):
    plane = CountingPlane()
    settings = _settings(("INPUT_VALIDATION", "SPLAT_RECONSTRUCTION", "NAVIGATION_BAKING"), workdir=tmp_path)
    Orchestrator(plane, None, settings, toolchain=FakeToolchain(CPU_IMAGE_TOOLS, gpu="no-device")).run_once()
    assert plane.claims == [["INPUT_VALIDATION", "NAVIGATION_BAKING"]]


def test_the_diagnostic_mode_offers_every_configured_stage(tmp_path):
    plane = CountingPlane()
    settings = _settings(("SPLAT_RECONSTRUCTION",), workdir=tmp_path, offer_unavailable_stages=True)
    Orchestrator(plane, None, settings, toolchain=FakeToolchain(set(), gpu="no-device")).run_once()
    assert plane.claims == [["SPLAT_RECONSTRUCTION"]]


def test_the_offer_is_reassessed_after_the_recheck_interval():
    now = [0.0]
    toolchains = [FakeToolchain(GPU_IMAGE_TOOLS, gpu="ok"), FakeToolchain(GPU_IMAGE_TOOLS | MODELS, gpu="ok")]
    offer = StageOffer(_settings(("SEMANTIC_SEGMENTATION",), capability_recheck_seconds=300), toolchain_factory=lambda: toolchains.pop(0),
                       clock=lambda: now[0])
    assert offer.stages() == []  # weights not fetched yet
    now[0] = 299
    assert offer.stages() == [], "not re-checked before the interval"
    now[0] = 300
    assert offer.stages() == ["SEMANTIC_SEGMENTATION"], "picked up once the weights are in the volume"


def test_the_capabilities_command_reports_and_exits_nonzero_when_a_configured_stage_is_refused(monkeypatch, capsys):
    monkeypatch.setattr(capabilities, "Toolchain", lambda: FakeToolchain(CPU_IMAGE_TOOLS | {"colmap"}, gpu="no-device", gpu_stack=True))
    monkeypatch.setenv("WORKER_STAGES", "INPUT_VALIDATION,SPLAT_RECONSTRUCTION")
    assert capabilities.main([]) == 1
    out = capsys.readouterr().out
    assert '"offered": [\n    "INPUT_VALIDATION"\n  ]' in out and "GPU_NOT_PRESENT" in out
    monkeypatch.setenv("WORKER_STAGES", "INPUT_VALIDATION")
    assert capabilities.main([]) == 0


@pytest.mark.parametrize("stage", ["GEOMETRIC_CLEANUP", "PLANE_FITTING", "REGION_ALIGNMENT", "ROOM_REGISTRATION"])
def test_open3d_stages_need_open3d(stage):
    assert stage in assess(_settings((stage,)), FakeToolchain({"py:open3d"})).offered
    assert stage in assess(_settings((stage,)), FakeToolchain(set())).refused
