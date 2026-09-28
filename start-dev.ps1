# 一键启动前后端（开发模式）。
#
# 做的事情：
#   1. 检查 JDK 17 / Node / npm；
#   2. 首次运行时装前端依赖（npm install）；
#   3. 用 Maven 打后端可执行 jar（已是最新则跳过）；
#   4. 直接拉起后端 java -jar（:8080）和前端 vite（:5173）；
#   5. 两个都探活成功后自动打开浏览器。
#
# 为什么不用 mvn spring-boot:run / npm run dev：
#   那两个命令都会再 fork 子进程，脚本拿到的 PID 不是真正干活的进程，
#   停止时容易留下孤儿进程占着端口。这里直接运行 java / node，
#   一个 PID 对应一个服务，启停都干净。
#
# 用法：
#   .\start-dev.ps1                 # 启动并打开浏览器
#   .\start-dev.ps1 -NoBrowser      # 只启动
#   .\start-dev.ps1 -Rebuild        # 强制重新打后端 jar
#   .\start-dev.ps1 -SkipInstall    # 跳过 npm install 检查
#
# 停止：.\stop-dev.ps1   （或关闭本窗口后执行）
# 日志：target\dev-logs\

param(
    [switch]$NoBrowser,
    [switch]$SkipInstall,
    [switch]$Rebuild
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $root 'target\dev-logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$backendPort = 8080
$frontendPort = 5173
$jarName = 'test-1.0-SNAPSHOT.jar'
$jarPath = Join-Path $root "target\$jarName"
$frontendDir = Join-Path $root 'frontend'
$viteEntry = Join-Path $frontendDir 'node_modules\vite\bin\vite.js'
$backendLog = Join-Path $logDir 'backend.log'
$frontendLog = Join-Path $logDir 'frontend.log'
$pidFile = Join-Path $logDir 'dev-pids.txt'

function Write-Step($text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Write-Ok($text) { Write-Host "  [OK]   $text" -ForegroundColor Green }
function Write-Bad($text) { Write-Host "  [失败] $text" -ForegroundColor Red }

function Test-PortBusy([int]$port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Http([string]$url, [int]$timeoutSeconds, [string]$what) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
            if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 400) { return $true }
        } catch { }
        Start-Sleep -Milliseconds 700
    }
    Write-Bad "$what 在 $timeoutSeconds 秒内没有就绪（$url）"
    return $false
}

function Show-LogTail([string]$path, [int]$lines) {
    if (Test-Path $path) {
        Write-Host "  日志尾部（$path）：" -ForegroundColor Yellow
        Get-Content $path -Tail $lines -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    $_" }
    }
}

# 启动失败时统一收尾
$started = @()
function Cleanup-All {
    foreach ($p in $script:started) {
        Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}
trap {
    Write-Bad "启动过程中出错：$_"
    Cleanup-All
    exit 1
}

# ---- 1. 环境检查 ----
Write-Step '检查环境'
. (Join-Path $root 'scripts\jdk.ps1')
$jdkHome = $env:SOKOBAN_JDK_HOME
$javaExe = Join-Path $jdkHome 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    Write-Bad "找不到 JDK：$jdkHome"
    Write-Host '        请修改 scripts\jdk.ps1 里的路径后重试。' -ForegroundColor Yellow
    exit 1
}
$env:JAVA_HOME = $jdkHome
Write-Ok "JDK 17: $jdkHome"

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Bad '找不到 node，请先安装 Node.js 18+'
    exit 1
}
Write-Ok "node: $(& node -v)"
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    Write-Bad '找不到 npm'
    exit 1
}
Write-Ok "npm: $(& npm -v)"

if (Test-PortBusy $backendPort) {
    Write-Bad "端口 $backendPort 已被占用，可能后端已经在跑。先执行 .\stop-dev.ps1 再试。"
    exit 1
}
if (Test-PortBusy $frontendPort) {
    Write-Bad "端口 $frontendPort 已被占用，可能前端已经在跑。先执行 .\stop-dev.ps1 再试。"
    exit 1
}
Write-Ok "端口 $backendPort / $frontendPort 都空闲"

# ---- 2. 前端依赖 ----
if (-not $SkipInstall) {
    Write-Step '准备前端依赖'
    if (Test-Path (Join-Path $frontendDir 'node_modules')) {
        Write-Ok 'node_modules 已存在，跳过 npm install'
    } else {
        Write-Host '  首次运行，正在执行 npm install（可能要一两分钟）…'
        Push-Location $frontendDir
        try {
            & npm install
            if ($LASTEXITCODE -ne 0) { Write-Bad 'npm install 失败'; exit 1 }
        } finally { Pop-Location }
        Write-Ok 'npm install 完成'
    }
}
if (-not (Test-Path $viteEntry)) {
    Write-Bad "找不到 $viteEntry，请先在 frontend 目录执行 npm install"
    exit 1
}

# ---- 3. 后端 jar ----
Write-Step '准备后端 jar'
$needBuild = $Rebuild -or (-not (Test-Path $jarPath))
if (-not $needBuild) {
    # jar 比任何一个 java 源文件旧，就重新打
    $jarTime = (Get-Item $jarPath).LastWriteTimeUtc
    $newer = Get-ChildItem -Recurse -File (Join-Path $root 'src\main') -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTimeUtc -gt $jarTime } | Select-Object -First 1
    if ($newer) {
        Write-Host "  源码有更新（$($newer.Name)），需要重新打包…"
        $needBuild = $true
    } else {
        Write-Ok "jar 已是最新，跳过打包（$jarName）"
    }
}
if ($needBuild) {
    Write-Host '  正在打包（mvn -DskipTests package，首次会慢一些）…'
    Push-Location $root
    try {
        & (Join-Path $root 'mvn17.ps1') -B -q -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            Write-Bad '后端打包失败，请看上面的 Maven 输出'
            exit 1
        }
    } finally { Pop-Location }
    Write-Ok "打包完成（$([math]::Round((Get-Item $jarPath).Length / 1MB, 1)) MB）"
}

# ---- 4. 启动后端 ----
Write-Step "启动后端（:$backendPort）"
Remove-Item $backendLog -Force -ErrorAction SilentlyContinue
$backend = Start-Process -FilePath $javaExe -PassThru -WindowStyle Hidden -ArgumentList @(
    '-Dfile.encoding=UTF-8', '-jar', $jarPath, "--server.port=$backendPort"
) -RedirectStandardOutput $backendLog -RedirectStandardError "$backendLog.err" `
  -WorkingDirectory $root
$started += $backend.Id
Write-Ok "后端已拉起（PID $($backend.Id)）"

if (-not (Wait-Http "http://127.0.0.1:$backendPort/api/levels" 90 '后端')) {
    Show-LogTail $backendLog 20
    Show-LogTail "$backendLog.err" 15
    Cleanup-All
    exit 1
}
Write-Ok "后端就绪：http://127.0.0.1:$backendPort"

# ---- 5. 启动前端 ----
Write-Step "启动前端（:$frontendPort）"
Remove-Item $frontendLog -Force -ErrorAction SilentlyContinue
$frontend = Start-Process -FilePath 'node' -PassThru -WindowStyle Hidden -ArgumentList @(
    $viteEntry, '--port', "$frontendPort", '--strictPort', '--configLoader', 'native'
) -RedirectStandardOutput $frontendLog -RedirectStandardError "$frontendLog.err" `
  -WorkingDirectory $frontendDir
$started += $frontend.Id
Write-Ok "前端已拉起（PID $($frontend.Id)）"

if (-not (Wait-Http "http://127.0.0.1:$frontendPort/" 60 '前端')) {
    Show-LogTail $frontendLog 20
    Show-LogTail "$frontendLog.err" 15
    Cleanup-All
    exit 1
}
Write-Ok "前端就绪：http://127.0.0.1:$frontendPort/"

# ---- 6. 收尾 ----
"$($backend.Id)`n$($frontend.Id)" | Out-File -Encoding ascii $pidFile

# 顺手确认前端到后端的代理是通的
try {
    $probe = Invoke-WebRequest -Uri "http://127.0.0.1:$frontendPort/api/levels" -UseBasicParsing -TimeoutSec 8
    if ($probe.StatusCode -eq 200) {
        Write-Ok '前端 /api 代理已连通后端'
    }
} catch {
    Write-Host "  [注意] 前端 /api 代理探测失败，浏览器里可能调不通接口" -ForegroundColor Yellow
}

$url = "http://localhost:$frontendPort/"
if (-not $NoBrowser) {
    Start-Process $url
    Write-Ok "已打开浏览器：$url"
}

Write-Host ''
Write-Host "前后端都跑起来了 —— 浏览器打开 $url 即可开始游戏。" -ForegroundColor Green
Write-Host ''
Write-Host "  后端 : http://localhost:$backendPort   (PID $($backend.Id))" -ForegroundColor DarkGray
Write-Host "  前端 : $url  (PID $($frontend.Id))" -ForegroundColor DarkGray
Write-Host "  日志 : $logDir" -ForegroundColor DarkGray
Write-Host ''
Write-Host '停止：.\stop-dev.ps1' -ForegroundColor DarkGray
Write-Host ''
exit 0
