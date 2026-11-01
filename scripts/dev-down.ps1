<#
.SYNOPSIS
    Safely stops the Chaya 02 local development stack on Windows.
.DESCRIPTION
    Stops the Next.js frontend process and Docker Compose services without deleting volumes or user data.
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

Write-Host "==> Stopping Chaya 02 local development environment..." -ForegroundColor Cyan

# 1. Stop Next.js frontend if running on port 3000
try {
    $connections = Get-NetTCPConnection -LocalPort 3000 -ErrorAction SilentlyContinue | Where-Object { $_.State -eq 'Listen' }
    foreach ($conn in $connections) {
        $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
        if ($proc -and $proc.ProcessName -match "node|next") {
            Write-Host "Stopping frontend process (PID $($proc.Id))..." -ForegroundColor Yellow
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        }
    }
} catch {
    Write-Warning "Could not stop frontend process on port 3000: $_"
}

# 2. Stop Docker Compose services (preserving volumes)
$composeFiles = @(
    "-f", "infra/docker/docker-compose.yml",
    "-f", "infra/ci/docker-compose.smoke.yml"
)

if (Test-Path ".env") {
    Write-Host "Stopping Docker containers (preserving volumes)..." -ForegroundColor Yellow
    docker compose --env-file .env @composeFiles stop
    Write-Host "Containers stopped." -ForegroundColor Green
} else {
    Write-Host "No .env found; stopping default compose services..." -ForegroundColor Yellow
    docker compose -f infra/docker/docker-compose.yml stop
}

Write-Host "==> Chaya 02 local stack safely stopped. Volumes and data preserved." -ForegroundColor Green
