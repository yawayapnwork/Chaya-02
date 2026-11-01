# SECURITY & DATA INTEGRITY REVIEW: CHAYA 02
**Principal Security Engineer & Hostile Auditor Assessment**

**Audit Date:** 2026-11-01  
**Lead Auditor:** Independent Security Reviewer  
**Target Architecture:** Multi-tenant Indoor Digital Twin Platform  
**Compliance & Hardening Baseline:** OWASP ASVS Level 2, NIST 800-53 controls, Enterprise Tenancy Isolation  

---

## 0. Executive Security Summary

The security architecture of **Chaya 02** is exceptionally robust. In contrast to common prototype shortcuts, the platform implements strict defense-in-depth across the control plane, data storage, and processing workers:

1. **Strict Multi-Tenancy:** Every database query, API endpoint, S3 path, and search query is scoped by `organization_id` and `venue_id`. Unauthorized access across tenants consistently returns **HTTP 404 Not Found**, eliminating venue enumeration.
2. **Cryptographic Artifact Sealing:** All uploaded media, intermediate point clouds, camera poses, NavMeshes, and published `.ksplat` models are sealed with SHA-256 digests. Any bit tampering is detected prior to delivery.
3. **Malware Quarantine & Content Sniffing:** Media uploads pass chunk-level ClamAV scanning and Apache Tika MIME-type validation. Non-media polyglot files are rejected.
4. **Automated PII Redaction:** Faces and vehicle license plates are blurred prior to SfM and 3D Gaussian training; unmasked raw frames are purged from ephemeral storage.
5. **Zero Trust Worker Boundaries:** Processing workers run as unprivileged users on isolated internal networks without external internet ingress.

---

## 1. Threat Model & Attack Surface Analysis

```
                              ATTACK SURFACE & BOUNDARIES
                                        │
                         [Public Internet / Untrusted]
                                        │  TLS 1.3 / HTTPS (Caddy)
                         ┌──────────────▼──────────────┐
                         │   Edge Reverse Proxy (Caddy)│── Rate Limiting & CSP
                         └──────┬──────────────┬───────┘
                     app.*      │              │ auth.*
          ┌─────────────────────▼┐            ┌▼──────────────────────┐
          │ Next.js Web Client   │            │ Keycloak 26.x (OIDC)  │
          │ (Zero Embedded Sec.) │            │ (RS256 JWT Issuer)    │
          └─────────────────────┬┘            └───────────────────────┘
                                │ Bearer JWT (org_id, venue_roles)
                         ┌──────▼──────────────────────┐
                         │ Spring Boot 3 Resource Svr  │── TenantGuard & Scopes
                         └──────┬──────────────┬───────┘
          ┌─────────────────────┼──────────────┴──────────────────────┐
          │ [Internal Network]  │                                     │
   ┌──────▼──────┐       ┌──────▼──────┐       ┌─────────────┐ ┌──────▼──────┐
   │ PostgreSQL  │       │ MinIO S3    │       │ ClamAV /    │ │ Reconstruction
   │ 16+pgvector │       │ Object Store│       │ Tika Daemon │ │ Worker (CPU)
   └─────────────┘       └─────────────┘       └─────────────┘ └─────────────┘
```

### Threat Actors & Primary Vectors:
- **Tenant Snooper:** An authenticated user in Org A attempting to inspect or modify venues, captures, or models in Org B.
- **Malicious Operator:** An operator attempting to upload malware, corrupt coordinate frames, or publish uncalibrated models.
- **Public Viewer Exploiter:** An anonymous visitor using public viewer links attempting to traverse internal endpoints or execute arbitrary searches.
- **Infrastructure Attacker:** An external adversary attempting to exploit container vulnerabilities, forge JWTs, or cause Denial of Service.

---

## 2. Identity & Tenancy Isolation

### Token Architecture & Claims Validation:
- Authentication is strictly externalized to Keycloak 26.x via OpenID Connect (PKCE flow).
- The Spring API backend operates solely as an **OAuth2 Resource Server**; it never stores user passwords or generates user tokens.
- **Token Claims Enforced:**
  - `iss`: Must match the configured Keycloak issuer URI.
  - `aud`: Must include `chaya-api`.
  - `exp` / `nbf`: Strictly validated with a maximum allowable clock skew of 60 seconds.
  - `org_id`: Mandatory tenant UUID claim. User tokens lacking `org_id` receive immediate HTTP 401.
  - `venue_roles`: Structured array of `<venue_id>:<role>` grants (`venue-manager`, `operator`, `viewer`).

### Venue-Scoped Authorization (`VenueScopeFilter`):
- For requests targeted at `/api/v1/venues/{venueId}/...`, `VenueScopeFilter` intercepts the request and narrows the actor's security context strictly to the permissions granted at that specific venue.
- An administrator in Org A has no privileges in Org B.
- A user who is a `venue-manager` at Venue 1 and a `viewer` at Venue 2 cannot execute administrative actions at Venue 2.

### Enumeration Prevention:
- `TenantGuard` enforces that requesting a resource owned by another tenant yields **HTTP 404 (Not Found)** rather than HTTP 403 (Forbidden).
- Verified by: `TenantApiIsolationTest.java`, `CrossTenantResourceIsolationTest.java`.

---

## 3. Media Ingestion & Malware Protection

### Upload Security Pipeline:
```
Client Chunk Upload ──> MinIO Stage ──> ClamAV Scan ──> Tika MIME Sniff ──> SHA-256 Seal
                                            │                 │
                                    (Malware ──> 400) (Invalid ──> 400)
```

1. **Chunked Resumable Uploads:** Captured files are uploaded in discrete binary chunks referenced by client-generated UUIDs.
2. **Antivirus Protection:** ClamAV scans every ingested video and image file during `INPUT_VALIDATION`. EICAR signature test files are quarantined and immediately rejected.
3. **MIME-Type Sniffing:** Apache Tika inspects binary headers to prevent file extension spoofing (e.g. an executable renamed to `.mp4`). Non-media files trigger immediate stage failure.
4. **Vulnerability Remediation:** Apache Tika was upgraded from 3.2.2 to **3.3.2** to remediate CVE-2026-66755 (CVSS 7.5 denial of service / memory corruption).
5. **Rate Limiting:** IP-based bucket rate limiting on `/api/v1/captures/upload` prevents storage exhaustion attacks.

---

## 4. Privacy & PII Scrubbing

### Preprocessing Architecture (`PRIVACY_PREPROCESS`):
- Every camera frame extracted from raw video passes through an automated PII redaction worker before reaching Structure from Motion or 3D training.
- **Redaction Targets:** Human faces and automobile license plates.
- **Blurring Technique:** High-radius Gaussian blur with softened edge blending to prevent hard boundary artifacts in SfM feature matching.
- **Storage Lifecycle:** Raw un-blurred video frames are treated as ephemeral artifacts. Once privacy masking completes, un-blurred frames are deleted from the worker filesystem.
- **Observed Defect (P1-1):** The Haar cascade face detector exhibits high false-positive rates on indoor wall textures, occasionally masking planar building surfaces (up to 83% of a frame). The pipeline successfully prevents PII leakage, but over-masks non-PII features.

---

## 5. Model & Artifact Integrity

All artifacts generated by the reconstruction worker are cryptographically bound to their pipeline run and scan version:

| Artifact Kind | Storage Prefix | Integrity Mechanism | Enforcement Point |
|---|---|---|---|
| **Raw Media** | `s3://chaya/{org}/{venue}/captures/{id}/` | Pre-upload SHA-256 | Backend upload completion |
| **Masked Frames** | `s3://chaya/{org}/{venue}/artifacts/{run}/` | `frames-manifest.json` digest | Worker input validation |
| **Camera Poses** | `s3://chaya/{org}/{venue}/artifacts/{run}/` | Schema validation + checksum | Coordinate calibration |
| **Point Cloud** | `s3://chaya/{org}/{venue}/artifacts/{run}/` | PLY header validation + SHA | Geometry cleanup stage |
| **Model (.ksplat)**| `s3://chaya/{org}/{venue}/models/{version}/` | Level-0 layout check + SHA | Viewer browser delivery |
| **NavMesh (.bin)**| `s3://chaya/{org}/{venue}/navmesh/{version}/` | `navmesh_manifest.json` SHA | Route planning service |

### Tamper Protection Verification:
- In `apps/web/e2e/ksplat-viewer.spec.ts`, tests deliberately flip a single byte in a valid `.ksplat` model. The viewer client calculates the SHA-256 hash using `hash-wasm` in a Web Worker prior to parsing. The modified model is rejected with `ArtifactIntegrityError`, rendering nothing.

---

## 6. Infrastructure Hardening & Network Topology

### Reverse Proxy & Edge Defense:
- **Caddy 2.8+:** Terminates TLS 1.3 with automated ACME certificate management.
- **Security Headers Enforced:**
  - `Content-Security-Policy: default-src 'self'; script-src 'self'; object-src 'none'; frame-ancestors 'none';`
  - `X-Frame-Options: DENY`
  - `X-Content-Type-Options: nosniff`
  - `Strict-Transport-Security: max-age=63072000; includeSubDomains; preload`
- **/actuator Protection:** Spring Boot management endpoints (`/actuator/**`) are blocked at the Caddy edge and cannot be accessed externally.
- **Keycloak Admin Isolation:** Access to the Keycloak master realm and `/admin` console is restricted to an internal IP allowlist.

### Container Security Profile:
- All production Docker images (`chaya-api`, `chaya-web`, `chaya-worker`, `chaya-vision`) declare non-root runtime users (`USER 10001:10001`).
- All containers specify explicit Docker `HEALTHCHECK` definitions.
- Containers operate on an internal bridge network (`chaya-internal`) with no external internet route; only the ClamAV and vision weight downloader have egress access.

---

## 7. Disaster Recovery & Backup Integrity

### Backup Procedure (`scripts/backup/backup.sh`):
- Creates a point-in-time PostgreSQL dump using `pg_dump --format=custom`.
- Backs up MinIO S3 buckets with metadata.
- Computes SHA-256 manifest of all backup archives.

### Restore Drill Verification (`scripts/backup/restore-drill.sh`):
- Restores database tables and S3 buckets into an isolated sandbox environment.
- **Artifact Re-Verification:** Reads every artifact record in `pipeline_artifact` and recalculates its SHA-256 against the restored MinIO storage. Any missing or corrupt artifact causes the restore drill to exit with an error.
- Verified by automated test: `scripts/backup/test-restore-drill.sh` (tests valid backup, corrupted database, and missing artifact scenarios).

---

## 8. Security Review Verdict

### Rating: **EXCELLENT / PRODUCTION-HARDENED**

The security design, tenant isolation, cryptographic integrity gates, and input validation mechanisms meet rigorous enterprise standards. The platform demonstrates zero critical architectural vulnerabilities.
