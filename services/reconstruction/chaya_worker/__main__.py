"""Worker entry point: `python -m chaya_worker [--once]`."""

from __future__ import annotations

import argparse
import logging
import signal
import time

from . import __version__
from .api_client import ApiError, HttpControlPlane
from .logging_json import configure
from .orchestrator import Orchestrator
from .settings import Settings
from .storage import S3Storage
from .toolchain import Toolchain

log = logging.getLogger("chaya_worker")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Chaya 02 reconstruction worker")
    parser.add_argument("--once", action="store_true", help="process at most one job, then exit")
    parser.add_argument("--drain", action="store_true", help="process jobs until none is queued, then exit")
    args = parser.parse_args(argv)

    settings = Settings.from_env()
    configure(settings.log_level)
    settings.require_service_config()
    toolchain = Toolchain()
    log.info("worker starting", extra={"version": __version__, "worker_id": settings.worker_id,
                                       "ffmpeg": toolchain.ffmpeg().as_dict(), "colmap": toolchain.colmap().as_dict(),
                                       "glomap": toolchain.glomap().as_dict(), "cuda": toolchain.cuda().as_dict()})
    orchestrator = Orchestrator(HttpControlPlane(settings), S3Storage(settings), settings, toolchain=toolchain)

    stopping = False

    def stop(*_: object) -> None:
        nonlocal stopping
        stopping = True
        log.info("stop requested; finishing the current job")

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    while not stopping:
        try:
            worked = orchestrator.run_once()
        except ApiError as exc:
            log.error("control plane call failed", extra={"error": str(exc)})
            worked = False
        if args.once or (args.drain and not worked):
            break
        if not worked:
            time.sleep(settings.poll_interval)


if __name__ == "__main__":
    main()
