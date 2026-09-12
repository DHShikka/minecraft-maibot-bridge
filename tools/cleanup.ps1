<#
  收尾清理：把测试用的进程和内存都收干净。

  为什么要有它：一次真机测试会留下
    · 游戏客户端（3~4 GB）
    · Gradle 守护 + worker（编译完会一直驻留，合计 1 GB 上下）
    · play.py 驱动、run-client 的 node 启动器
  这些东西不会自己退，攒几轮就把内存吃满。

  用法：
    powershell -ExecutionPolicy Bypass -File tools/cleanup.ps1
    powershell -ExecutionPolicy Bypass -File tools/cleanup.ps1 -KeepGame   # 游戏留着，只清别的
    powershell -ExecutionPolicy Bypass -File tools/cleanup.ps1 -ForceGame  # 不等优雅退出，直接杀

  注意：**只杀我们自己的 node 进程**（命令行里带 run-client.mjs 的），
  DSH/其它工具的 node 一律不碰 —— 误杀会把正在跑的服务端一起带走。
#>
param(
    [switch]$KeepGame,      # 保留游戏客户端
    [switch]$ForceGame,     # 不尝试优雅退出，直接强杀游戏
    [int]$WaitSeconds = 150
)
$ErrorActionPreference = "Continue"

$root = Split-Path -Parent $PSScriptRoot
$gameDir = Join-Path $root "我的世界本体\versions\1.20.1-Forge_47.4.23"
$flag = Join-Path $gameDir "mcai-quit.flag"

function Get-FreeMB {
    return [math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1KB)
}
function Get-GameProcs {
    return @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
             Where-Object { $_.CommandLine -like "*--gameDir*" })
}

$before = Get-FreeMB
Write-Output ("清理前：可用内存 {0:N0} MB" -f $before)

# ---------------------------------------------------------------- 1) 游戏客户端
if (-not $KeepGame) {
    $games = Get-GameProcs
    if ($games.Count -eq 0) {
        Write-Output "游戏客户端：没在跑"
    } else {
        Write-Output ("游戏客户端：{0} 个（{1:N0} MB）" -f $games.Count,
            (($games | Measure-Object -Property WorkingSetSize -Sum).Sum / 1MB))
        if ($ForceGame) {
            $games | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
            Write-Output "  → 直接强杀（没存盘）"
        } else {
            # 优雅退出：放标记文件，模组会先存盘再退（强杀会丢还没写盘的进度）
            Remove-Item $flag -Force -ErrorAction SilentlyContinue
            New-Item -ItemType File -Path $flag -Force | Out-Null
            $deadline = (Get-Date).AddSeconds($WaitSeconds)
            while ((Get-Date) -lt $deadline) {
                if ((Get-GameProcs).Count -eq 0) { break }
                Start-Sleep -Seconds 3
            }
            $left = Get-GameProcs
            if ($left.Count -gt 0) {
                $left | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
                Write-Output ("  → 等了 {0} 秒还没退，强杀" -f $WaitSeconds)
            } else {
                Write-Output "  → 优雅退出（已存盘）"
            }
            Remove-Item $flag -Force -ErrorAction SilentlyContinue
        }
    }
} else {
    Write-Output "游戏客户端：按 -KeepGame 保留"
}

# ---------------------------------------------------------------- 2) play.py 驱动
$drivers = @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
             Where-Object { $_.CommandLine -like "*play.py*" })
foreach ($d in $drivers) { Stop-Process -Id $d.ProcessId -Force -ErrorAction SilentlyContinue }
Write-Output ("play.py 驱动：已停 {0} 个" -f $drivers.Count)

# ---------------------------------------------------------------- 3) run-client 的 node 启动器
$nodes = @(Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
           Where-Object { $_.CommandLine -like "*run-client.mjs*" })
foreach ($n in $nodes) { Stop-Process -Id $n.ProcessId -Force -ErrorAction SilentlyContinue }
Write-Output ("run-client 的 node：已停 {0} 个（其它 node 一律不碰）" -f $nodes.Count)

# ---------------------------------------------------------------- 4) Gradle 守护
$gw = Join-Path $root "mc-mod\gradlew.bat"
if (Test-Path $gw) {
    & cmd /c "`"$gw`" --stop" 2>&1 | Out-Null
}
$gradles = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
             Where-Object { $_.CommandLine -like "*GradleDaemon*" -or $_.CommandLine -like "*GradleWorkerMain*" })
foreach ($g in $gradles) { Stop-Process -Id $g.ProcessId -Force -ErrorAction SilentlyContinue }
Write-Output ("Gradle 守护/worker：已停 {0} 个" -f $gradles.Count)

# ---------------------------------------------------------------- 汇报
Start-Sleep -Seconds 2
$after = Get-FreeMB
Write-Output ("清理后：可用内存 {0:N0} MB（释放 {1:N0} MB）" -f $after, ($after - $before))
$still = Get-GameProcs
Write-Output ("游戏进程现在：{0}" -f $(if ($still.Count -eq 0) { "已全部关闭" } else { "$($still.Count) 个还在" }))
