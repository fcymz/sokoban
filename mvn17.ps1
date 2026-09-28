# 用 JDK 17 跑 Maven（本项目的便捷入口）。
#
# 背景：系统级 JAVA_HOME 指向 JDK 8，而用户级同名变量会被系统级压住，
# 所以在终端里直接敲 mvn 会用错 JDK。这个脚本把当前会话的 JAVA_HOME
# 指到项目需要的 JDK 17，再执行 maven，不改动机器上的任何全局配置。
#
# 用法：
#   .\mvn17.ps1                       # 等价于 mvn
#   .\mvn17.ps1 -v                    # 看 maven 版本
#   .\mvn17.ps1 spring-boot:run       # 启动后端
#   .\mvn17.ps1 -DskipTests package   # 打包
#
# 也可以先「点源」进当前会话，之后这个终端里的 mvn 就都是 JDK 17：
#   . .\mvn17.ps1 -Only
#
# 如果提示「禁止运行脚本」，用这条绕过（仅对本次生效）：
#   powershell -ExecutionPolicy Bypass -File .\mvn17.ps1 -v

# 刻意不使用 param([switch]$Only)，因为 -B、-DskipTests 这类 maven 参数
# 会被 PowerShell 当成开关的缩写去匹配（例如 -B 会被吃成 -Only），
# 导致参数丢失。这里用 $args 原样透传。
$only = $false
$mavenArgs = @()
foreach ($item in $args) {
    if ($item -eq '-Only') {
        $only = $true
    } else {
        $mavenArgs += $item
    }
}

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $root 'scripts\jdk.ps1')

if (-not (Test-Path (Join-Path $env:SOKOBAN_JDK_HOME 'bin\java.exe'))) {
    Write-Error "找不到 JDK：$env:SOKOBAN_JDK_HOME`n请修改 scripts\jdk.ps1 里的路径。"
}

$env:JAVA_HOME = $env:SOKOBAN_JDK_HOME

# java -version 会把版本写到 stderr，直接捕获会触发 ErrorActionPreference='Stop'，
# 所以先临时放宽，再恢复。
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$version = (& (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1 | Select-Object -First 1)
$ErrorActionPreference = $previous
Write-Host "JAVA_HOME = $env:JAVA_HOME" -ForegroundColor DarkGray
Write-Host "$version" -ForegroundColor DarkGray

if ($only) {
    Write-Host "（-Only：只设置当前会话，未执行 maven）" -ForegroundColor DarkGray
    return
}

Push-Location $root
try {
    & mvn @mavenArgs
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
