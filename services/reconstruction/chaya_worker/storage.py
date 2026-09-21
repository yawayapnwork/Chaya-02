"""Object storage access. Stages never touch storage; only the orchestrator does."""

from __future__ import annotations

import shutil
from pathlib import Path
from typing import Protocol

from .settings import Settings


class StorageError(Exception):
    pass


class ObjectStorage(Protocol):
    def download(self, bucket: str, key: str, dest: Path) -> int: ...

    def upload(self, bucket: str, key: str, src: Path, content_type: str) -> None: ...

    def delete(self, bucket: str, key: str) -> None: ...


class S3Storage:
    """S3-compatible storage (MinIO), path-style addressing."""

    def __init__(self, settings: Settings) -> None:
        import boto3
        from botocore.config import Config

        self._s3 = boto3.client(
            "s3", endpoint_url=settings.s3_endpoint, region_name=settings.s3_region,
            aws_access_key_id=settings.s3_access_key, aws_secret_access_key=settings.s3_secret_key,
            config=Config(signature_version="s3v4", s3={"addressing_style": "path"}, retries={"max_attempts": 5, "mode": "standard"},
                          # MinIO and other S3-compatible stores do not all accept the newer default checksums
                          request_checksum_calculation="when_required", response_checksum_validation="when_required"))

    def download(self, bucket: str, key: str, dest: Path) -> int:
        try:
            dest.parent.mkdir(parents=True, exist_ok=True)
            self._s3.download_file(bucket, key, str(dest))
            return dest.stat().st_size
        except Exception as exc:  # noqa: BLE001 - boto raises many types
            raise StorageError(f"download of {bucket}/{key} failed: {exc}") from exc

    def upload(self, bucket: str, key: str, src: Path, content_type: str) -> None:
        try:
            self._s3.upload_file(str(src), bucket, key, ExtraArgs={"ContentType": content_type})
        except Exception as exc:  # noqa: BLE001
            raise StorageError(f"upload to {bucket}/{key} failed: {exc}") from exc

    def delete(self, bucket: str, key: str) -> None:
        try:
            self._s3.delete_object(Bucket=bucket, Key=key)
        except Exception as exc:  # noqa: BLE001
            raise StorageError(f"delete of {bucket}/{key} failed: {exc}") from exc


class LocalStorage:
    """A directory tree standing in for buckets: root/<bucket>/<key>. For tests and local development."""

    def __init__(self, root: Path) -> None:
        self.root = Path(root)

    def _path(self, bucket: str, key: str) -> Path:
        path = (self.root / bucket / key).resolve()
        if self.root.resolve() not in path.parents:
            raise StorageError(f"key escapes the storage root: {key}")
        return path

    def download(self, bucket: str, key: str, dest: Path) -> int:
        src = self._path(bucket, key)
        if not src.is_file():
            raise StorageError(f"{bucket}/{key} does not exist")
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dest)
        return dest.stat().st_size

    def upload(self, bucket: str, key: str, src: Path, content_type: str) -> None:
        dest = self._path(bucket, key)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dest)

    def delete(self, bucket: str, key: str) -> None:
        self._path(bucket, key).unlink(missing_ok=True)

    def exists(self, bucket: str, key: str) -> bool:
        return self._path(bucket, key).is_file()

    def keys(self, bucket: str) -> list[str]:
        base = self.root / bucket
        return sorted(p.relative_to(base).as_posix() for p in base.rglob("*") if p.is_file()) if base.exists() else []
