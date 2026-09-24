#!/bin/sh
# One-shot MinIO bootstrap (docker compose service `minio-init`). Idempotent: safe to run on every `up`.
#
# The root credentials are used ONLY here. Every client gets its own account with the least privilege it needs:
#   chaya-api     raw + derived: read, write, delete (validation deletes rejected uploads; the pipeline purges PII)
#   chaya-worker  raw: read only; derived: read + write, no delete. For workers that run the stages up to and
#                 including PRIVACY_PREPROCESS (the only stages that read unblurred media).
#   chaya-recon   (optional, when S3_RECON_ACCESS_KEY is set) derived: read + write, no delete; NO raw bucket and
#                 NO pii/ staging objects. For workers that run only stages after privacy (GPU hosts, DEPLOYMENT.md):
#                 they never hold a credential that can read unblurred captures, whatever the control plane hands out.
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

# The explicit Deny wins over the Allow: pii/ staging (unblurred frames written before privacy preprocessing, key
# layout in PipelineService.prefix) can be neither read nor written with this account.
cat > "$dir/chaya-recon.json" <<EOF
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation"],"Resource":["$DER"]},
 {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:AbortMultipartUpload","s3:ListMultipartUploadParts"],"Resource":["$DER/*"]},
 {"Effect":"Deny","Action":["s3:GetObject","s3:PutObject"],"Resource":["$DER/*/pii/*"]}]}
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
if [ -n "${S3_RECON_ACCESS_KEY:-}" ]; then
  : "${S3_RECON_SECRET_KEY:?S3_RECON_SECRET_KEY is required when S3_RECON_ACCESS_KEY is set}"
  account chaya-recon "$S3_RECON_ACCESS_KEY" "$S3_RECON_SECRET_KEY"
fi

rm -rf "$dir"
echo "minio-init: buckets private, service accounts chaya-api / chaya-worker / chaya-backup${S3_RECON_ACCESS_KEY:+ / chaya-recon} ready"
