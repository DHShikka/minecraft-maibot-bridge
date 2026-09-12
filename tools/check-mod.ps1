# 模组侧的验证脚本（纯 JVM，不需要启动游戏）
#
# 用法：在仓库根目录执行  powershell -File tools\check-mod.ps1
# 或在已有 shell 里：
#   . .\tools\check-mod.ps1
#
# 做四件事：
#   1. 用 javac 直接编译模组源码（报错是 UTF-8，比 Gradle 转发出来的可读）
#   2. 打印 Forge 真实生成的默认配置文件，并断言分节结构
#   3. 跑 A* 寻路的单元测试（假世界，22 项）
#   4. 编译并跑「模组 WebSocket 客户端 ↔ 插件 WebSocket 服务端」的互通冒烟测试的前置编译
#
# 说明：本脚本用 & 调用外部程序，不依赖 PowerShell 执行策略以外的功能。

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jdk = Join-Path $root '.toolchain\jdk\jdk-17.0.2'
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'
$mod = Join-Path $root 'mc-mod'
$out = Join-Path $mod 'build\javac-check'

$cpFile = Join-Path $mod 'build\compileClasspath.txt'
if (-not (Test-Path $cpFile)) {
    Write-Host '缺少 compileClasspath.txt，先执行: cd mc-mod; .\gradlew.bat printCompileClasspath'
    exit 1
}
$cp = (Get-Content $cpFile -Raw -Encoding UTF8).Trim()
$full = "$cp;$out;$(Join-Path $root 'tools\javatest\out')"

Write-Host '=== 1) 编译模组源码 ==='
& $javac '-J-Dfile.encoding=UTF-8' '-J-Dsun.stdout.encoding=UTF-8' "@$(Join-Path $mod 'build\javac-args.txt')"
if ($LASTEXITCODE -ne 0) { Write-Host '编译失败'; exit 1 }
Write-Host '编译通过'

Write-Host ''
Write-Host '=== 2) 配置分节结构 ==='
& $java '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' -cp $full com.mcai.bridge.ConfigDump
$configExit = $LASTEXITCODE

Write-Host ''
Write-Host '=== 3) A* 寻路单元测试 ==='
& $java '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' -cp $full com.mcai.bridge.nav.PathFinderTest
$pathExit = $LASTEXITCODE

Write-Host ''
Write-Host '=== 4) 跨语言互通测试用的 Java 客户端编译 ==='
& $javac '-J-Dfile.encoding=UTF-8' '-encoding' 'UTF-8' '-nowarn' -cp $full -d (Join-Path $root 'tools\javatest\out') (Join-Path $root 'tools\javatest\BridgeSmokeTest.java')
$smokeCompileExit = $LASTEXITCODE

Write-Host ''
Write-Host "汇总：config=$configExit pathfinder=$pathExit smokeCompile=$smokeCompileExit"
if ($configExit -ne 0 -or $pathExit -ne 0 -or $smokeCompileExit -ne 0) { exit 1 }
Write-Host '模组侧全部通过'
