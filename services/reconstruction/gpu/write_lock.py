"""Build step of Dockerfile.gpu: records the versions the image was built with (gpu-lock.json), and fails the build when
the image cannot train.

The build host normally has no GPU. So CUDA_UNAVAILABLE is the one preflight problem allowed here: every other check
must pass. That includes gsplat's compiled CUDA extension loading against the installed torch. A worker started from
the image later compares its running versions with this lock (chaya_worker.splat_preflight, PACKAGE_VERSION_DRIFT).

usage: python write_lock.py <lock.json> <cuda arch list>
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from chaya_worker.splat_preflight import environment_report
from chaya_worker.toolchain import Toolchain


def main() -> int:
    out, arch_list = Path(sys.argv[1]), sys.argv[2]
    report = environment_report(Toolchain(), min_compute_capability=7.0, allow_jit=False)
    blocking = [p for p in report["problems"] if p["code"] != "CUDA_UNAVAILABLE"]
    print(json.dumps(report, indent=2, default=str))
    if blocking:
        print("the image cannot train: " + "; ".join(p["message"] for p in blocking), file=sys.stderr)
        return 1
    v = report["versions"]
    lock = {k: v.get(k) for k in ("python", "torch", "torch_cuda", "gsplat", "gsplat_backend", "numpy", "scipy", "cv2", "colmap")}
    lock["cuda_arch_list"] = arch_list
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out}: {lock}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
