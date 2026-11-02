<#
.SYNOPSIS
    Starts the complete Chaya 02 local development stack on Windows.
.DESCRIPTION
    Validates prerequisites, prepares environment files, starts Docker infrastructure and backend API,
    waits for health checks, seeds test venue and operator, and starts the Next.js frontend.
.PARAMETER StartFrontend
    Whether to launch the Next.js frontend in the background (default: true).
#>

[CmdletBinding()]
param(
    [switch]$StartFrontend = $true
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "     CHAYA 02 - Windows Local Stack Automation            " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Validate Prerequisites
Write-Host "`n[1/6] Checking prerequisites..." -ForegroundColor Yellow

# Docker
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "Docker is not found in PATH. Please install Docker Desktop and ensure it is running."
}
try {
    $dockerInfo = docker info 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Docker daemon is not running. Please start Docker Desktop."
    }
} catch {
    Write-Error "Failed to connect to Docker daemon: $_"
}
Write-Host "  [OK] Docker daemon is running" -ForegroundColor Green

# Node.js & npm
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Error "Node.js is not found in PATH. Please install Node.js (v20+)."
}
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    Write-Error "npm is not found in PATH."
}
$nodeVer = node -v
Write-Host "  [OK] Node.js ($nodeVer) and npm are available" -ForegroundColor Green

# Python
if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    Write-Warning "Python not found in PATH. Keycloak auto-seeding will be skipped if already seeded."
} else {
    Write-Host "  [OK] Python is available" -ForegroundColor Green
}

# 2. Check & Prepare Environment (.env and apps/web/.env.local)
Write-Host "`n[2/6] Validating environment configuration..." -ForegroundColor Yellow

if (-not (Test-Path ".env")) {
    Write-Host "  Creating .env from .env.example with secure random credentials..." -ForegroundColor Cyan
    if (-not (Test-Path ".env.example")) {
        Write-Error ".env.example not found."
    }
    Copy-Item ".env.example" ".env"
    
    function Get-SecureHexToken([int]$bytes = 32) {
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $buffer = New-Object byte[] $bytes
        $rng.GetBytes($buffer)
        return ($buffer | ForEach-Object { "{0:x2}" -f $_ }) -join ''
    }

    $envContent = Get-Content ".env" -Raw
    $placeholders = @(
        'POSTGRES_PASSWORD', 'POSTGRES_APP_PASSWORD', 'MINIO_ROOT_PASSWORD',
        'S3_SECRET_KEY', 'S3_WORKER_SECRET_KEY', 'S3_BACKUP_SECRET_KEY',
        'KEYCLOAK_ADMIN_PASSWORD', 'CHAYA_WORKER_CLIENT_SECRET',
        'METRICS_SCRAPE_PASSWORD', 'GLITCHTIP_SECRET_KEY', 'GLITCHTIP_DB_PASSWORD'
    )
    foreach ($p in $placeholders) {
        $token = Get-SecureHexToken
        $envContent = $envContent -replace "(?m)^$p=.*$", "$p=$token"
    }

    # Ensure Windows-safe ports
    $envContent = $envContent -replace "(?m)^POSTGRES_PORT=.*$", "POSTGRES_PORT=15432"
    $envContent = $envContent -replace "(?m)^API_PORT=.*$", "API_PORT=18080"
    $envContent = $envContent -replace "(?m)^CHAYA_API_URL=.*$", "CHAYA_API_URL=http://localhost:18080"
    $envContent = $envContent -replace "(?m)^CHAYA_API_BASE_URL=.*$", "CHAYA_API_BASE_URL=http://localhost:18080"
    $envContent = $envContent -replace "(?m)^NEXT_PUBLIC_API_BASE_URL=.*$", "NEXT_PUBLIC_API_BASE_URL=http://localhost:18080"
    Set-Content -Path ".env" -Value $envContent -NoNewline
    Write-Host "  [OK] Generated new .env configuration" -ForegroundColor Green
} else {
    Write-Host "  [OK] Using existing .env file" -ForegroundColor Green
}

# Frontend .env.local
if (-not (Test-Path "apps/web/.env.local")) {
    Write-Host "  Creating apps/web/.env.local..." -ForegroundColor Cyan
    $webEnv = @(
        'CHAYA_API_BASE_URL=http://localhost:18080',
        'CHAYA_PUBLIC_API_BASE_URL=http://localhost:18080',
        'CHAYA_PUBLIC_OIDC_ISSUER=http://localhost:8081/realms/chaya',
        'CHAYA_PUBLIC_OIDC_CLIENT_ID=chaya-web',
        'NEXT_PUBLIC_API_BASE_URL=http://localhost:18080',
        'NEXT_PUBLIC_OIDC_ISSUER=http://localhost:8081/realms/chaya',
        'NEXT_PUBLIC_OIDC_CLIENT_ID=chaya-web',
        'PORT=3000'
    ) -join "`n"
    Set-Content -Path "apps/web/.env.local" -Value $webEnv
    Write-Host "  [OK] Created apps/web/.env.local" -ForegroundColor Green
} else {
    Write-Host "  [OK] Using existing apps/web/.env.local" -ForegroundColor Green
}

# 3. Start Infrastructure & Backend API
Write-Host "[3/6] Starting Docker containers..." -ForegroundColor Yellow
$composeArgs = @(
    "--env-file", ".env",
    "-f", "infra/docker/docker-compose.yml",
    "-f", "infra/ci/docker-compose.smoke.yml",
    "up", "-d", "postgres", "minio", "minio-init", "keycloak", "clamav", "api"
)
& docker compose @composeArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to start Docker Compose services."
}

# 4. Wait for Service Health
Write-Host "`n[4/6] Waiting for service readiness..." -ForegroundColor Yellow

function Wait-Endpoint($url, $name, [int]$maxAttempts = 30, [int]$delaySeconds = 3) {
    Write-Host "  Waiting for $name ($url)..." -NoNewline
    for ($i = 1; $i -le $maxAttempts; $i++) {
        try {
            $resp = Invoke-WebRequest -Uri $url -Method Get -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
            if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 400) {
                Write-Host " Ready!" -ForegroundColor Green
                return $true
            }
        } catch {
            Start-Sleep -Seconds $delaySeconds
        }
    }
    Write-Host " TIMEOUT!" -ForegroundColor Red
    return $false
}

$kcReady = Wait-Endpoint "http://localhost:8081/realms/chaya/.well-known/openid-configuration" "Keycloak (chaya realm)" 20 3
$apiReady = Wait-Endpoint "http://localhost:18080/actuator/health/readiness" "Backend API Readiness" 30 3

if (-not $apiReady) {
    Write-Warning "Backend API is not yet reporting ready. Check 'docker logs chaya-api-1'."
}

# 5. Seed Test Data if needed
Write-Host "`n[5/6] Verifying development tenant data and operator user..." -ForegroundColor Yellow
if (Test-Path "scripts/seed-dev.py") {
    try {
        & python scripts/seed-dev.py
        Write-Host "  [OK] Dev tenant and operator user verified" -ForegroundColor Green
    } catch {
        Write-Warning "Could not run seed script: $_"
    }
}

# 6. Start / Verify Frontend
Write-Host "`n[6/6] Verifying Next.js frontend..." -ForegroundColor Yellow
$frontendRunning = $false
try {
    $feCheck = Invoke-WebRequest -Uri "http://localhost:3000/api/health" -Method Get -TimeoutSec 3 -UseBasicParsing -ErrorAction SilentlyContinue
    if ($feCheck -and $feCheck.StatusCode -eq 200) {
        $frontendRunning = $true
        Write-Host "  [OK] Frontend is already running and healthy at http://localhost:3000" -ForegroundColor Green
    }
} catch {
    $frontendRunning = $false
}

if (-not $frontendRunning -and $StartFrontend) {
    Write-Host "  Starting frontend Next.js server in the background..." -ForegroundColor Cyan
    $env:PORT = "3000"
    Start-Process -FilePath "node" -ArgumentList "server.js" -WorkingDirectory (Join-Path $repoRoot "apps/web") -WindowStyle Hidden
    Start-Sleep -Seconds 3
    $feReady = Wait-Endpoint "http://localhost:3000/api/health" "Frontend Web App" 10 2
    if ($feReady) {
        Write-Host "  [OK] Frontend is now running at http://localhost:3000" -ForegroundColor Green
    }
}

# Summary
Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "           CHAYA 02 IS READY AND RUNNING                  " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "  Web Application:   http://localhost:3000" -ForegroundColor Cyan
Write-Host "  API Base URL:      http://localhost:18080" -ForegroundColor Cyan
Write-Host "  API Health:        http://localhost:18080/api/v1/health" -ForegroundColor Cyan
Write-Host "  API Docs (OpenAPI):http://localhost:18080/api/v1/openapi" -ForegroundColor Cyan
Write-Host "  Keycloak Admin:    http://localhost:8081 (admin / see .env)" -ForegroundColor Cyan
Write-Host "  MinIO Console:     http://localhost:9001 (chaya-root / see .env)" -ForegroundColor Cyan
Write-Host "`n  Default Operator Login:" -ForegroundColor White
Write-Host "    Username: dev-operator" -ForegroundColor White
Write-Host "    Password: DevPassword123!" -ForegroundColor White
Write-Host "==========================================================" -ForegroundColor Green
