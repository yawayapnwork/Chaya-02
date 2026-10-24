"""Build step of Dockerfile.gpu: records the versions the image was built with (gpu-lock.json), and fails the build when
the image cannot train.

The build host normally has no GPU, and a GPU is never passed into `docker build`. So the problems that only say "this
build container sees no usable GPU" are allowed here (BUILD_HOST_GPU_PROBLEMS); every other check must pass. That includes
gsplat's compiled CUDA extension loading against the installed torch, and COLMAP being installed. A worker started from
the image later compares its running versions with this lock (chaya_worker.splat_preflight, PACKAGE_VERSION_DRIFT).

The lock also records what the preflight does not gate: the OS packages the runtime stage installed (dpkg) and whether
the optional GLOMAP is present (it is not installed in this image; POSE_ESTIMATION uses COLMAP's mapper).

usage: python write_lock.py <lock.json> <cuda arch list>
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

from chaya_worker.splat_preflight import environment_report
from chaya_worker.toolchain import Toolchain

# A build container has no GPU: no device node and no driver (GPU_NOT_PRESENT), or, on a GPU build host, a driver that
# the container cannot use (CUDA_UNAVAILABLE). Neither says anything about the image.
BUILD_HOST_GPU_PROBLEMS = {"GPU_NOT_PRESENT", "CUDA_UNAVAILABLE"}
DPKG_PACKAGES = ("python3", "colmap", "libgl1", "libgomp1")


def dpkg_versions(packages=DPKG_PACKAGES, run=subprocess.run) -> dict[str, str | None]:
    out = {}
    for name in packages:
        res = run(["dpkg-query", "-W", "-f=${Version}", name], capture_output=True, text=True)
        out[name] = res.stdout.strip() if res.returncode == 0 and res.stdout.strip() else None
    return out


def main() -> int:
    out, arch_list = Path(sys.argv[1]), sys.argv[2]
    toolchain = Toolchain()
    report = environment_report(toolchain, min_compute_capability=7.0, allow_jit=False)
    blocking = [p for p in report["problems"] if p["code"] not in BUILD_HOST_GPU_PROBLEMS]
    print(json.dumps(report, indent=2, default=str))
    if blocking:
        print("the image cannot train: " + "; ".join(f"{p['code']}: {p['message']}" for p in blocking), file=sys.stderr)
        return 1
    v = report["versions"]
    lock = {k: v.get(k) for k in ("python", "torch", "torch_cuda", "gsplat", "gsplat_backend", "numpy", "scipy", "cv2", "colmap")}
    lock["cuda_arch_list"] = arch_list
    glomap = toolchain.glomap()
    lock["glomap"] = glomap.version if glomap.available else None
    lock["dpkg"] = dpkg_versions()
    missing = [k for k, ver in lock["dpkg"].items() if ver is None]
    if missing:
        print(f"the image is missing OS packages it needs: {missing}", file=sys.stderr)
        return 1
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out}: {lock}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
