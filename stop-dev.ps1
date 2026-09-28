# 停掉 start-dev.ps1 起的前后端服务。
#
# 做法（按“先快后慢”的顺序，尽量少调系统接口）：
#   1. 读 start-dev.ps1 记下的 PID，一次性拉出进程树（只查一次 CIM），从叶子往根收；
#   2. 如果 PID 文件丢了，用 netstat 按 8080 / 5173 找占用者再收（共两次 netstat）；
#   3. 用轮询确认端口释放，而不是睡眠固定时间。
#
# 为什么不直接用 Get-NetTCPConnection / 递归 Get-CimInstance：
#   实测 Get-NetTCPConnection 首次调用约 1057ms、之后每次约 270ms，
#   而 netstat -ano 每次只要 16~44ms（快 6~15 倍）；
#   递归查子进程会把 CIM 查询次数放大，进程树一大就很慢。
#   改完之后整个停止过程通常 1~2 秒返回（原来约 6 秒）。
#
# 用法：
#   .\stop-dev.ps1              停止并打印结果
#   .\stop-dev.ps1 -Quiet       只打印一行结果
#   .\stop-dev.ps1 -All         额外收掉所有 mvn / 本项目 JDK 起的 java 进程（清理测试残留）

param(
    [switch]$Quiet,
    [switch]$All
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidFile = Join-Path $root 'target\dev-logs\dev-pids.txt'
$ports = @(8080, 5173)
$watch = [System.Diagnostics.Stopwatch]::StartNew()

function Say([string]$text, [string]$color = 'Gray') {
    if (-not $Quiet) { Write-Host $text -ForegroundColor $color }
}

# ---- 一次 netstat，拿到所有监听端口 -> pid（后面都复用这份结果） ----
function Get-Listeners {
    $map = @{}
    $lines = netstat -ano -p tcp 2>$null
    foreach ($line in $lines) {
        if ($line -notmatch '\sLISTENING\s+(\d+)\s*$') { continue }
        $procId = [int]$Matches[1]
        if ($line -match '[:.](\d+)\s+\S+\s+LISTENING') {
            $port = [int]$Matches[1]
            if (-not $map.ContainsKey($port)) { $map[$port] = $procId }
        }
    }
    return $map
}

# ---- 一次 CIM，建立 父pid -> 子pid列表 的映射（避免递归重复查询） ----
function Get-ChildMap {
    $map = @{}
    $all = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Select-Object ProcessId, ParentProcessId
    foreach ($row in $all) {
        $parent = [int]$row.ParentProcessId
        if (-not $map.ContainsKey($parent)) { $map[$parent] = New-Object System.Collections.ArrayList }
        [void]$map[$parent].Add([int]$row.ProcessId)
    }
    return $map
}

# ---- 收集一棵进程树（从给定 pid 出发的所有后代，含自己）----
function Collect-Tree([int]$rootId, $childMap) {
    $result = New-Object System.Collections.ArrayList
    $stack = New-Object System.Collections.Stack
    $stack.Push($rootId)
    while ($stack.Count -gt 0) {
        $cur = [int]$stack.Pop()
        if ($result.Contains($cur)) { continue }
        [void]$result.Add($cur)
        if ($childMap.ContainsKey($cur)) {
            foreach ($child in $childMap[$cur]) { $stack.Push($child) }
        }
    }
    return $result
}

function Stop-Ids($ids, [string]$why, $childMap) {
    $killed = New-Object System.Collections.ArrayList
    foreach ($procId in $ids) {
        foreach ($treeId in (Collect-Tree $procId $childMap)) {
            if ($killed.Contains($treeId)) { continue }
            # 后收的（子树里的）先杀，父进程最后杀，避免留下孤儿
            [void]$killed.Add($treeId)
        }
    }
    # 反转后先杀叶子
    $ordered = @($killed)
    [array]::Reverse($ordered)
    $n = 0
    foreach ($procId in $ordered) {
        if (Get-Process -Id $procId -ErrorAction SilentlyContinue) {
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            $n++
        }
    }
    if ($n -gt 0) { Say "  已停止 $n 个进程（$why）" 'Green' }
    return $n
}

Say ''
Say '== 停止前后端服务' 'Cyan'

$stopped = 0
$targets = New-Object System.Collections.ArrayList

# ---- 1. PID 文件 ----
if (Test-Path $pidFile) {
    foreach ($line in (Get-Content $pidFile)) {
        $procId = 0
        if ([int]::TryParse($line.Trim(), [ref]$procId) -and $procId -gt 0) {
            [void]$targets.Add($procId)
        }
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

# ---- 2. 端口兜底（复用同一份 netstat 结果）----
$listeners = Get-Listeners
foreach ($port in $ports) {
    if ($listeners.ContainsKey($port)) { [void]$targets.Add([int]$listeners[$port]) }
}

if ($targets.Count -gt 0) {
    $childMap = Get-ChildMap
    $stopped += Stop-Ids $targets '来自 PID 记录与端口占用' $childMap
}

# ---- 3. 可选：清理测试残留 ----
if ($All) {
    $childMap = Get-ChildMap
    $extra = New-Object System.Collections.ArrayList
    Get-Process java, mvn -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -like '*ms-17*' -or $_.ProcessName -eq 'mvn' } |
        ForEach-Object { [void]$extra.Add($_.Id) }
    if ($extra.Count -gt 0) { $stopped += Stop-Ids $extra '清理 mvn / JDK17 残留' $childMap }
}

# ---- 4. 轮询确认端口释放（不再盲等 2 秒）----
$deadline = (Get-Date).AddSeconds(10)
do {
    Start-Sleep -Milliseconds 200
    $listeners = Get-Listeners
    $busy = @($ports | Where-Object { $listeners.ContainsKey($_) })
} while ($busy.Count -gt 0 -and (Get-Date) -lt $deadline)

Say ''
if ($Quiet) {
    Write-Host "  已停止 $stopped 个进程，端口 $($ports -join '/') 均已释放（$($watch.ElapsedMilliseconds) ms）" -ForegroundColor Green
} else {
    foreach ($port in $ports) {
        if ($listeners.ContainsKey($port)) {
            Write-Host "  端口 $port 仍被占用（pid $($listeners[$port])，可能不是本脚本起的服务）" -ForegroundColor Yellow
        } else {
            Write-Host "  端口 $port 已释放" -ForegroundColor Green
        }
    }
    Write-Host ''
    if ($stopped -eq 0) {
        Write-Host '没有找到需要停止的服务。' -ForegroundColor DarkGray
    } else {
        Write-Host "共停止 $stopped 个进程，耗时 $($watch.ElapsedMilliseconds) ms。" -ForegroundColor Green
    }
    Write-Host ''
}
