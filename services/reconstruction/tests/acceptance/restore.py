"""Puts back the objects the acceptance suite corrupted on purpose (test_04_viewer), after the browser check has seen them.

    python -m tests.acceptance.restore /out/.restore.json      (inside the runner image; ACC_S3_* from the environment)
"""

from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

from .stack import Config, s3_client, sha


def main(path: str) -> int:
    p = Path(path)
    if not p.exists():
        return 0
    cfg = Config.from_env()
    s3 = s3_client(cfg, cfg.s3_root_key, cfg.s3_root_secret)
    for item in json.loads(p.read_text(encoding="utf-8")):
        body = base64.b64decode(item["body"])
        assert sha(body) == item["sha256"]
        s3.put_object(Bucket=item["bucket"], Key=item["key"], Body=body, ContentType="application/octet-stream")
        back = s3.get_object(Bucket=item["bucket"], Key=item["key"])["Body"].read()
        print(f"restored {item['key']}: sha256 {'matches' if sha(back) == item['sha256'] else 'DOES NOT MATCH'}")
    p.unlink()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
