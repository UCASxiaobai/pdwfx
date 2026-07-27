# 便携包静态资源服务（无需 Node.js，使用 Windows 自带 PowerShell）
param(
    [int]$Port = 5173,
    [string]$Root = $PSScriptRoot
)

$dist = Join-Path $Root "dist"
if (-not (Test-Path $dist)) {
    Write-Error "未找到 dist 目录: $dist"
    exit 1
}

$listener = New-Object System.Net.HttpListener
$prefix = "http://localhost:$Port/"
$listener.Prefixes.Add($prefix)

try {
    $listener.Start()
} catch {
    Write-Error "无法监听 $prefix （端口可能被占用）。错误: $_"
    exit 1
}

Write-Host "前端静态服务已启动: $prefix"
Write-Host "请确保后端已在 http://localhost:18080 运行"
Write-Host "按 Ctrl+C 停止服务"
Write-Host ""

$mime = @{
    ".html" = "text/html; charset=utf-8"
    ".js"   = "application/javascript; charset=utf-8"
    ".css"  = "text/css; charset=utf-8"
    ".json" = "application/json; charset=utf-8"
    ".png"  = "image/png"
    ".jpg"  = "image/jpeg"
    ".jpeg" = "image/jpeg"
    ".svg"  = "image/svg+xml"
    ".ico"  = "image/x-icon"
    ".woff" = "font/woff"
    ".woff2"= "font/woff2"
    ".map"  = "application/json"
}

function Send-Bytes($context, $bytes, $contentType) {
    $response = $context.Response
    $response.ContentType = $contentType
    $response.ContentLength64 = $bytes.Length
    $response.OutputStream.Write($bytes, 0, $bytes.Length)
    $response.OutputStream.Close()
}

while ($listener.IsListening) {
    $context = $listener.GetContext()
    $request = $context.Request
    $path = [System.Uri]::UnescapeDataString($request.Url.AbsolutePath)

    if ($path -eq "/") { $path = "/index.html" }

    $filePath = Join-Path $dist ($path.TrimStart("/").Replace("/", "\"))
    $resolved = [System.IO.Path]::GetFullPath($filePath)
    $distFull = [System.IO.Path]::GetFullPath($dist)

    if (-not $resolved.StartsWith($distFull, [StringComparison]::OrdinalIgnoreCase)) {
        $context.Response.StatusCode = 403
        $context.Response.Close()
        continue
    }

    if (Test-Path $resolved -PathType Leaf) {
        $ext = [System.IO.Path]::GetExtension($resolved).ToLowerInvariant()
        $type = $mime[$ext]
        if (-not $type) { $type = "application/octet-stream" }
        $bytes = [System.IO.File]::ReadAllBytes($resolved)
        Send-Bytes $context $bytes $type
    } else {
        # SPA fallback
        $index = Join-Path $dist "index.html"
        if (Test-Path $index) {
            $bytes = [System.IO.File]::ReadAllBytes($index)
            Send-Bytes $context $bytes "text/html; charset=utf-8"
        } else {
            $context.Response.StatusCode = 404
            $context.Response.Close()
        }
    }
}
