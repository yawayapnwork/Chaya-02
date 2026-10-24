"""The GPU-facing probes of chaya_worker.toolchain: the NVIDIA driver as the host reports it, and whether COLMAP was
built with CUDA. Neither ever decides that training can run (only torch enumerating a device does): they tell the
preflight's failure codes apart, and keep a CUDA-less COLMAP from being asked for GPU SIFT."""

from __future__ import annotations

import subprocess

from chaya_worker import toolchain as tc_module
from chaya_worker.toolchain import Toolchain, colmap_banner_has_cuda


def test_the_colmap_banner_says_whether_it_was_built_with_cuda():
    # Both banners seen on 2026-10-24: Ubuntu noble's 3.9.1-2build2 and the CPU test image's 3.8.
    assert not colmap_banner_has_cuda("COLMAP 3.9.1 -- Structure-from-Motion and Multi-View Stereo\n(Commit Unknown on Unknown without CUDA)")
    assert not colmap_banner_has_cuda("COLMAP 3.8 -- Structure-from-Motion and Multi-View Stereo\n(Commit Unknown on Unknown without CUDA)")
    assert colmap_banner_has_cuda("COLMAP 3.9.1 -- Structure-from-Motion and Multi-View Stereo\n(Commit 1234abc on 2024-02-29 with CUDA)")
    assert not colmap_banner_has_cuda("")


def test_no_colmap_means_no_gpu_sift():
    assert Toolchain(env={}, which=lambda _n: None).colmap_has_cuda() is False


class Done:
    def __init__(self, stdout="", returncode=0, stderr=""):
        self.stdout, self.returncode, self.stderr = stdout, returncode, stderr


def test_the_driver_is_read_from_nvidia_smi(monkeypatch):
    monkeypatch.setattr(tc_module.subprocess, "run", lambda argv, **kw: Done("NVIDIA L4, 560.35.03, 23034, 22497\n"))
    d = Toolchain(env={}, which=lambda n: "/usr/bin/nvidia-smi" if n == "nvidia-smi" else None).nvidia_driver()
    assert d["present"] and d["version"] == "560.35.03" and d["source"] == "nvidia-smi"
    assert d["gpus"] == [{"name": "NVIDIA L4", "memory_total_mb": 23034.0, "memory_free_mb": 22497.0}]


def test_a_failing_nvidia_smi_falls_back_to_the_kernel_module_version(monkeypatch, tmp_path):
    proc = tmp_path / "version"
    proc.write_text("NVRM version: NVIDIA UNIX x86_64 Kernel Module  535.183.01  Sun May 12 19:39:15 UTC 2024\n", encoding="utf-8")
    monkeypatch.setattr(tc_module.subprocess, "run", lambda argv, **kw: Done("", 9, "NVIDIA-SMI has failed because it couldn't "
                                                                             "communicate with the NVIDIA driver"))
    d = Toolchain(env={"NVIDIA_DRIVER_VERSION_FILE": str(proc), "NVIDIA_DEVICE_NODE": str(tmp_path / "none")},
                  which=lambda n: "/usr/bin/nvidia-smi" if n == "nvidia-smi" else None).nvidia_driver()
    assert d["present"] and d["version"] == "535.183.01" and d["source"] == str(proc)


def test_a_device_node_alone_means_present_with_an_unknown_version(tmp_path):
    node = tmp_path / "nvidia0"
    node.write_text("", encoding="utf-8")
    d = Toolchain(env={"NVIDIA_DRIVER_VERSION_FILE": str(tmp_path / "missing"), "NVIDIA_DEVICE_NODE": str(node)},
                  which=lambda _n: None).nvidia_driver()
    assert d["present"] and d["version"] is None and d["source"] == "device node"


def test_nothing_nvidia_is_not_present(tmp_path):
    d = Toolchain(env={"NVIDIA_DRIVER_VERSION_FILE": str(tmp_path / "missing"), "NVIDIA_DEVICE_NODE": str(tmp_path / "none")},
                  which=lambda _n: None).nvidia_driver()
    assert d == {"present": False, "version": None, "gpus": [], "source": None, "detail": None}


def test_nvidia_smi_that_cannot_start_is_reported_not_raised(monkeypatch, tmp_path):
    def boom(argv, **kw):
        raise subprocess.TimeoutExpired(argv, 20)

    monkeypatch.setattr(tc_module.subprocess, "run", boom)
    d = Toolchain(env={"NVIDIA_DRIVER_VERSION_FILE": str(tmp_path / "missing"), "NVIDIA_DEVICE_NODE": str(tmp_path / "none")},
                  which=lambda n: "/usr/bin/nvidia-smi" if n == "nvidia-smi" else None).nvidia_driver()
    assert not d["present"] and "nvidia-smi failed" in d["detail"]
