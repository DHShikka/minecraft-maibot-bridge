# 直接用 javac 编译模组源码：报错信息是 UTF-8，可读性远好于 Gradle 的转发输出。
# 用法：pwsh -File tools\javac-check.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$mod = Join-Path $root 'mc-mod'
$jdk = Join-Path $root '.toolchain\jdk\jdk-17.0.2'
$javac = Join-Path $jdk 'bin\javac.exe'

$cpFile = Join-Path $mod 'build\compileClasspath.txt'
if (-not (Test-Path $cpFile)) {
    Write-Error "缺少 $cpFile，请先运行: gradlew printCompileClasspath"
}

$cp = (Get-Content $cpFile -Raw -Encoding UTF8).Trim()
$out = Join-Path $mod 'build\javac-check'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = Get-ChildItem (Join-Path $mod 'src\main\java') -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }

$argFile = Join-Path $mod 'build\javac-args.txt'
$lines = @('-encoding', 'UTF-8', '-nowarn', '-proc:none', '-d', "`"$out`"", '-classpath', "`"$cp`"")
$lines += $sources | ForEach-Object { "`"$_`"" }
Set-Content -Path $argFile -Value $lines -Encoding UTF8

Write-Host "编译 $($sources.Count) 个源文件..."
& $javac "-J-Dfile.encoding=UTF-8" "-J-Dsun.stdout.encoding=UTF-8" "-J-Dsun.stderr.encoding=UTF-8" "@$argFile"
$code = $LASTEXITCODE
Write-Host "javac 退出码: $code"
exit $code
