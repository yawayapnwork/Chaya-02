import os
import re
import json
import subprocess
import urllib.request
import urllib.parse
from pathlib import Path

def parse_env(env_path):
    env = {}
    if not os.path.exists(env_path):
        return env
    with open(env_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            # remove inline comment
            if "#" in line:
                line = line.split("#", 1)[0].strip()
            if "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip("'\"")
    return env

def main():
    root = Path(__file__).resolve().parent.parent
    env_vars = parse_env(root / ".env")
    
    kc_port = env_vars.get("KEYCLOAK_PORT", "8081")
    kc = f"http://localhost:{kc_port}"
    api_port = env_vars.get("API_PORT", "18080")
    api_url = f"http://localhost:{api_port}"
    admin_user = env_vars.get("KEYCLOAK_ADMIN", "admin")
    admin_pw = env_vars.get("KEYCLOAK_ADMIN_PASSWORD", "")

    # Query or insert organization & venue in PostgreSQL
    # Use docker compose exec
    def run_psql(sql):
        cmd = [
            "docker", "compose", "--env-file", str(root / ".env"),
            "-f", str(root / "infra/docker/docker-compose.yml"),
            "exec", "-T", "postgres",
            "psql", "-U", "postgres", "-d", "chaya", "-q", "-t", "-A", "-c", sql
        ]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
        return res.stdout.strip()

    # Check existing org
    org_id = run_psql("SELECT id FROM organization WHERE slug = 'dev-org' LIMIT 1;")
    if not org_id:
        org_id = run_psql("INSERT INTO organization (slug, name) VALUES ('dev-org', 'Development Organization') RETURNING id;")
        print(f"Created dev-org: {org_id}")
    else:
        print(f"Using existing dev-org: {org_id}")

    # Check existing venue
    venue_id = run_psql(f"SELECT id FROM venue WHERE slug = 'dev-venue' AND organization_id = '{org_id}' LIMIT 1;")
    if not venue_id:
        venue_id = run_psql(f"INSERT INTO venue (organization_id, slug, name) VALUES ('{org_id}', 'dev-venue', 'Development Venue') RETURNING id;")
        print(f"Created dev-venue: {venue_id}")
    else:
        print(f"Using existing dev-venue: {venue_id}")

    # Obtain Keycloak admin token
    data = urllib.parse.urlencode({
        "grant_type": "password",
        "client_id": "admin-cli",
        "username": admin_user,
        "password": admin_pw
    }).encode()
    req = urllib.request.Request(f"{kc}/realms/master/protocol/openid-connect/token", data=data)
    with urllib.request.urlopen(req) as resp:
        admin_token = json.loads(resp.read())["access_token"]

    def kc_request(path, method="GET", body=None):
        headers = {
            "Authorization": f"Bearer {admin_token}",
            "Content-Type": "application/json"
        }
        data_bytes = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(f"{kc}/admin/realms/chaya{path}", data=data_bytes, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req) as resp:
                content = resp.read()
                if content:
                    return json.loads(content)
                return None
        except urllib.error.HTTPError as e:
            err_body = e.read().decode()
            print(f"HTTPError on {method} {path}: {e.code} {err_body}")
            raise

    # Enable directAccessGrantsEnabled on chaya-web for testing/smoke
    clients = kc_request("/clients?clientId=chaya-web")
    if clients:
        web_client = clients[0]
        client_id_internal = web_client["id"]
        if not web_client.get("directAccessGrantsEnabled"):
            web_client["directAccessGrantsEnabled"] = True
            kc_request(f"/clients/{client_id_internal}", method="PUT", body=web_client)
            print("Enabled directAccessGrantsEnabled on chaya-web")

    # Check if dev-operator exists
    users = kc_request("/users?username=dev-operator&exact=true")
    if not users:
        user_body = {
            "username": "dev-operator",
            "enabled": True,
            "email": "dev-operator@chaya.local",
            "emailVerified": True,
            "firstName": "Dev",
            "lastName": "Operator",
            "attributes": {
                "org_id": [org_id],
                "venue_id": [venue_id],
                "venue_roles": [f"{venue_id}:operator"]
            }
        }
        kc_request("/users", method="POST", body=user_body)
        print("Created dev-operator user")
        users = kc_request("/users?username=dev-operator&exact=true")
    else:
        user_id = users[0]["id"]
        user_body = users[0]
        user_body["attributes"] = {
            "org_id": [org_id],
            "venue_id": [venue_id],
            "venue_roles": [f"{venue_id}:operator"]
        }
        kc_request(f"/users/{user_id}", method="PUT", body=user_body)

    user_id = users[0]["id"]

    # Check if existing password works, otherwise reset password
    user_password = "DevPassword123!"
    password_valid = False
    try:
        test_data = urllib.parse.urlencode({
            "grant_type": "password",
            "client_id": "chaya-web",
            "username": "dev-operator",
            "password": user_password
        }).encode()
        req_test = urllib.request.Request(f"{kc}/realms/chaya/protocol/openid-connect/token", data=test_data)
        with urllib.request.urlopen(req_test) as resp:
            password_valid = True
    except Exception:
        password_valid = False

    if not password_valid:
        try:
            kc_request(f"/users/{user_id}/reset-password", method="PUT", body={
                "type": "password",
                "value": user_password,
                "temporary": False
            })
            print(f"Set password for dev-operator to {user_password}")
        except Exception as e:
            print(f"Could not reset password: {e}")
    else:
        print("dev-operator credentials already valid")

    # Assign operator role
    role = kc_request("/roles/operator")
    try:
        kc_request(f"/users/{user_id}/role-mappings/realm", method="POST", body=[role])
    except Exception:
        pass

    # Verify user token
    token_data = urllib.parse.urlencode({
        "grant_type": "password",
        "client_id": "chaya-web",
        "username": "dev-operator",
        "password": "DevPassword123!"
    }).encode()
    req_token = urllib.request.Request(f"{kc}/realms/chaya/protocol/openid-connect/token", data=token_data)
    with urllib.request.urlopen(req_token) as resp:
        token = json.loads(resp.read())["access_token"]

    # Verify API access
    req_api = urllib.request.Request(f"{api_url}/api/v1/venues", headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req_api) as resp:
        venues = json.loads(resp.read())
        print(f"Verified API access: retrieved {len(venues)} venue(s)")

if __name__ == "__main__":
    main()
