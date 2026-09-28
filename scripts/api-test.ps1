$ErrorActionPreference = 'Continue'
# 后端地址：脚本的第一个参数可以覆盖，默认 8080
$base = if ($args.Count -ge 1 -and $args[0]) { $args[0] } else { 'http://127.0.0.1:8080' }
$script:failed = 0

$tmp = Join-Path $env:TEMP 'sokoban-api-body.json'
function Write-Body {
    param([string]$Json)
    [System.IO.File]::WriteAllText($tmp, $Json, (New-Object System.Text.UTF8Encoding($false)))
    return "@$tmp"
}
# 注意：这里必须返回「字符串」而不是字符串数组，
# 否则调用方写 `... | ConvertFrom-Json` 会报 Invalid JSON primitive。
function Invoke-Api {
    param([string]$Method, [string]$Path, [string]$Json)
    $curlArgs = @('-sS', '--max-time', '30', '-X', $Method, "$base$Path",
                  '-H', 'Content-Type: application/json')
    if ($Json) {
        $curlArgs += @('--data-binary', (Write-Body $Json))
    }
    $raw = & curl.exe @curlArgs 2>&1
    return ([string]::Join("`n", [string[]]$raw)).Trim()
}
function Get-Code {
    param([string]$Method, [string]$Path, [string]$Json)
    $curlArgs = @('-sS', '--max-time', '30', '-o', 'NUL', '-w', '%{http_code}',
                  '-X', $Method, "$base$Path", '-H', 'Content-Type: application/json')
    if ($Json) {
        $curlArgs += @('--data-binary', (Write-Body $Json))
    }
    $raw = & curl.exe @curlArgs 2>&1
    return ([string]::Join('', [string[]]$raw)).Trim()
}
function Check($name, $cond, $detail) {
    if ($cond) { Write-Output "  [OK]   $name" }
    else { Write-Output "  [FAIL] $name -- $detail"; $script:failed++ }
}


$BODY_RIGHT = '{"dir":"RIGHT"}'
$BODY_UP = '{"dir":"UP"}'
$BODY_BAD_DIR = '{"dir":"SIDEWAYS"}'
$BODY_L200 = '{"index":200}'
$BODY_L3 = '{"index":2}'
$BODY_NEXT = '{"delta":1}'
$BODY_SEED = '{"seedCode":"7K3M-9QPZ","endless":true}'
$BODY_BAD_SEED = '{"seedCode":"!!!","endless":false}'

$state = (Invoke-Api 'POST' '/api/game/sessions' '{}') | ConvertFrom-Json
$sid = $state.sessionId
Write-Output "session = $sid"

Write-Output '== 移动 / 撤销 / 重来 =='
$m1 = (Invoke-Api 'POST' "/api/game/sessions/$sid/moves" $BODY_RIGHT) | ConvertFrom-Json
Check '走一步后 steps 增加' ($m1.steps -eq $state.steps + 1) "steps=$($m1.steps)"
Check '走一步后 undoCount 增加' ($m1.undoCount -ge 1) "undo=$($m1.undoCount)"
Check 'player 位置随之改变' ($m1.player -ne $state.player) "player=$($m1.player)"
$u1 = (Invoke-Api 'POST' "/api/game/sessions/$sid/undo" $null) | ConvertFrom-Json
Check '撤销后 steps 回到 0' ($u1.steps -eq 0) "steps=$($u1.steps)"
Check '撤销后玩家回到起点' ($u1.player -eq $state.player) "player=$($u1.player)"
$null = Invoke-Api 'POST' "/api/game/sessions/$sid/moves" $BODY_RIGHT
$r1 = (Invoke-Api 'POST' "/api/game/sessions/$sid/reset" $null) | ConvertFrom-Json
Check '重来后 steps 归零' ($r1.steps -eq 0) "steps=$($r1.steps)"

Write-Output '== 关卡切换与门控 =='
Check '非法方向返回 400' ((Get-Code 'POST' "/api/game/sessions/$sid/moves" $BODY_BAD_DIR) -eq '400')
$lockedCode = Get-Code 'POST' "/api/game/sessions/$sid/level" $BODY_L200
Check '远超解锁范围的无尽层返回 403' ($lockedCode -eq '403') "code=$lockedCode"
$l3 = (Invoke-Api 'POST' "/api/game/sessions/$sid/level" $BODY_L3) | ConvertFrom-Json
Check '可以跳到第 3 关' ($l3.levelIndex -eq 2) "index=$($l3.levelIndex)"
$n3 = (Invoke-Api 'POST' "/api/game/sessions/$sid/level" $BODY_NEXT) | ConvertFrom-Json
Check '内置关卡可以往后走' ($n3.levelIndex -eq 3) "index=$($n3.levelIndex)"

Write-Output '== 无尽模式与种子 =='
$e1 = (Invoke-Api 'POST' '/api/game/sessions' $BODY_SEED) | ConvertFrom-Json
Check '带种子直接开无尽第 1 层' ($e1.endless -and $e1.endlessNumber -eq 1) "n=$($e1.endlessNumber)"
Check '种子回显与输入一致' ($e1.seedCode -eq '7K3M-9QPZ') "seed=$($e1.seedCode)"
Check '无尽关卡启用了配对' ($e1.level.paired -eq $true) "paired=$($e1.level.paired)"
$e2 = (Invoke-Api 'POST' '/api/game/sessions' $BODY_SEED) | ConvertFrom-Json
Check '同种子生成同一张地图' (($e1.level.walls -join ',') -eq ($e2.level.walls -join ',')) 'walls differ'
Check '同种子箱子起点一致' (($e1.boxes -join ',') -eq ($e2.boxes -join ',')) 'boxes differ'
Check '非法种子返回 400' ((Get-Code 'POST' '/api/game/sessions' $BODY_BAD_SEED) -eq '400')

Write-Output '== 关卡列表 =='
$lv = (Invoke-Api 'GET' '/api/levels' $null) | ConvertFrom-Json
Check '内置关卡返回 10 条' ($lv.Count -eq 10) "count=$($lv.Count)"
Check '第 1 条标题含「第 1 关」' ("$($lv[0].title)" -like '*第 1 关*') "title=$($lv[0].title)"
Check '第 1 条进度为 1/10' ("$($lv[0].shortProgress)" -eq '1/10') "progress=$($lv[0].shortProgress)"
Check '内置关卡都不标记为无尽' (($lv | Where-Object { $_.endless }).Count -eq 0) 'some endless'

Write-Output '== 提示 =='
$h = (Invoke-Api 'POST' "/api/game/sessions/$sid/hint" $null) | ConvertFrom-Json
Check '提示返回非空计划' ($h.plan.Count -gt 0) "plan=$($h.plan.Count)"
Check '提示步骤都是合法方向' (($h.plan | Where-Object { $_ -notin @('UP','DOWN','LEFT','RIGHT') }).Count -eq 0) 'bad dir'
Check '提示附带最新快照' ($null -ne $h.state.sessionId) 'no state'
$sid3 = $h.state.sessionId
foreach ($d in $h.plan) { $null = Invoke-Api 'POST' "/api/game/sessions/$sid3/moves" "{""dir"":""$d""}" }
$afterHint = (Invoke-Api 'GET' "/api/game/sessions/$sid3" $null) | ConvertFrom-Json
Check '按提示走完确实通关' ($afterHint.won -eq $true) "won=$($afterHint.won)"

Write-Output '== 存档 =='
$sv = (Invoke-Api 'GET' '/api/saves' $null) | ConvertFrom-Json
Check '存档槽固定 8 个' ($sv.Count -eq 8) "count=$($sv.Count)"
$null = Invoke-Api 'POST' "/api/game/sessions/$sid/moves" $BODY_RIGHT
$null = Invoke-Api 'POST' "/api/game/sessions/$sid/moves" $BODY_UP
$cur = (Invoke-Api 'GET' "/api/game/sessions/$sid" $null) | ConvertFrom-Json
$savedList = (Invoke-Api 'POST' "/api/saves/3/from/$sid" $null) | ConvertFrom-Json
$sv3 = $savedList | Where-Object { $_.slot -eq 3 }
Check '存档后槽位 3 有记录' ($sv3.exists -eq $true) "exists=$($sv3.exists)"
Check '存档记录了当前步数' ($sv3.steps -eq $cur.steps) "slot=$($sv3.steps) cur=$($cur.steps)"
Check '存档记录了关卡下标' ($sv3.levelIndex -eq $cur.levelIndex) "slot=$($sv3.levelIndex)"
Check '存档回显了种子码' ("$($sv3.seedCode)" -match '^[0-9A-Z]{4}-[0-9A-Z]{4}$') "seed=$($sv3.seedCode)"

Write-Output '== 读档（局面必须与存档时一致） =='
$ld = (Invoke-Api 'POST' '/api/saves/3/load' $null) | ConvertFrom-Json
Check '读档产生新会话' ($ld.sessionId -ne $sid) 'same session'
Check '读档后关卡一致' ($ld.levelIndex -eq $cur.levelIndex) "lvl=$($ld.levelIndex) exp=$($cur.levelIndex)"
Check '读档后步数一致' ($ld.steps -eq $cur.steps) "steps=$($ld.steps) exp=$($cur.steps)"
Check '读档后玩家位置一致' ($ld.player -eq $cur.player) "player=$($ld.player) exp=$($cur.player)"
Check '读档后箱子位置一致' (($ld.boxes -join ',') -eq ($cur.boxes -join ',')) "boxes=$($ld.boxes -join ',') exp=$($cur.boxes -join ',')"
Check '读档后棋盘尺寸一致' ($ld.level.width -eq $cur.level.width) "width=$($ld.level.width)"

Write-Output '== 空槽位与删档 =='
Check '读空槽位返回 400' ((Get-Code 'POST' '/api/saves/7/load' $null) -eq '400')
$dv = ((Invoke-Api 'DELETE' '/api/saves/3' $null) | ConvertFrom-Json) | Where-Object { $_.slot -eq 3 }
Check '删档后槽位 3 为空' ($dv.exists -eq $false) "exists=$($dv.exists)"
Check '未知会话返回 404' ((Get-Code 'GET' '/api/game/sessions/not-a-session' $null) -eq '404')

Write-Output '== 已下线的成绩榜接口 =='
Check 'GET /api/scores 返回 404' ((Get-Code 'GET' '/api/scores' $null) -eq '404')
Check '随便一个不存在的路径也是 404' ((Get-Code 'GET' '/api/nope' $null) -eq '404')

Write-Output ''
Write-Output "结果：失败 $script:failed 项"
exit $script:failed