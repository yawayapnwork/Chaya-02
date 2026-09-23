#!/bin/sh
# Runs once, when the Postgres data volume is first initialised (docker-entrypoint-initdb.d).
#
# POSTGRES_USER is the bootstrap superuser and is used for nothing else. The API connects as POSTGRES_APP_USER, which
# owns the database and schema (so Flyway can migrate) but is NOT a superuser: no COPY ... PROGRAM, no file access,
# no session_replication_role to bypass the append-only/immutability triggers, no other databases.
# The only superuser-only step the migrations need, CREATE EXTENSION vector, is done here; the migration's
# CREATE EXTENSION IF NOT EXISTS then finds it present.
#
# Existing volumes are not touched by this script: see docs/backup-recovery.md, "Moving an existing database to the
# app role".
# No `set -eu`: the image's entrypoint *sources* non-executable init scripts, so shell options would leak into it.
# The :? guards and psql's ON_ERROR_STOP give the same fail-fast behaviour.
: "${POSTGRES_APP_USER:?set POSTGRES_APP_USER}" "${POSTGRES_APP_PASSWORD:?set POSTGRES_APP_PASSWORD}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app_user="$POSTGRES_APP_USER" -v app_password="$POSTGRES_APP_PASSWORD" -v db="$POSTGRES_DB" <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';
ALTER DATABASE :"db" OWNER TO :"app_user";
ALTER SCHEMA public OWNER TO :"app_user";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO :"app_user";
SQL
