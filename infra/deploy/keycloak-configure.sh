#!/usr/bin/env bash
# Idempotent post-start Keycloak configuration for this environment. The realm file (infra/keycloak/chaya-realm.json)
# is shared with development and carries localhost URLs; this sets the production values, every deploy.
#
# Runs kcadm INSIDE the Keycloak container using the bootstrap admin it already has in its environment, so no secret is
# passed on any command line from the host.
set -euo pipefail
cd "$(dirname "$0")"
ENV_FILE=${ENV_FILE:-./.env}
set -a; . "$ENV_FILE"; set +a
COMPOSE="docker compose --env-file $ENV_FILE -f docker-compose.yml"
APP="https://${CHAYA_APP_DOMAIN:?}"

$COMPOSE exec -T -e APP="$APP" keycloak sh -s <<'IN_CONTAINER'
set -eu
K=/opt/keycloak/bin/kcadm.sh
$K config credentials --server http://localhost:8080 --realm master \
  --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" --config /tmp/kcadm.config > /dev/null
id=$($K get clients -r chaya -q clientId=chaya-web --fields id --format csv --noquotes --config /tmp/kcadm.config)
[ -n "$id" ] || { echo "chaya-web client not found (realm not imported?)" >&2; exit 1; }
$K update "clients/$id" -r chaya --config /tmp/kcadm.config \
  -s "redirectUris=[\"$APP/auth/callback\"]" \
  -s "webOrigins=[\"$APP\"]" \
  -s "attributes.\"post.logout.redirect.uris\"=$APP"
# sslRequired stays "external": public traffic is HTTPS through the proxy, while the API and worker use Keycloak's
# private-network backchannel (http://keycloak:8080), which "all" would refuse.
$K update realms/chaya --config /tmp/kcadm.config -s sslRequired=external
rm -f /tmp/kcadm.config
echo "keycloak: chaya-web redirect/web origins set to $APP"
IN_CONTAINER
