# 构建并打包前后端便携压缩包（在开发机上运行一次即可）
# 产出: release/pdwfx-backend-portable-*.zip
#       release/pdwfx-frontend-portable-*.zip

$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
if (-not (Test-Path (Join-Path $Root "backend\pom.xml"))) {
    throw "无法定位项目根目录，请在 pdwfx 目录下运行 scripts\package-portable.ps1"
}

$Version = Get-Date -Format "yyyyMMdd-HHmm"
$ReleaseDir = Join-Path $Root "release"
$BackendStage = Join-Path $ReleaseDir "backend-portable"
$FrontendStage = Join-Path $ReleaseDir "frontend-portable"
$PortableScripts = Join-Path $Root "scripts\portable"

Write-Host "==> 项目根目录: $Root"

# --- 构建后端 ---
Write-Host "`n==> 构建后端 (Maven)..."
Push-Location (Join-Path $Root "backend")
mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败" }
Pop-Location

$Jar = Get-ChildItem (Join-Path $Root "backend\target\signal-analysis-*.jar") |
    Where-Object { $_.Name -notmatch "original" } |
    Select-Object -First 1
if (-not $Jar) { throw "未找到构建产物 JAR" }
Write-Host "    JAR: $($Jar.Name) ($([math]::Round($Jar.Length/1MB, 1)) MB)"

# --- 构建前端 ---
Write-Host "`n==> 构建前端 (npm)..."
Push-Location (Join-Path $Root "frontend")
if (-not (Test-Path "node_modules")) {
    npm ci
    if ($LASTEXITCODE -ne 0) { throw "npm ci 失败" }
}
npm run build
if ($LASTEXITCODE -ne 0) { throw "npm run build 失败" }
Pop-Location

if (-not (Test-Path (Join-Path $Root "frontend\dist\index.html"))) {
    throw "前端 dist 构建失败"
}

# --- 组装后端包 ---
Write-Host "`n==> 组装后端便携包..."
if (Test-Path $BackendStage) { Remove-Item $BackendStage -Recurse -Force }
New-Item -ItemType Directory -Path $BackendStage | Out-Null
New-Item -ItemType Directory -Path (Join-Path $BackendStage "output") | Out-Null

Copy-Item $Jar.FullName (Join-Path $BackendStage "signal-analysis.jar")
Copy-Item (Join-Path $Root "backend\src\main\resources\application.yml") (Join-Path $BackendStage "application.yml")
Copy-Item (Join-Path $PortableScripts "start-backend.bat") $BackendStage
Copy-Item (Join-Path $PortableScripts "stop-backend.bat") $BackendStage
Copy-Item (Join-Path $PortableScripts "README-backend.txt") (Join-Path $BackendStage "README.txt")

# --- 组装前端包 ---
Write-Host "==> 组装前端便携包..."
if (Test-Path $FrontendStage) { Remove-Item $FrontendStage -Recurse -Force }
New-Item -ItemType Directory -Path $FrontendStage | Out-Null

Copy-Item (Join-Path $Root "frontend\dist") (Join-Path $FrontendStage "dist") -Recurse
Copy-Item (Join-Path $PortableScripts "serve-frontend.ps1") $FrontendStage
Copy-Item (Join-Path $PortableScripts "start-frontend.bat") $FrontendStage
Copy-Item (Join-Path $PortableScripts "README-frontend.txt") (Join-Path $FrontendStage "README.txt")

# 便携部署 API 指向本机后端
$RuntimeConfig = @"
// 便携包配置：前端静态服务在 5173，API 指向后端 18080
window.__API_BASE__ = "http://localhost:18080";
"@
Set-Content -Path (Join-Path $FrontendStage "dist\runtime-config.js") -Value $RuntimeConfig -Encoding UTF8

# --- 压缩 ---
Write-Host "`n==> 压缩..."
if (-not (Test-Path $ReleaseDir)) { New-Item -ItemType Directory -Path $ReleaseDir | Out-Null }

$BackendZip = Join-Path $ReleaseDir "pdwfx-backend-portable-$Version.zip"
$FrontendZip = Join-Path $ReleaseDir "pdwfx-frontend-portable-$Version.zip"

if (Test-Path $BackendZip) { Remove-Item $BackendZip -Force }
if (Test-Path $FrontendZip) { Remove-Item $FrontendZip -Force }

Compress-Archive -Path (Join-Path $BackendStage "*") -DestinationPath $BackendZip -CompressionLevel Optimal
Compress-Archive -Path (Join-Path $FrontendStage "*") -DestinationPath $FrontendZip -CompressionLevel Optimal

$BackendSize = [math]::Round((Get-Item $BackendZip).Length / 1MB, 1)
$FrontendSize = [math]::Round((Get-Item $FrontendZip).Length / 1MB, 1)

Write-Host "`n完成!"
Write-Host "  后端: $BackendZip ($BackendSize MB)"
Write-Host "  前端: $FrontendZip ($FrontendSize MB)"
Write-Host "`n目标机使用: 解压后端 -> start-backend.bat -> 解压前端 -> start-frontend.bat"
Write-Host "目标机需安装: Java 8+（仅后端需要）"
