<#
  打包发布产物。

  产出（都在 target\release\ 下）：
    sokoban.jar                     自包含可执行 jar（后端 + 内嵌前端）
    sokoban-<版本>.zip              完整运行包：jar + 一键运行脚本 + README
    sokoban-frontend-<版本>.zip     只含前端静态文件（给 Nginx 等分离部署用）

  关键一步是把 frontend\dist 塞进 jar 的 BOOT-INF\classes\static\，
  这样 Spring Boot 会直接把前端挂在根路径上，一个进程、一个端口就能跑，
  发布包不需要 Node，也不需要任何反向代理。
  注意这只影响发布产物，日常开发仍然是前后端分开跑（start-dev.ps1）。
#>
param(
    [switch]$SkipFrontend,   # 复用已有的 frontend\dist，不重新构建
    [switch]$SkipSmoke       # 跳过启动冒烟测试
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Definition)

function Write-Step($text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Write-Ok($text) { Write-Host "  [OK]   $text" -ForegroundColor Green }
function Write-Bad($text) { Write-Host "  [失败] $text" -ForegroundColor Red; exit 1 }

# ---- 0. 版本号与 JDK ----
. (Join-Path $root 'scripts\jdk.ps1')
$jdk = $env:SOKOBAN_JDK_HOME
$jarExe = Join-Path $jdk 'bin\jar.exe'
$javaExe = Join-Path $jdk 'bin\java.exe'
if (-not (Test-Path $javaExe)) { Write-Bad "找不到 JDK：$jdk（改 scripts\jdk.ps1）" }

[xml]$pom = Get-Content (Join-Path $root 'pom.xml')
$version = $pom.project.version
$relDir = Join-Path $root 'target\release'
$jarPath = Join-Path $root 'target\sokoban.jar'
$frontendDir = Join-Path $root 'frontend'
$distDir = Join-Path $frontendDir 'dist'

Write-Step "打包发布产物（版本 $version）"
Write-Ok "JDK：$jdk"

# ---- 1. 后端 jar ----
Write-Step '构建后端 jar'
Push-Location $root
& (Join-Path $root 'mvn17.ps1') -B -q -DskipTests package
Pop-Location
if (-not (Test-Path $jarPath)) { Write-Bad '没有产出 target\sokoban.jar' }
Write-Ok "sokoban.jar（$('{0:N1} MB' -f ((Get-Item $jarPath).Length / 1MB))）"

# ---- 2. 前端 ----
if ($SkipFrontend) {
    Write-Step '跳过前端构建（复用 frontend\dist）'
} else {
    Write-Step '构建前端'
    Remove-Item $distDir -Recurse -Force -ErrorAction SilentlyContinue
    Push-Location $frontendDir
    if (-not (Test-Path 'node_modules')) { & npm install }
    & node 'node_modules\vite\bin\vite.js' build
    Pop-Location
}
if (-not (Test-Path (Join-Path $distDir 'index.html'))) { Write-Bad '前端 dist 里没有 index.html' }
Write-Ok 'frontend\dist 就绪'

# ---- 3. 把前端塞进 jar ----
Write-Step '把前端内嵌进 jar（BOOT-INF\classes\static\）'
$stage = Join-Path $relDir 'stage'
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
$staticDir = Join-Path $stage 'BOOT-INF\classes\static'
New-Item -ItemType Directory -Force -Path $staticDir | Out-Null
Copy-Item (Join-Path $distDir '*') $staticDir -Recurse -Force
& $jarExe uf $jarPath -C $stage 'BOOT-INF/classes/static'
if ($LASTEXITCODE -ne 0) { Write-Bad 'jar 更新失败' }

$entries = & $jarExe tf $jarPath
if (-not ($entries | Select-String -SimpleMatch 'BOOT-INF/classes/static/index.html')) {
    Write-Bad 'jar 里没有看到内嵌的前端文件'
}
Write-Ok '前端已内嵌'

# ---- 4. 冒烟测试：这个 jar 单独跑起来能不能同时提供前后端 ----
if (-not $SkipSmoke) {
    Write-Step '冒烟测试（单独跑 jar，检查前后端都能访问）'
    $log = Join-Path $relDir 'smoke.log'
    $err = Join-Path $relDir 'smoke.err'
    $proc = Start-Process -FilePath $javaExe -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', $jarPath, '--server.port=18080') `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $err -WorkingDirectory $root
    $ok = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 500
        if ($proc.HasExited) { break }
        try { $null = Invoke-WebRequest 'http://127.0.0.1:18080/api/levels' -UseBasicParsing -TimeoutSec 2; $ok = $true; break } catch { }
    }
    if ($ok) {
        $levels = (Invoke-WebRequest 'http://127.0.0.1:18080/api/levels' -UseBasicParsing -TimeoutSec 5).Content
        Write-Ok "接口正常（$(($levels | ConvertFrom-Json).Count) 个内置关卡）"
        $page = (Invoke-WebRequest 'http://127.0.0.1:18080/' -UseBasicParsing -TimeoutSec 5)
        if ($page.Content -match 'id="app"') { Write-Ok '根路径返回了前端页面' }
        else { Write-Bad '根路径没有返回前端页面' }
    } else {
        if (Test-Path $err) { Get-Content $err -Tail 15 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray } }
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        Write-Bad 'jar 单独启动失败'
    }
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 800
}

# ---- 5. 组装发布包 ----
Write-Step '组装发布包'
Remove-Item $relDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $relDir | Out-Null

Copy-Item $jarPath (Join-Path $relDir 'sokoban.jar') -Force

# 完整运行包：解压即用，双击 run-sokoban.cmd
$bundle = Join-Path $relDir "bundle\sokoban-$version"
New-Item -ItemType Directory -Force -Path $bundle | Out-Null
Copy-Item $jarPath (Join-Path $bundle 'sokoban.jar') -Force
Copy-Item (Join-Path $root 'README.md') (Join-Path $bundle 'README.md') -Force
foreach ($name in 'run-sokoban.ps1', 'run-sokoban.cmd', 'stop-sokoban.cmd') {
    Copy-Item (Join-Path $root "scripts\$name") (Join-Path $bundle $name) -Force
}
Compress-Archive -Path $bundle -DestinationPath (Join-Path $relDir "sokoban-$version.zip") -Force

# 前端静态文件（分离部署用）
Compress-Archive -Path $distDir -DestinationPath (Join-Path $relDir "sokoban-frontend-$version.zip") -Force

# 单独的一键脚本（想直接下脚本的人）
foreach ($name in 'run-sokoban.ps1', 'run-sokoban.cmd', 'stop-sokoban.cmd') {
    Copy-Item (Join-Path $root "scripts\$name") (Join-Path $relDir $name) -Force
}

Remove-Item (Join-Path $relDir 'bundle') -Recurse -Force
Remove-Item (Join-Path $relDir 'stage') -Recurse -Force -ErrorAction SilentlyContinue

Write-Step '产物清单'
Get-ChildItem $relDir -File | Sort-Object Name | ForEach-Object {
    Write-Host ("  {0,-32} {1,8:N2} MB" -f $_.Name, ($_.Length / 1MB))
}
Write-Host ''
