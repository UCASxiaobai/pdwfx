# Package backend/frontend source + offline dependencies for dev environment restore
# Output: release/pdwfx-backend-dev.zip, release/pdwfx-frontend-dev.zip

$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
if (-not (Test-Path (Join-Path $Root "backend\pom.xml"))) {
    throw "Project root not found"
}

$Version = Get-Date -Format 'yyyyMMdd-HHmm'
$ReleaseDir = Join-Path $Root "release"
$BackendStage = Join-Path $ReleaseDir "backend-dev"
$FrontendStage = Join-Path $ReleaseDir "frontend-dev"
$DevScripts = Join-Path $Root "scripts\portable-dev"
$OfflineM2 = Join-Path $Root "backend\offline-m2"

Write-Host "Project root: $Root"

Write-Host ""
Write-Host "==> Download Maven deps into backend/offline-m2 ..."
if (Test-Path $OfflineM2) { Remove-Item $OfflineM2 -Recurse -Force }
New-Item -ItemType Directory -Path $OfflineM2 | Out-Null

Push-Location (Join-Path $Root "backend")
mvn "-Dmaven.repo.local=$OfflineM2" -q dependency:go-offline
if ($LASTEXITCODE -ne 0) { throw "dependency:go-offline failed" }
mvn "-Dmaven.repo.local=$OfflineM2" -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }
Pop-Location

$m2Size = (Get-ChildItem $OfflineM2 -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("    offline-m2 size: {0:N1} MB" -f $m2Size)

Write-Host ""
Write-Host "==> Check frontend node_modules ..."
Push-Location (Join-Path $Root "frontend")
if (-not (Test-Path "node_modules")) {
    npm ci
    if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
}
Pop-Location

$nmSize = (Get-ChildItem (Join-Path $Root "frontend\node_modules") -Recurse -File -ErrorAction SilentlyContinue |
    Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("    node_modules size: {0:N1} MB" -f $nmSize)

Write-Host ""
Write-Host "==> Stage backend dev package ..."
if (Test-Path $BackendStage) { Remove-Item $BackendStage -Recurse -Force }
New-Item -ItemType Directory -Path $BackendStage | Out-Null

$BackendSrc = Join-Path $Root "backend"
Copy-Item (Join-Path $BackendSrc "pom.xml") $BackendStage
Copy-Item (Join-Path $BackendSrc "src") (Join-Path $BackendStage "src") -Recurse
Copy-Item $OfflineM2 (Join-Path $BackendStage "offline-m2") -Recurse
Copy-Item (Join-Path $DevScripts "start-backend-dev.bat") $BackendStage

Write-Host "==> Stage frontend dev package ..."
if (Test-Path $FrontendStage) { Remove-Item $FrontendStage -Recurse -Force }
New-Item -ItemType Directory -Path $FrontendStage | Out-Null

$FrontendSrc = Join-Path $Root "frontend"
foreach ($item in @("src", "public", "index.html", "package.json", "package-lock.json", "vite.config.js")) {
    $srcPath = Join-Path $FrontendSrc $item
    if (Test-Path $srcPath) {
        Copy-Item $srcPath (Join-Path $FrontendStage $item) -Recurse -Force
    }
}
Copy-Item (Join-Path $FrontendSrc "node_modules") (Join-Path $FrontendStage "node_modules") -Recurse
Copy-Item (Join-Path $DevScripts "start-frontend-dev.bat") $FrontendStage

Write-Host ""
Write-Host "==> Compress (may take several minutes) ..."
if (-not (Test-Path $ReleaseDir)) { New-Item -ItemType Directory -Path $ReleaseDir | Out-Null }

$BackendZip = Join-Path $ReleaseDir ("pdwfx-backend-dev-{0}.zip" -f $Version)
$FrontendZip = Join-Path $ReleaseDir ("pdwfx-frontend-dev-{0}.zip" -f $Version)

if (Test-Path $BackendZip) { Remove-Item $BackendZip -Force }
if (Test-Path $FrontendZip) { Remove-Item $FrontendZip -Force }

Compress-Archive -Path (Join-Path $BackendStage "*") -DestinationPath $BackendZip -CompressionLevel Optimal
Compress-Archive -Path (Join-Path $FrontendStage "*") -DestinationPath $FrontendZip -CompressionLevel Optimal

$BackendZipSize = [math]::Round((Get-Item $BackendZip).Length / 1MB, 1)
$FrontendZipSize = [math]::Round((Get-Item $FrontendZip).Length / 1MB, 1)

Copy-Item $BackendZip (Join-Path $ReleaseDir "pdwfx-backend-dev.zip") -Force
Copy-Item $FrontendZip (Join-Path $ReleaseDir "pdwfx-frontend-dev.zip") -Force

Write-Host ""
Write-Host "Done."
Write-Host ("  Backend: {0} ({1} MB)" -f $BackendZip, $BackendZipSize)
Write-Host ("  Frontend: {0} ({1} MB)" -f $FrontendZip, $FrontendZipSize)
