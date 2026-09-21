"""Integration: the S3 storage adapter against a real MinIO. Skipped unless CHAYA_IT_S3_* are set:

    docker run -d -p 9000:9000 -e MINIO_ROOT_USER=itaccess -e MINIO_ROOT_PASSWORD=itsecret123 quay.io/minio/minio server /data
    CHAYA_IT_S3_ENDPOINT=http://localhost:9000 CHAYA_IT_S3_ACCESS_KEY=itaccess CHAYA_IT_S3_SECRET_KEY=itsecret123 pytest -m integration
"""

from __future__ import annotations

import hashlib
import os
import uuid

import pytest

from chaya_worker.settings import Settings
from chaya_worker.storage import S3Storage, StorageError

pytestmark = pytest.mark.integration

ENDPOINT = os.environ.get("CHAYA_IT_S3_ENDPOINT")
needs_minio = pytest.mark.skipif(not ENDPOINT, reason="CHAYA_IT_S3_ENDPOINT/ACCESS_KEY/SECRET_KEY are not set (no MinIO to test against)")


@pytest.fixture()
def bucket_and_storage():
    import boto3
    from botocore.config import Config

    settings = Settings(s3_endpoint=ENDPOINT, s3_access_key=os.environ["CHAYA_IT_S3_ACCESS_KEY"], s3_secret_key=os.environ["CHAYA_IT_S3_SECRET_KEY"])
    raw = boto3.client("s3", endpoint_url=ENDPOINT, aws_access_key_id=settings.s3_access_key, aws_secret_access_key=settings.s3_secret_key,
                       region_name="us-east-1", config=Config(s3={"addressing_style": "path"}))
    bucket = f"it-{uuid.uuid4().hex[:12]}"
    raw.create_bucket(Bucket=bucket)
    yield bucket, S3Storage(settings), raw
    for obj in raw.list_objects_v2(Bucket=bucket).get("Contents", []):
        raw.delete_object(Bucket=bucket, Key=obj["Key"])
    raw.delete_bucket(Bucket=bucket)


@needs_minio
def test_upload_download_delete_round_trip_against_real_minio(bucket_and_storage, tmp_path):
    bucket, storage, raw = bucket_and_storage
    src = tmp_path / "frames.tar"
    src.write_bytes(os.urandom(3 * 1024 * 1024 + 17))
    key = "org/o/venue/v/scan/s/run/r/FFMPEG_PREPROCESS/attempt-1/pii/frames.tar"
    storage.upload(bucket, key, src, "application/x-tar")

    head = raw.head_object(Bucket=bucket, Key=key)
    assert head["ContentLength"] == src.stat().st_size and head["ContentType"] == "application/x-tar"
    dest = tmp_path / "back.tar"
    assert storage.download(bucket, key, dest) == src.stat().st_size
    assert hashlib.sha256(dest.read_bytes()).hexdigest() == hashlib.sha256(src.read_bytes()).hexdigest()

    storage.delete(bucket, key)
    with pytest.raises(StorageError):
        storage.download(bucket, key, tmp_path / "gone.tar")


@needs_minio
def test_missing_bucket_is_a_storage_error_not_a_crash(bucket_and_storage, tmp_path):
    _, storage, _ = bucket_and_storage
    with pytest.raises(StorageError):
        storage.upload("bucket-that-does-not-exist", "k", tmp_path / "nothing", "text/plain")
