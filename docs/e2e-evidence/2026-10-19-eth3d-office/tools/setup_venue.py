"""Creates the validation venue on the kept acceptance stack: users in Keycloak, venue and floor through the API.

argv: <keycloak url> <api url> <kc admin user> <kc admin password> <org id> <out dir>
Writes <out>/ids.json and the operator/admin tokens (private dir) for real_venue.py. A test-only Keycloak client
'chaya-validation' issues 8 h access tokens: real_venue.py reads its token once (see the validation report, defect list).
"""
import json
import secrets
import sys
import urllib.parse
import urllib.request
from pathlib import Path

kc, api, adm, admpw, org, out = sys.argv[1:7]
out = Path(out)


def call(method, url, body=None, token=None, form=None):
    data, headers = None, {}
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw and r.headers.get_content_type().endswith("json") else raw)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:500]


def admin():
    s, t = call("POST", f"{kc}/realms/master/protocol/openid-connect/token",
                form={"grant_type": "password", "client_id": "admin-cli", "username": adm, "password": admpw})
    assert s == 200, (s, t)
    return t["access_token"]


A = admin()
s, t = call("POST", f"{kc}/admin/realms/chaya/clients", token=A, body={
    "clientId": "chaya-validation", "publicClient": True, "standardFlowEnabled": False, "directAccessGrantsEnabled": True,
    "defaultClientScopes": ["basic", "chaya-roles", "chaya-api-audience", "chaya-tenant"],
    "attributes": {"access.token.lifespan": "28800"}})
assert s in (201, 409), (s, t)

passwords = {}


def user(name, role, venues):
    pw = secrets.token_urlsafe(18) + "Aa1!"
    attrs = {"org_id": [org]} | ({"venue_id": venues} if venues else {})
    s, t = call("POST", f"{kc}/admin/realms/chaya/users", token=A, body={
        "username": name, "enabled": True, "email": f"{name}@example.invalid", "emailVerified": True,
        "firstName": "Validation", "lastName": name, "attributes": attrs})
    assert s in (201, 409), (s, t)
    _, found = call("GET", f"{kc}/admin/realms/chaya/users?username={name}&exact=true", token=A)
    uid = found[0]["id"]
    rep = found[0] | {"attributes": attrs}
    call("PUT", f"{kc}/admin/realms/chaya/users/{uid}", token=A, body=rep)
    s, t = call("PUT", f"{kc}/admin/realms/chaya/users/{uid}/reset-password", token=A,
                body={"type": "password", "value": pw, "temporary": False})
    assert s == 204, (s, t)
    _, role_rep = call("GET", f"{kc}/admin/realms/chaya/roles/{role}", token=A)
    call("POST", f"{kc}/admin/realms/chaya/users/{uid}/role-mappings/realm", token=A, body=[role_rep])
    passwords[name] = pw


def token(name):
    s, t = call("POST", f"{kc}/realms/chaya/protocol/openid-connect/token",
                form={"grant_type": "password", "client_id": "chaya-validation", "username": name, "password": passwords[name]})
    assert s == 200, (s, t)
    return t["access_token"]


user("validation-admin", "admin", [])
admin_token = token("validation-admin")
s, venue = call("POST", f"{api}/api/v1/venues", token=admin_token, body={
    "slug": "eth3d-office", "name": "ETH3D office (public dataset, validation)", "timezone": "Europe/Zurich"})
assert s in (200, 201), (s, venue)
venue_id = venue["id"]
user("validation-admin", "admin", [venue_id])
admin_token = token("validation-admin")
s, floor = call("POST", f"{api}/api/v1/venues/{venue_id}/floors", token=admin_token, body={"level": 0, "name": "Office floor"})
assert s in (200, 201), (s, floor)
user("validation-operator", "operator", [venue_id])
user("validation-manager", "venue-manager", [venue_id])
priv = out / "private"
priv.mkdir(exist_ok=True)
for n, f in (("validation-operator", "operator.token"), ("validation-admin", "admin.token"), ("validation-manager", "manager.token")):
    (priv / f).write_text(token(n))
(priv / "passwords.json").write_text(json.dumps(passwords))
(out / "ids.json").write_text(json.dumps({"org": org, "venue": venue_id, "floor": floor["id"]}, indent=1))
print(json.dumps({"org": org, "venue": venue_id, "floor": floor["id"]}))
