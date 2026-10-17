"""1. AUTHENTICATION: real Keycloak tokens against the real API."""

from __future__ import annotations

import secrets
import time

import pytest

from .stack import Http, Keycloak, jwt_claims

pytestmark = pytest.mark.acceptance
CLOCK_SKEW_S = 60  # org.springframework.security.oauth2.jwt.JwtTimestampValidator default


def test_operator_login_carries_tenant_claims_and_scopes_the_venue_list(world, kc: Keycloak, cfg, ev):
    """An operator signs in at Keycloak; the API accepts the token and lists only the operator's venue."""
    r = kc.token(f"acc-operator-{world.tag}")
    ev.check("1.1", "Keycloak issues a token (password grant, test client)", "200 with access_token", r.status_code == 200,
             f"HTTP {r.status_code}")
    c = jwt_claims(r.json()["access_token"])
    aud = c.get("aud") if isinstance(c.get("aud"), list) else [c.get("aud")]
    ev.check("1.2", "Token carries audience, organization, venue and role", "aud chaya-api; org_id A; venue_id [A]; operator",
             "chaya-api" in aud and c.get("org_id") == cfg.org_a and world.venue_a in (c.get("venue_id") or [])
             and "operator" in (c.get("realm_access") or {}).get("roles", []),
             f"aud={aud} org_id={c.get('org_id')} venue_id={c.get('venue_id')} roles={(c.get('realm_access') or {}).get('roles')}")
    venues = world.u("operator").ok("GET", "/api/v1/venues")
    ids = [v["id"] for v in venues]
    ev.check("1.3", "The API lists exactly the operator's venue", f"[{world.venue_a}]", ids == [world.venue_a], f"{ids}")
    anon = Http(cfg)("GET", "/api/v1/venues")
    ev.check("1.4", "No token is refused", "401", anon.status_code == 401, f"HTTP {anon.status_code}")
    tok = r.json()["access_token"]
    tampered = tok[:-6] + ("AAAAAA" if not tok.endswith("AAAAAA") else "BBBBBB")
    t = Http(cfg, token=tampered)("GET", "/api/v1/venues")
    ev.check("1.5", "A token with a broken signature is refused", "401", t.status_code == 401, f"HTTP {t.status_code}")
    bad = kc.token(f"acc-operator-{world.tag}", password=secrets.token_urlsafe(18) + "Aa1!")  # never the user's
    ev.check("1.6", "A wrong password is refused by Keycloak", "401", bad.status_code == 401, f"HTTP {bad.status_code}")


def test_venue_authorization_follows_the_token_venue_and_role(world, ev):
    """An operator of venue A cannot see or write venue A2 of the same organization; a viewer cannot write."""
    op = world.u("operator")
    r = op("GET", f"/api/v1/venues/{world.venue_a2}/captures")
    ev.check("1.7", "Another venue of the same organization is invisible", "404 (no enumeration)", r.status_code == 404,
             f"HTTP {r.status_code}")
    r = op("POST", f"/api/v1/venues/{world.venue_a2}/captures", json={"device": {}})
    ev.check("1.8", "...and cannot be written", "404", r.status_code == 404, f"HTTP {r.status_code}")
    r = world.u("viewer")("POST", f"/api/v1/venues/{world.venue_a}/captures", json={"device": {}})
    ev.check("1.9", "A viewer of venue A cannot create a capture there", "403", r.status_code == 403, f"HTTP {r.status_code}")
    r = world.u("operator")("POST", "/api/v1/venues", json={"slug": f"acc-x-{world.tag}", "name": "x"})
    ev.check("1.10", "An operator cannot create a venue (admin only)", "403", r.status_code == 403, f"HTTP {r.status_code}")


def test_an_expired_token_is_refused(world, kc: Keycloak, cfg, ev):
    """A token from a client whose access tokens live 10 s is accepted, then refused once it has expired."""
    r = kc.token(f"acc-operator-{world.tag}", client=Keycloak.SHORT_CLIENT)
    tok = r.json()["access_token"]
    c = jwt_claims(tok)
    ev.check("1.11", "Short-lived token issued", "exp - iat == 10 s", c["exp"] - c["iat"] == 10, f"exp-iat={c['exp'] - c['iat']}")
    user = Http(cfg, token=tok)
    first = user("GET", "/api/v1/venues")
    ev.check("1.12", "Accepted while valid", "200", first.status_code == 200, f"HTTP {first.status_code}")
    # Spring Security's JwtTimestampValidator (JwtValidators.createDefaultWithIssuer) allows 60 s of clock skew: a token is
    # accepted until exp + 60 s. Measured here, not assumed: just after exp, then after exp + skew.
    time.sleep(max(0.0, c["exp"] - time.time()) + 3)
    grace = user("GET", "/api/v1/venues")
    ev.check("1.13", "Just after exp (inside the validator's 60 s clock-skew allowance)", "200 (documented grace, not a bypass)",
             grace.status_code == 200, f"HTTP {grace.status_code} at exp + 3 s")
    time.sleep(max(0.0, c["exp"] + CLOCK_SKEW_S - time.time()) + 3)
    after = user("GET", "/api/v1/venues")
    ev.check("1.14", "Refused once exp + clock skew has passed", "401, WWW-Authenticate names the expiry", after.status_code == 401
             and "expired" in after.headers.get("WWW-Authenticate", "").lower(),
             f"HTTP {after.status_code} WWW-Authenticate={after.headers.get('WWW-Authenticate', '')[:160]}")


def test_cross_tenant_access_is_rejected(world, ev):
    """Organization B's admin and manager cannot see anything of organization A, and the reverse."""
    admin_b = world.u("admin_b")
    ids = [v["id"] for v in admin_b.ok("GET", "/api/v1/venues")]
    ev.check("1.15", "Org B's venue list excludes org A's venues", f"only {world.venue_b}", ids == [world.venue_b], f"{ids}")
    for path, name in ((f"/api/v1/venues/{world.venue_a}", "venue"), (f"/api/v1/venues/{world.venue_a}/captures", "captures"),
                       (f"/api/v1/venues/{world.venue_a}/search?q=pillar", "search"), (f"/api/v1/venues/{world.venue_a}/pois", "POIs")):
        r = admin_b("GET", path)
        ev.check(f"1.16.{name}", f"Org B admin reads org A's {name}", "404", r.status_code == 404, f"HTTP {r.status_code}")
    r = world.u("manager_b")("GET", f"/api/v1/venues/{world.venue_a}/ops/overview")
    ev.check("1.17", "Org B manager reads org A's operations overview", "404", r.status_code == 404, f"HTTP {r.status_code}")
    r = world.u("admin")("GET", f"/api/v1/venues/{world.venue_b}")
    ev.check("1.18", "Org A admin reads org B's venue", "404", r.status_code == 404, f"HTTP {r.status_code}")
