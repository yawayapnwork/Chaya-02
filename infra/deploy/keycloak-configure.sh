#!/usr/bin/env bash
# Idempotent post-start Keycloak configuration for this environment. The realm file (infra/keycloak/chaya-realm.json)
# is shared with development and carries localhost URLs; this sets the production values, every deploy.
#
# Runs kcadm INSIDE the Keycloak container with credentials it already has in its environment, so no secret is passed
# on any command line from the host.
#
# Credentials (DEPLOYMENT.md "First-time host setup"): it signs in as the chaya-deploy service account, a confidential
# client of the chaya realm that can manage only that realm's clients and settings (never the master realm). The first
# deploy creates that account with the bootstrap admin (KEYCLOAK_ADMIN), so the bootstrap admin can then be deleted, as
# the setup instructions say. Before 2026-10-17 this signed in as the bootstrap admin every time: once an operator had
# deleted it, every later deploy failed here, and so did its automatic rollback, which runs the same step.
set -euo pipefail
cd "$(dirname "$0")"
ENV_FILE=${ENV_FILE:-./.env}
# Parsed as Compose parses it, never executed (scripts/backup/env-file.sh).
# shellcheck source=../../scripts/backup/env-file.sh
. ./scripts/backup/env-file.sh
load_env_file "$ENV_FILE"
# A function, not a command string, so an env-file path with spaces stays one argument (as in deploy.sh).
compose() { docker compose --env-file "$ENV_FILE" -f docker-compose.yml "$@"; }
APP="https://${CHAYA_APP_DOMAIN:?}"

compose exec -T -e APP="$APP" keycloak sh -s <<'IN_CONTAINER'
set -eu
K=/opt/keycloak/bin/kcadm.sh
C=/tmp/kcadm.config
trap 'rm -f "$C"' EXIT
: "${CHAYA_DEPLOY_CLIENT_SECRET:?CHAYA_DEPLOY_CLIENT_SECRET is not set in the keycloak container (.env, DEPLOYMENT.md)}"

# Signed in AND able to read the realm's clients: an account whose roles or service account were removed counts as
# not signed in, so the bootstrap admin (while it exists) repairs it below.
deploy_signin() {
  $K config credentials --server http://localhost:8080 --realm chaya --client chaya-deploy \
    --secret "$CHAYA_DEPLOY_CLIENT_SECRET" --config "$C" > /dev/null 2>&1 &&
    $K get clients -r chaya -q clientId=chaya-web --fields id --config "$C" > /dev/null 2>&1
}
client_json() {
  cat <<JSON
{"clientId":"chaya-deploy","name":"Deploy automation (infra/deploy/keycloak-configure.sh)","enabled":true,
 "publicClient":false,"serviceAccountsEnabled":true,"standardFlowEnabled":false,"implicitFlowEnabled":false,
 "directAccessGrantsEnabled":false,"secret":"$CHAYA_DEPLOY_CLIENT_SECRET"}
JSON
}

if deploy_signin; then
  echo "keycloak: signed in as the chaya-deploy service account"
elif $K config credentials --server http://localhost:8080 --realm master \
     --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" --config "$C" > /dev/null 2>&1; then
  # First deploy (or the deploy secret was changed in .env): create or repair chaya-deploy with the bootstrap admin.
  # The client JSON goes through stdin, so the secret is not on kcadm's command line. --merge: without it, `update -f`
  # replaces the whole client with the file's fields.
  id=$($K get clients -r chaya -q clientId=chaya-deploy --fields id --format csv --noquotes --config "$C")
  if [ -z "$id" ]; then
    client_json | $K create clients -r chaya --config "$C" -f - > /dev/null 2>&1
    echo "keycloak: created the chaya-deploy service account"
  else
    client_json | $K update "clients/$id" -r chaya --config "$C" --merge -f -
    echo "keycloak: chaya-deploy updated (secret from CHAYA_DEPLOY_CLIENT_SECRET)"
  fi
  # Only what this script needs, in the chaya realm only.
  $K add-roles -r chaya --config "$C" --uusername service-account-chaya-deploy --cclientid realm-management \
    --rolename view-clients --rolename query-clients --rolename manage-clients --rolename view-realm --rolename manage-realm
  # Continue as chaya-deploy, which proves the next deploy (perhaps without the bootstrap admin) can sign in.
  deploy_signin || { echo "keycloak: chaya-deploy was set up but cannot sign in with CHAYA_DEPLOY_CLIENT_SECRET" >&2; exit 1; }
  echo "keycloak: signed in as the chaya-deploy service account"
else
  echo "keycloak: cannot sign in, neither as chaya-deploy (CHAYA_DEPLOY_CLIENT_SECRET) nor as the bootstrap admin" >&2
  echo "(KEYCLOAK_ADMIN). If the bootstrap admin was deleted, set the chaya-deploy client's secret in the admin console" >&2
  echo "(realm chaya, Clients, chaya-deploy, Credentials) to the value in .env (DEPLOYMENT.md \"Rotation\")." >&2
  exit 1
fi

id=$($K get clients -r chaya -q clientId=chaya-web --fields id --format csv --noquotes --config "$C")
[ -n "$id" ] || { echo "chaya-web client not found (realm not imported?)" >&2; exit 1; }
$K update "clients/$id" -r chaya --config "$C" \
  -s "redirectUris=[\"$APP/auth/callback\"]" \
  -s "webOrigins=[\"$APP\"]" \
  -s "attributes.\"post.logout.redirect.uris\"=$APP"
# sslRequired stays "external": public traffic is HTTPS through the proxy, while the API and worker use Keycloak's
# private-network backchannel (http://keycloak:8080), which "all" would refuse.
$K update realms/chaya --config "$C" -s sslRequired=external
echo "keycloak: chaya-web redirect/web origins set to $APP"
IN_CONTAINER
