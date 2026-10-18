#Requires -Version 5.1
<#
.SYNOPSIS
    构建 BoardLive.jar(Java 17,零第三方依赖)。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\build.ps1
#>
[CmdletBinding()]
param(
    [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Get-JavacMajor {
    param([string]$Javac)
    $raw = & $Javac '-version' 2>&1 | Out-String
    if ($raw -match 'javac\s+(\d+)') { return [int]$Matches[1] }
    return 0
}

function Find-Jdk {
    $candidates = @()
    if ($JavaHome) { $candidates += $JavaHome }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $bases = @(
        'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Microsoft',
        'C:\Program Files\Zulu',
        'C:\Program Files\BellSoft',
        'C:\Program Files\Amazon Corretto',
        '/usr/lib/jvm',
        "$env:HOME/.sdkman/candidates/java"
    )
    foreach ($base in $bases) {
        if (Test-Path $base) {
            $candidates += (Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending | ForEach-Object { $_.FullName })
        }
    }
    foreach ($candidate in $candidates) {
        $javac = Join-Path $candidate 'bin/javac.exe'
        $jar = Join-Path $candidate 'bin/jar.exe'
        if (-not (Test-Path $javac)) { $javac = Join-Path $candidate 'bin/javac'; $jar = Join-Path $candidate 'bin/jar' }
        if ((Test-Path $javac) -and (Test-Path $jar)) {
            $major = Get-JavacMajor $javac
            if ($major -ge 17) {
                return [pscustomobject]@{ Home = $candidate; Javac = $javac; Jar = $jar; Major = $major }
            }
        }
    }
    throw '未找到 JDK 17 或更高版本。请安装 JDK 17+ 并设置 JAVA_HOME,或使用 -JavaHome 指定路径。'
}

$jdk = Find-Jdk
Write-Host "JDK      : $($jdk.Home) (javac $($jdk.Major))" -ForegroundColor Cyan

$classes = Join-Path $root 'build/classes'
$dist = Join-Path $root 'dist'
$jarFile = Join-Path $dist 'BoardLive.jar'

Remove-Item $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $dist | Out-Null

$sources = @(Get-ChildItem (Join-Path $root 'src/main/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
if ($sources.Count -eq 0) { throw '没有找到任何 .java 源文件。' }
Write-Host "源文件   : $($sources.Count) 个" -ForegroundColor Cyan

$commonArgs = @('-encoding', 'UTF-8', '-d', $classes)
& $jdk.Javac '--release', '17' @commonArgs @sources
if ($LASTEXITCODE -ne 0) {
    Write-Host '--release 17 编译失败,改用默认目标版本重试...' -ForegroundColor Yellow
    & $jdk.Javac @commonArgs @sources
    if ($LASTEXITCODE -ne 0) { throw '编译失败。' }
}

Remove-Item $jarFile -Force -ErrorAction SilentlyContinue
& $jdk.Jar 'cfe' $jarFile 'mcboard.Main' '-C' $classes '.'
if ($LASTEXITCODE -ne 0) { throw '打包失败。' }

# config.yml / Board.xaml 只在不存在时复制,避免覆盖你已改好的配置
foreach ($name in @('config.yml', 'Board.xaml')) {
    $target = Join-Path $dist $name
    if (-not (Test-Path $target)) {
        Copy-Item (Join-Path $root $name) $target
    }
}
Copy-Item (Join-Path $root 'README.md') $dist -Force -ErrorAction SilentlyContinue

$sizeKb = [math]::Round((Get-Item $jarFile).Length / 1KB, 1)
Write-Host ''
Write-Host "构建完成: $jarFile ($sizeKb KB)" -ForegroundColor Green
Write-Host "配套文件: $dist\config.yml, $dist\Board.xaml" -ForegroundColor Green
Write-Host ''
Write-Host '验证:' -ForegroundColor Cyan
& (Join-Path $jdk.Home 'bin/java.exe') '-jar' $jarFile '-v'
