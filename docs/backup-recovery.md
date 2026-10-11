# Backup and recovery

## What is backed up

| Data | How | Where | Notes |
|---|---|---|---|
| PostgreSQL (all application state: tenancy, captures, jobs, versions, POIs, audit log) | `pg_dump --format=custom`, dated, with `.sha256` | `$BACKUP_DIR/postgres/chaya-<UTC>.dump` | Kept for `BACKUP_KEEP_DAYS` (14). Every dump is checked with `pg_restore --list` when taken. |
| MinIO raw bucket (original captures) | `mc mirror` (additive) through the read-only `chaya-backup` account | `$BACKUP_DIR/minio/chaya-raw/` | Deletions in the live bucket do **not** propagate. Rejected uploads are pruned from the copy using the database. |
| MinIO derived bucket (pipeline outputs) | same, **excluding `*/pii/*`** | `$BACKUP_DIR/minio/chaya-derived/` | PII staging objects are never copied. |
| Keycloak realm configuration | in git (`infra/keycloak/chaya-realm.json`) | — | **Users are not backed up by these scripts.** Export them with `kc.sh export` while Keycloak is stopped, or use an external Keycloak database with its own backups. |

Backups hold raw captures (faces, screens, documents, before any blurring) and the audit log. Treat `$BACKUP_DIR` like
production data: encrypted storage, restricted access, and never inside the repository (`/backups/` is git-ignored).

### Why an additive mirror

Objects are write-once, and the database only ever points at objects that exist. So the database is dumped
**first**, and the mirror runs **after**: every object the dump references already exists when it is copied.

The mirror does **not** propagate deletions. An earlier version used `mc mirror --remove`. Then an accidental or
malicious deletion in the live buckets (the API account can delete) would also have deleted the backup copy at the
next scheduled run, leaving the 14 days of database dumps pointing at objects that no longer existed anywhere. The
application deletes objects in exactly two cases, and both are still honoured:

- **Unblurred frames** (`pii/` staging), purged after privacy preprocessing: excluded from the mirror, so never copied.
- **Rejected uploads** (for example infected files), deleted by validation: `backup.sh` removes their copies from the
  mirror using the `REJECTED` rows in the database, in case a mirror ran while validation was in progress.

What the mirror still does not give you: history. A live object that is **overwritten** with bad content (objects are
write-once, so only a bug or an attacker does this) replaces the good copy at the next run, and the mirror lives on
the same host. For production, add an off-host copy of `$BACKUP_DIR` with its own retention (for example restic or
borg to separate storage), or MinIO bucket versioning with object locking. This is a residual risk until one of them
is in place.

**Erasure requests** (docs/privacy-erasure.md): deletions do not propagate through the mirror, so `backup.sh` applies
erasures to it itself. On every run, it deletes each key and prefix listed in `erasure_object` from
`$BACKUP_DIR/minio/`. Dated database dumps still hold erased rows until they age out (`BACKUP_KEEP_DAYS`, 14 days).
**Restoring a dump older than an erasure brings the erased rows back.** Before restoring, save the list of erasures
(`GET /api/v1/erasures`). After restoring, issue again every erasure that is later than the dump.

## Running backups

```bash
scripts/backup/backup.sh            # database, then objects
scripts/backup/backup.sh postgres   # database only
scripts/backup/restore-drill.sh     # prove the newest backup restores, in isolation (see "Restore drill")
scripts/backup/verify.sh            # lighter check on the live server (see "Verification")
```

Schedule the backup and the drill, for example with cron on the Docker host, daily:
`15 2 * * * cd /srv/chaya && scripts/backup/backup.sh && DRILL_API_IMAGE=<registry>/chaya-api:$(cat .deployed-version) scripts/backup/restore-drill.sh`.
A backup command that exits 0 proves only that files were written. A backup whose restore drill has never passed has
not been shown to work.

The scripts need Docker, the running compose stack, and `.env`. Credentials reach the `mc` container through a
mode-600 temp file, never through the command line.

## Restore drill (`scripts/backup/restore-drill.sh`)

The authoritative check. It restores into an **isolated environment** and touches nothing live except reading the
backup files:

1. compares the dump's SHA-256 with its `.sha256` file;
2. creates a Docker network with `--internal` (no route out, no published ports) and starts a throwaway PostgreSQL
   and MinIO on it, with random credentials and anonymous volumes. The images are the ones the live stack runs, when
   it is running;
3. recreates the roles that own objects in the dump (random passwords), restores the dump with its owners
   (`--exit-on-error`), and mirrors the object backup into fresh buckets. Both are timed: this is recovery-time
   evidence for the current data size;
4. checks that no migration is recorded as failed, prints the schema version next to this checkout's newest migration,
   and prints row counts. Jobs that were RUNNING in the dump are noted: after a real restore the lease enforcer fails
   them as `WORKER_LOST`;
5. checks **every** object the restored database references (accepted raw media, non-PII artifacts) in the restored
   MinIO, by SHA-256 against the value recorded when the object was verified. `DRILL_SAMPLE=<n>` checks a random
   sample instead, for very large stores;
6. if an API image is known (`DRILL_API_IMAGE`, or the live stack's `api` container), boots it on the restored data.
   Flyway validates the restored schema (and applies newer migrations, if the image is newer), and Hibernate validates
   the mapping. Liveness must answer within `DRILL_API_BOOT_TIMEOUT` (300 s). The identity provider is deliberately
   absent, so the API's own health is DOWN for that reason only;
7. removes every container, the network and the volumes, also on failure.

It exits non-zero on any failure and writes a report without secrets to `$BACKUP_DIR/drills/`. `DRILL_DB_ONLY=1`
restores and checks the database only; `deploy.sh` uses that mode with the version being deployed as a migration
rehearsal (DEPLOYMENT.md section 7).

## Verification (`scripts/backup/verify.sh`)

The lighter, older check. It restores into a scratch database **on the live PostgreSQL server** (so it shares that
server's resources and superuser) and checks a sample of objects as files in the mirror directory, not through a
restored object store. It touches no live data. It:

1. compares the dump's SHA-256 with its `.sha256` file;
2. restores the dump into a scratch database, `chaya_restore_check`, with `--exit-on-error`;
3. prints the Flyway schema version and the row counts of the core tables;
4. samples 25 accepted raw media and 25 non-PII artifacts **from the restored database**, and checks that each object
   exists in the mirror with the SHA-256 the database recorded when the object was verified;
5. drops the scratch database.

It exits non-zero if any check fails.

## Recovery procedure

1. **Stop writers:** stop the API and every worker, so that nothing writes during the restore.
2. **Choose and verify:** pick the dump (normally the newest), then run `scripts/backup/restore-drill.sh <dump>`. Do
   not restore a backup that fails the drill. Take the next older one instead.
3. **Restore the database:**
   `scripts/backup/restore.sh postgres <dump> --yes-replace-live-data`
   This terminates connections, recreates the database owned by `POSTGRES_APP_USER`, and restores with the original
   owners.
4. **Restore the objects**, if they were lost:
   `scripts/backup/restore.sh minio --yes-replace-live-data`
   Mirror back with the MinIO root account. Objects that are not in the backup are removed from the bucket. If the
   buckets are intact, skip this step: extra objects are harmless, and missing ones are what step 2 checks. Because
   the mirror is additive, it can also hold objects that are newer than an older dump you restored. They are
   unreferenced and harmless.
5. **Start the API.** Flyway validates the schema version on start-up. Then check `/api/v1/health`: it must be `UP`,
   or `DEGRADED` only for a known reason.
6. **Reconcile:** runs that were `RUNNING` in the dump have lost their workers. The lease enforcer fails them as
   `WORKER_LOST` within `lease-seconds` (5 min), and they can then be retried from the operations dashboard. Uploads
   that were `VALIDATING` are re-validated automatically at start-up.
7. **Record** the restore: which dump, why, and the verification output.

Recovery point: the time of the last successful `backup.sh`. Recovery time: roughly the restore duration of the dump,
plus the object mirror if objects were lost. **Neither has been measured on real data sizes.** Measure both in a
restore drill.

## Moving an existing database to the app role

`infra/docker/postgres/initdb/10-app-role.sh` runs only when the data volume is first created. For an existing volume,
run this once as the superuser (replace the names and password), then set `POSTGRES_APP_USER` and
`POSTGRES_APP_PASSWORD`:

```sql
CREATE ROLE chaya_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '...';
ALTER DATABASE chaya OWNER TO chaya_app;
ALTER SCHEMA public OWNER TO chaya_app;
-- Hand every existing table, sequence and function in public to the app role:
REASSIGN OWNED BY <old_owner> TO chaya_app;   -- only if <old_owner> owns nothing outside this database
REVOKE ALL ON DATABASE chaya FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE chaya TO chaya_app;
```

If the old owner is the bootstrap superuser, `REASSIGN OWNED` is refused for system objects. In that case, alter the
owner of each table and sequence in `public` instead (`ALTER TABLE ... OWNER TO chaya_app`), including
`flyway_schema_history`.

## What has been verified

**2026-10-11, restore drill on real stack data.** `scripts/ci/stack-smoke.sh` brought up the real stack (API image
built from the tree, Postgres, MinIO, ClamAV with signatures, Keycloak), uploaded a video that ClamAV scanned and
accepted, and ran the real worker code through the CPU stages (PII staging purged). Then `backup.sh` took a dump
(268 KB) and the object mirror, and `restore-drill.sh` restored both into an isolated environment: database in 3 s,
objects in 4 s, schema V34 with no failed migration, **19 of 19** referenced objects matched their recorded SHA-256,
and the API image booted on the restored data in 23 s. Nothing was left running. The same drill runs at the end of
every stack smoke in CI.

`scripts/backup/test-restore-drill.sh` (CI: `ops`) proves the drill fails when it should: on a synthetic backup it
passes intact, and fails with the object named when one object is corrupted, when one is missing, and when the dump no
longer matches its `.sha256` (7 checks pass).

Not verified by the drill: production-sized data (the timings above are for a tiny store; measure on the real host),
Keycloak's own database, and off-site copies.

On 2026-09-24 the scripts were run end to end on Windows (Git Bash, Docker Desktop). The target was a throwaway copy
of the development stack: its own compose project, with the schema created by the API's Flyway migrations (v16) and
seeded rows and objects. The following passed:

- `backup.sh` produced a dump and its checksum, and mirrored both buckets. The accepted upload was copied. The
  rejected upload was copied mid-flight, then pruned by the database step. The `pii/` object was never copied.
- After the live object was deleted, the next `backup.sh` **kept** the backup copy.
- `verify.sh` restored the dump into the scratch database, reported schema version 16 and the row counts, and matched
  the sampled object's SHA-256 in the mirror.
- `restore.sh postgres` recreated the live database, with the tables still owned by the app role.
  `restore.sh minio` put the deleted object back.

That run found and fixed four script bugs. Paths containing spaces broke every `docker compose` call. On Git Bash,
`docker` was handed untranslated `/tmp/...` paths. `verify.sh` printed schema version 9 instead of 16 (`max()` over
a text column). A bucket with nothing to back up had no mirror directory, so `restore.sh minio` failed after it had
already overwritten the raw bucket; it now checks every bucket first.

Not verified: the production compose file (`infra/deploy`, `CHAYA_COMPOSE_FILE`), realistic data sizes, and the
recovery time. Measure those in a restore drill on the real host.
