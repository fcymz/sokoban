# 停掉 start-dev.ps1 起的前后端服务。
#
#   1. 先按 start-dev.ps1 记下的 PID 精确结束（连子进程一起收）；
#   2. 再按端口 8080 / 5173 兜底，防止 PID 文件丢了或进程换了；
#   3. 最后确认端口是否真的释放。
#
# 用法：.\stop-dev.ps1

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidFile = Join-Path $root 'target\dev-logs\dev-pids.txt'

# 递归结束后代进程
function Stop-Tree([int]$processId) {
    $proc = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (-not $proc) { return $false }
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$processId" -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Tree ([int]$_.ProcessId) | Out-Null }
    Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    return $true
}

Write-Host ''
Write-Host '== 停止前后端服务' -ForegroundColor Cyan

$stopped = 0

if (Test-Path $pidFile) {
    foreach ($line in (Get-Content $pidFile)) {
        $id = 0
        if ([int]::TryParse($line.Trim(), [ref]$id) -and $id -gt 0) {
            if (Stop-Tree $id) {
                Write-Host "  已停止进程 $id" -ForegroundColor Green
                $stopped++
            }
        }
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
} else {
    Write-Host '  没找到 PID 记录，改用端口兜底' -ForegroundColor DarkGray
}

foreach ($port in 8080, 5173) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($conn in $conns) {
        $owner = [int]$conn.OwningProcess
        if ($owner -gt 0 -and (Stop-Tree $owner)) {
            Write-Host "  已停止占用端口 $port 的进程 $owner" -ForegroundColor Green
            $stopped++
        }
    }
}

Start-Sleep -Seconds 2
Write-Host ''
foreach ($port in 8080, 5173) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "  端口 $port 仍被占用（可能不是本脚本起的服务）" -ForegroundColor Yellow
    } else {
        Write-Host "  端口 $port 已释放" -ForegroundColor Green
    }
}
Write-Host ''
if ($stopped -eq 0) {
    Write-Host '没有找到需要停止的服务。' -ForegroundColor DarkGray
} else {
    Write-Host "共停止 $stopped 个进程。" -ForegroundColor Green
}
Write-Host ''
