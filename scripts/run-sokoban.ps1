<#
  推箱子 · Sokoban —— 一键运行脚本

  用法（在解压出来的目录里）：
    双击 run-sokoban.cmd              启动，并自动打开浏览器
    双击 stop-sokoban.cmd             停止
    run-sokoban.cmd -NoBrowser        启动但不打开浏览器
    run-sokoban.cmd -Port 9000        换个端口

  只需要 JDK / JRE 17 或更高版本。
  sokoban.jar 里已经打包了前端，所以只有这一个进程，前后端都在这一个端口上。
#>
param(
    [switch]$Stop,
    [switch]$NoBrowser,
    [int]$Port = 8080
)

$ErrorActionPreference = 'Stop'

$here = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Definition }
$jarPath = Join-Path $here 'sokoban.jar'
$logDir = Join-Path $here 'logs'
$outLog = Join-Path $logDir 'sokoban.log'
$errLog = Join-Path $logDir 'sokoban.err'
$pidFile = Join-Path $logDir 'sokoban.pid'
$url = "http://localhost:$Port/"

function Write-Step($text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Write-Ok($text) { Write-Host "  [OK]   $text" -ForegroundColor Green }
function Write-Warn($text) { Write-Host "  [注意] $text" -ForegroundColor Yellow }
function Write-Bad($text) { Write-Host "  [失败] $text" -ForegroundColor Red }

# 找出正在跑的那个进程：优先读 pid 文件，读不到就按端口找
function Get-RunningPid {
    if (Test-Path $pidFile) {
        $text = (Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        if ($text -and (Get-Process -Id $text -ErrorAction SilentlyContinue)) {
            return [int]$text
        }
    }
    $line = netstat -ano -p tcp | Select-String ":$Port\s" | Select-String 'LISTENING' | Select-Object -First 1
    if ($line) {
        $fields = @(($line.Line -split '\s+') | Where-Object { $_ })
        $candidate = $fields[$fields.Count - 1]
        if ($candidate -match '^\d+$') { return [int]$candidate }
    }
    return 0
}

# ---------------- 停止 ----------------

if ($Stop) {
    Write-Step "停止 Sokoban"
    $running = Get-RunningPid
    if ($running -gt 0) {
        Stop-Process -Id $running -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 600
        if (Get-Process -Id $running -ErrorAction SilentlyContinue) {
            Write-Bad "进程 $running 没能停掉，可以打开任务管理器手动结束"
        } else {
            Write-Ok "已停止（进程 $running），端口 $Port 已释放"
        }
    } else {
        Write-Warn "没有发现正在运行的服务"
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    exit 0
}

# ---------------- 启动 ----------------

Write-Host ''
Write-Host '  推箱子 · Sokoban' -ForegroundColor White
Write-Host '  ----------------' -ForegroundColor DarkGray

Write-Step '检查运行环境'

if (-not (Test-Path $jarPath)) {
    Write-Bad "找不到 sokoban.jar"
    Write-Host "         请把 run-sokoban.cmd / run-sokoban.ps1 和 sokoban.jar 放在同一个目录里。" -ForegroundColor DarkGray
    exit 1
}
Write-Ok "找到 sokoban.jar（$('{0:N1} MB' -f ((Get-Item $jarPath).Length / 1MB))）"

# 只认主版本号，1.8 这种老写法折算成 8
# 注意：java -version 是往 stderr 输出的，而本脚本开头设了 ErrorActionPreference=Stop，
# PowerShell 5.1 会把这段 stderr 当成终止性错误抛出来 —— 那样每个候选都会被吞成 0，
# 结果就是「明明装了 JDK 17 却说找不到」。所以这里必须临时放宽。
function Get-JavaMajor([string]$exe) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = (& $exe -version 2>&1 | Out-String)
    } catch {
        $output = ''
    } finally {
        $ErrorActionPreference = $previous
    }
    $m = [regex]::Match($output, 'version "(\d+)(?:\.(\d+))?')
    if (-not $m.Success) { return 0 }
    $major = [int]$m.Groups[1].Value
    if ($major -eq 1 -and $m.Groups[2].Success) { $major = [int]$m.Groups[2].Value }
    return $major
}

# 候选顺序：JAVA_HOME -> PATH -> 常见安装目录（很多人装了 JDK 但没配 JAVA_HOME）
$candidates = New-Object System.Collections.ArrayList
if ($env:JAVA_HOME) { [void]$candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
[void]$candidates.Add('java')

$jdkRoots = @(
    (Join-Path $env:ProgramFiles 'Java'),
    (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
    (Join-Path $env:ProgramFiles 'Microsoft'),
    (Join-Path $env:ProgramFiles 'Zulu'),
    (Join-Path $env:ProgramFiles 'BellSoft'),
    (Join-Path $env:ProgramFiles 'Amazon Corretto'),
    (Join-Path $env:ProgramFiles 'Semeru'),
    (Join-Path $env:ProgramFiles 'RedHat'),
    (Join-Path $env:ProgramFiles 'SapMachine'),
    (Join-Path $env:USERPROFILE '.jdks'),
    (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium'),
    (Join-Path $env:LOCALAPPDATA 'Programs\Microsoft')
)
foreach ($root in $jdkRoots) {
    if (-not $root -or -not (Test-Path $root)) { continue }
    Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $exe = Join-Path $_.FullName 'bin\java.exe'
        if (Test-Path $exe) { [void]$candidates.Add($exe) }
    }
}

# 取版本最高的那个够用的
$javaExe = $null
$javaMajor = 0
foreach ($candidate in $candidates) {
    $major = Get-JavaMajor $candidate
    if ($major -ge 17 -and $major -gt $javaMajor) {
        $javaExe = $candidate
        $javaMajor = $major
    }
}

if (-not $javaExe) {
    Write-Bad '没有找到 Java 17 或更高版本'
    Write-Host '         本项目需要 JDK 17+（Spring Boot 3.4 的要求）。' -ForegroundColor DarkGray
    Write-Host '         装好之后如果这个脚本还是找不到，就把环境变量 JAVA_HOME 指向 JDK 目录。' -ForegroundColor DarkGray
    Write-Host '         下载：https://adoptium.net/' -ForegroundColor DarkGray
    exit 1
}
Write-Ok "使用 Java $javaMajor：$javaExe"

$running = Get-RunningPid
if ($running -gt 0) {
    Write-Warn "端口 $Port 已经有服务在跑（进程 $running），直接用它"
    if (-not $NoBrowser) { Start-Process $url }
    exit 0
}
Write-Ok "端口 $Port 空闲"

Write-Step "启动服务（端口 $Port）"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Remove-Item $outLog, $errLog -Force -ErrorAction SilentlyContinue

$arguments = @('-Dfile.encoding=UTF-8', '-jar', $jarPath, "--server.port=$Port")
$process = Start-Process -FilePath $javaExe -ArgumentList $arguments -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WorkingDirectory $here
Set-Content -Path $pidFile -Value $process.Id -Encoding ASCII
Write-Ok "已拉起（进程 $($process.Id)），日志在 logs\ 目录"

Write-Host '  等待服务就绪' -NoNewline
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 500
    Write-Host '.' -NoNewline
    if ($process.HasExited) {
        Write-Host ''
        Write-Bad "服务启动后立刻退出了（退出码 $($process.ExitCode)）"
        if (Test-Path $errLog) {
            Write-Host '---------- 错误日志 ----------' -ForegroundColor DarkGray
            Get-Content $errLog -Tail 20 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
        }
        exit 1
    }
    try {
        $null = Invoke-WebRequest "http://127.0.0.1:$Port/api/levels" -UseBasicParsing -TimeoutSec 2
        $ready = $true
        break
    } catch {
        # 还没起来，继续等
    }
}
Write-Host ''

if (-not $ready) {
    Write-Bad '等了 30 秒服务还是没就绪'
    Write-Host "         看一下 $errLog" -ForegroundColor DarkGray
    exit 1
}
Write-Ok "后端接口已就绪：http://127.0.0.1:$Port/api/levels"

try {
    $page = Invoke-WebRequest "http://127.0.0.1:$Port/" -UseBasicParsing -TimeoutSec 5
    if ($page.Content -match 'id="app"') {
        Write-Ok '前端页面也在这一个端口上（jar 内嵌）'
    } else {
        Write-Warn '根路径没有返回前端页面，这个 jar 可能没内嵌前端'
    }
} catch {
    Write-Warn '根路径没有返回前端页面，这个 jar 可能没内嵌前端'
}

if (-not $NoBrowser) {
    Start-Process $url
    Write-Ok "已打开浏览器：$url"
}

Write-Host ''
Write-Host "  推箱子已经跑起来了 —— 打开 $url 即可开始游戏。" -ForegroundColor Green
Write-Host ''
Write-Host "  停止：双击 stop-sokoban.cmd（或 run-sokoban.cmd -Stop）" -ForegroundColor DarkGray
Write-Host "  端口：$Port    进程：$($process.Id)    日志：$logDir" -ForegroundColor DarkGray
Write-Host ''
