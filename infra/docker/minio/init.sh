#!/bin/sh
# One-shot MinIO bootstrap (docker compose service `minio-init`). Idempotent: safe to run on every `up`.
#
# The root credentials are used ONLY here. Every client gets its own account with the least privilege it needs:
#   chaya-api     raw + derived: read, write, delete (validation deletes rejected uploads; the pipeline purges PII)
#   chaya-worker  raw: read only; derived: read + write, no delete
#   chaya-backup  raw + derived: read only (scripts/backup)
# Buckets are private: anonymous access is explicitly removed.
set -eu

: "${MINIO_ROOT_USER:?}" "${MINIO_ROOT_PASSWORD:?}" "${S3_BUCKET_RAW:?}" "${S3_BUCKET_DERIVED:?}"
: "${S3_ACCESS_KEY:?}" "${S3_SECRET_KEY:?}" "${S3_WORKER_ACCESS_KEY:?}" "${S3_WORKER_SECRET_KEY:?}"
: "${S3_BACKUP_ACCESS_KEY:?}" "${S3_BACKUP_SECRET_KEY:?}"

mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null

for b in "$S3_BUCKET_RAW" "$S3_BUCKET_DERIVED"; do
  mc mb --ignore-existing "local/$b"
  mc anonymous set none "local/$b"
done

RAW="arn:aws:s3:::$S3_BUCKET_RAW"
DER="arn:aws:s3:::$S3_BUCKET_DERIVED"
dir=$(mktemp -d)

cat > "$dir/chaya-api.json" <<EOF
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation","s3:ListBucketMultipartUploads"],"Resource":["$RAW","$DER"]},
 {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload","s3:ListMultipartUploadParts"],
  "Resource":["$RAW/*","$DER/*"]}]}
EOF

cat > "$dir/chaya-worker.json" <<EOF
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation"],"Resource":["$RAW","$DER"]},
 {"Effect":"Allow","Action":["s3:GetObject"],"Resource":["$RAW/*"]},
 {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:AbortMultipartUpload","s3:ListMultipartUploadParts"],"Resource":["$DER/*"]}]}
EOF

cat > "$dir/chaya-backup.json" <<EOF
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation"],"Resource":["$RAW","$DER"]},
 {"Effect":"Allow","Action":["s3:GetObject"],"Resource":["$RAW/*","$DER/*"]}]}
EOF

account() { # name access-key secret-key
  mc admin policy create local "$1" "$dir/$1.json"
  mc admin user add local "$2" "$3"
  # attach fails if already attached; the policy content was refreshed above either way
  mc admin policy attach local "$1" --user "$2" >/dev/null 2>&1 || true
}
account chaya-api "$S3_ACCESS_KEY" "$S3_SECRET_KEY"
account chaya-worker "$S3_WORKER_ACCESS_KEY" "$S3_WORKER_SECRET_KEY"
account chaya-backup "$S3_BACKUP_ACCESS_KEY" "$S3_BACKUP_SECRET_KEY"

rm -rf "$dir"
echo "minio-init: buckets private, service accounts chaya-api / chaya-worker / chaya-backup ready"
