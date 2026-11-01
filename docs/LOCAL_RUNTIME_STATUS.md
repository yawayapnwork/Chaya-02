# Local Runtime Status: Chaya 02

**Status Date:** 2026-11-01  
**Operating System:** Windows 11 Enterprise (64-bit)  
**Host Environment:** Docker Desktop 4.96.3 (WSL 2 backend, Ubuntu 2)  
**Runtime Node.js:** v24.14.0  
**Runtime npm:** 11.9.0  
**Runtime Python:** Python 3.14.0 (host) / Python 3.12 (services/reconstruction/.venv)  
**Overall Local Stack Health:** **ONLINE & FUNCTIONAL**

---

## 1. Service Map & Connectivity

| Service | Container / Process | Internal Port | Exposed Host Port | Health Check URL / Probe | Status | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Frontend Web App** | Node.js / Next.js 16.4.3 | 3000 | `3000` | `http://localhost:3000/api/health` | **HEALTHY** | Serving `/`, `/ops`, `/viewer`, `/capture`, `/ar`, `/status` |
| **Backend REST API** | `chaya-api-1` (Spring Boot 4.0.8, Java 21) | 8080 | `18080` | `http://localhost:18080/actuator/health/readiness` | **HEALTHY** | 43 Flyway migrations applied. Mapped to 18080 to avoid Windows 8080 exclusion. |
| **PostgreSQL + pgvector** | `chaya-postgres-1` (Postgres 17.11) | 5432 | `15432` | `pg_isready -h localhost -p 5432` | **HEALTHY** | Database `chaya`, pgvector, pg_trgm extensions loaded. |
| **Keycloak Identity Provider** | `chaya-keycloak-1` (Keycloak 26.0) | 8080 | `8081` | `http://localhost:8081/realms/chaya/.well-known/openid-configuration` | **HEALTHY** | Realm `chaya`, PKCE client `chaya-web`, service account `chaya-worker`. |
| **MinIO Object Storage** | `chaya-minio-1` (MinIO API) | 9000 | `9000` | `http://localhost:9000/minio/health/live` | **HEALTHY** | Buckets `chaya-raw` and `chaya-derived` initialized by `minio-init`. |
| **MinIO Console** | `chaya-minio-1` (Web UI) | 9001 | `9001` | Browser access `http://localhost:9001` | **HEALTHY** | Root user `chaya-root`. |
| **ClamAV Malware Scanner** | `chaya-clamav-1` (clamav/clamav:1.4) | 3310 | `3310` | `nc -z 127.0.0.1 3310` / ping | **HEALTHY** | Virus signature database loaded. |

---

## 2. Seeded Development Identities & Tenant Data

### Organization & Venue
- **Organization Slug:** `dev-org`
  - **ID:** `7008e9c4-67f7-49ad-a5ca-03c4b6dabba9`
  - **Name:** Development Organization
- **Venue Slug:** `dev-venue`
  - **ID:** `8152037e-29af-4b82-92fe-e9408f5a0a29`
  - **Name:** Development Venue

### Development Operator Account
- **Username:** `dev-operator`
- **Email:** `dev-operator@chaya.local`
- **Password:** `DevPassword123!`
- **Assigned Realm Roles:** `operator`
- **Tenant Claims in JWT:**
  - `org_id`: `7008e9c4-67f7-49ad-a5ca-03c4b6dabba9`
  - `venue_id`: `["8152037e-29af-4b82-92fe-e9408f5a0a29"]`
  - `venue_roles`: `["8152037e-29af-4b82-92fe-e9408f5a0a29:operator"]`

---

## 3. End-to-End Smoke Verification

1. **Frontend Readiness Probe (`/api/health`):**
   ```json
   {
     "status": "UP",
     "backend": {
       "reachable": true,
       "status": "DEGRADED",
       "httpStatus": 200
     },
     "problems": []
   }
   ```
2. **Backend API Readiness (`/actuator/health/readiness`):**
   ```json
   {"status":"UP"}
   ```
3. **Backend Detailed Component Health (`/api/v1/health`):**
   - `database`: UP
   - `storage`: UP
   - `identityProvider`: UP
   - `malwareScanner`: UP
   - `processing`: IDLE (0 queued, 0 running)
4. **Authenticated API Access (`GET /api/v1/venues`):**
   - Returns HTTP 200 with `[{"id":"8152037e-29af-4b82-92fe-e9408f5a0a29","organizationId":"7008e9c4-67f7-49ad-a5ca-03c4b6dabba9","slug":"dev-venue","name":"Development Venue","timezone":"UTC"}]`.
5. **Frontend Pages Verified:**
   - `/` (Home landing page): HTTP 200 OK
   - `/ops` (Operations Dashboard): HTTP 200 OK
   - `/status` (System Status): HTTP 200 OK
   - `/viewer` (3D Digital Twin Viewer): HTTP 200 OK
   - `/capture` (Capture & Upload flow): HTTP 200 OK
   - `/ar` (AR Navigation interface): HTTP 200 OK

---

## 4. Test Suite Execution Summary

- **Frontend Unit Tests (`npm --prefix apps/web test`):**
  - **Total Tests:** 472
  - **Passed:** 472
  - **Failed:** 0
- **Frontend Typecheck (`npm --prefix apps/web run typecheck`):**
  - **Status:** PASSED (0 errors)
- **Frontend Production Build (`npm --prefix apps/web run build`):**
  - **Status:** PASSED (standalone build with Next.js Turbopack)
- **Flyway Database Migrations:**
  - **Status:** 43/43 applied successfully (schema at `v43`).
