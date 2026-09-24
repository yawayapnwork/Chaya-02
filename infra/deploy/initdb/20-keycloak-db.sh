#!/bin/sh
# Runs once, when the Postgres volume is first initialised: Keycloak's own database and role, separate from the
# application's. Keycloak never gets access to the chaya database, and the API never to keycloak's.
# (No `set -eu`: the entrypoint sources this file; see 10-app-role.sh.)
: "${KEYCLOAK_DB_PASSWORD:?set KEYCLOAK_DB_PASSWORD}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v kc_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE ROLE keycloak LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'kc_password';
CREATE DATABASE keycloak OWNER keycloak;
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
SQL
