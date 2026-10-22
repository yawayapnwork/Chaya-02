"""The orchestrator downloads only the inputs a stage declares (chaya_worker.stage_inputs; gaussian-splatting-audit D-4).

The control plane already offers a stage only its declared kinds (PipelineDefinition#INPUTS); these tests give the
orchestrator a work order that offers everything, as an older control plane did, and check what is downloaded, what the
stage sees and what the report names as its inputs, and how a missing required input fails.
"""

from __future__ import annotations

import pytest

from chaya_worker import stage_inputs
from chaya_worker.contract import ArtifactSpec, StageError, StageResult

# Everything SPLAT_RECONSTRUCTION publishes when training completes, and what the stages before it publish.
TRAINING_OUTPUTS = ("SPLAT", "SPLAT_CHECKPOINT", "SPLAT_TRAINING_REPORT", "KEYFRAME_RENDERS", "TRAINING_CAMERAS")
EARLIER_OUTPUTS = ("FRAME_ARCHIVE_ANON", "PRIVACY_MASKS", "PRIVACY_REPORT", "SPARSE_MODEL", "POSES", "FRAME_QUALITY_REPORT")
REQUIRED_OUTPUTS = {"GEOMETRIC_CLEANUP": ["SPLAT_CLEAN", "SEMANTIC_LABELS_CLEAN"], "ARTIFACT_GENERATION": ["KSPLAT", "ARTIFACT_MANIFEST"],
                    "SPLAT_RECONSTRUCTION": ["SPLAT"]}


class Recording:
    """A TEST stage under a real stage's name: records the kinds it was given and publishes that stage's required outputs."""

    def __init__(self, name, fn=None):
        self.name, self.fn, self.seen = name, fn, None

    def run(self, ctx):
        self.seen = sorted(i.kind for i in ctx.inputs)
        if self.fn:
            self.fn(ctx)
        artifacts = []
        for kind in REQUIRED_OUTPUTS.get(self.name, []):
            f = ctx.workdir / kind.lower()
            f.write_bytes(kind.encode())
            artifacts.append(ArtifactSpec(kind, f, kind.lower()))
        return StageResult("SUCCEEDED", {"stage": ctx.stage, "argv": ["x"]}, 0, artifacts)


def offered(harness, tmp_path, *kinds):
    """Work-order inputs of these kinds, each a real stored object. FIXTURE CONTENT: the bytes are not of the kind."""
    refs = []
    for kind in kinds:
        path = tmp_path / f"fixture-{kind.lower()}"
        path.write_bytes(f"fixture {kind}".encode())
        refs.append({**harness.raw_input(kind, path), "containsPii": False})
    return refs


@pytest.fixture()
def downloads(harness):
    """The artifact ids the orchestrator downloads, in order."""
    seen, real = [], harness.storage.download

    def spy(bucket, key, dest):
        seen.append(dest.name[:36])  # the orchestrator names a download <artifactId>-<object name>
        return real(bucket, key, dest)

    harness.storage.download = spy
    return seen


def kinds_of(order, artifact_ids):
    by_id = {r["artifactId"]: r["kind"] for r in order["inputs"]}
    return sorted(by_id[a] for a in artifact_ids)


def test_a_stage_after_training_downloads_only_its_declared_inputs_never_the_checkpoint_or_keyframes(harness, tmp_path, downloads):
    order = harness.order("GEOMETRIC_CLEANUP", offered(harness, tmp_path, *EARLIER_OUTPUTS, *TRAINING_OUTPUTS, "SEMANTIC_LABELS"))
    stage = Recording("GEOMETRIC_CLEANUP")
    report = harness.run(order, registry={"GEOMETRIC_CLEANUP": stage})

    assert report["status"] == "SUCCEEDED", report["errorMessage"]
    assert stage.seen == ["SEMANTIC_LABELS", "SPLAT"]
    assert kinds_of(order, downloads) == ["SEMANTIC_LABELS", "SPLAT"]
    assert kinds_of(order, report["inputArtifactIds"]) == ["SEMANTIC_LABELS", "SPLAT"], "the report names what the stage read"


@pytest.mark.parametrize("stage", sorted(set(stage_inputs.REQUIRED) - {"SPLAT_RECONSTRUCTION"}))
def test_no_stage_but_splat_reconstruction_is_handed_the_training_checkpoint_or_keyframes(stage):
    refs = [{"artifactId": k, "kind": k} for k in TRAINING_OUTPUTS]
    kinds = {r["kind"] for r in stage_inputs.offered(stage, refs)}
    assert not kinds & {"SPLAT_CHECKPOINT", "KEYFRAME_RENDERS", "TRAINING_CAMERAS", "SPLAT_TRAINING_REPORT"}


def test_a_retry_of_splat_reconstruction_still_gets_the_checkpoint_it_resumes_from(harness, tmp_path, downloads):
    order = harness.order("SPLAT_RECONSTRUCTION", offered(harness, tmp_path, *EARLIER_OUTPUTS, "SPLAT_CHECKPOINT"), attempt=2)
    stage = Recording("SPLAT_RECONSTRUCTION")
    report = harness.run(order, registry={"SPLAT_RECONSTRUCTION": stage})

    assert report["status"] == "SUCCEEDED", report["errorMessage"]
    assert stage.seen == ["FRAME_ARCHIVE_ANON", "POSES", "PRIVACY_MASKS", "SPARSE_MODEL", "SPLAT_CHECKPOINT"]
    assert "SPLAT_CHECKPOINT" in kinds_of(order, downloads)


def test_optional_inputs_are_downloaded_when_offered_and_not_required_when_absent(harness, tmp_path):
    with_planes = Recording("ARTIFACT_GENERATION")
    inputs = offered(harness, tmp_path, "SPLAT_CLEAN", "SEMANTIC_LABELS_CLEAN", "PLANE_MODEL", "SPLAT_CHECKPOINT", "GRAVITY_ESTIMATE")
    report = harness.run(harness.order("ARTIFACT_GENERATION", inputs), registry={"ARTIFACT_GENERATION": with_planes})
    assert report["status"] == "SUCCEEDED" and with_planes.seen == ["PLANE_MODEL", "SEMANTIC_LABELS_CLEAN", "SPLAT_CLEAN"]

    without = Recording("ARTIFACT_GENERATION")
    report = harness.run(harness.order("ARTIFACT_GENERATION", offered(harness, tmp_path, "SPLAT_CLEAN")),
                         registry={"ARTIFACT_GENERATION": without})
    assert report["status"] == "SUCCEEDED" and without.seen == ["SPLAT_CLEAN"]


def _first(kind):
    def read(ctx):
        ctx.inputs_of(kind)[0]  # a stage that assumes its input is there
    return read


def test_a_stage_that_crashes_on_a_missing_required_input_fails_as_input_invalid_naming_it(harness, tmp_path):
    stage = Recording("GEOMETRIC_CLEANUP", _first("SEMANTIC_LABELS"))
    order = harness.order("GEOMETRIC_CLEANUP", offered(harness, tmp_path, "SPLAT", "SPLAT_CHECKPOINT", "KEYFRAME_RENDERS"))
    report = harness.run(order, registry={"GEOMETRIC_CLEANUP": stage})

    assert report["status"] == "FAILED" and report["errorCode"] == "INPUT_INVALID" and report["artifacts"] == []
    assert report["errorDetails"] == {"missing_inputs": [["SEMANTIC_LABELS"]], "offered_kinds": ["SPLAT"]}
    assert "SEMANTIC_LABELS" in report["errorMessage"] and "IndexError" in report["errorMessage"]


def test_a_stages_own_structured_code_for_a_missing_input_is_kept(harness, tmp_path):
    def refuse(ctx):
        raise StageError("no labels for the splat being baked", code="NAVMESH_LABELS_UNAVAILABLE")

    report = harness.run(harness.order("NAVIGATION_BAKING", offered(harness, tmp_path, "SPLAT_CLEAN", "PLANE_MODEL")),
                         registry={"NAVIGATION_BAKING": Recording("NAVIGATION_BAKING", refuse)})
    assert report["status"] == "FAILED" and report["errorCode"] == "NAVMESH_LABELS_UNAVAILABLE"


def test_a_crash_with_every_required_input_offered_stays_an_internal_error(harness, tmp_path):
    stage = Recording("GEOMETRIC_CLEANUP", _first("SPLAT_CHECKPOINT"))  # reads a kind it does not declare: a stage bug
    report = harness.run(harness.order("GEOMETRIC_CLEANUP", offered(harness, tmp_path, "SPLAT", "SEMANTIC_LABELS", "SPLAT_CHECKPOINT")),
                         registry={"GEOMETRIC_CLEANUP": stage})
    assert report["status"] == "FAILED" and report["errorCode"] == "INTERNAL_ERROR"


def test_one_kind_of_a_group_of_alternatives_satisfies_it():
    def refs(*kinds):
        return [{"artifactId": k, "kind": k} for k in kinds]

    assert stage_inputs.missing("ARTIFACT_GENERATION", refs("SPLAT_MERGED")) == []
    assert stage_inputs.missing("ARTIFACT_GENERATION", refs("SPLAT_PARTIAL", "PLANE_MODEL")) == [["SPLAT_MERGED", "SPLAT_CLEAN", "SPLAT"]]
    assert stage_inputs.missing("NAVIGATION_BAKING", refs("SPLAT_CLEAN")) == [stage_inputs.LABELS, ["PLANE_MODEL"]]


def test_a_stage_the_contract_does_not_cover_is_offered_everything():
    refs = [{"artifactId": k, "kind": k} for k in TRAINING_OUTPUTS]
    assert stage_inputs.offered("NOT_A_STAGE", refs) == refs and stage_inputs.missing("NOT_A_STAGE", []) == []
