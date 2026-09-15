# 屏幕苍蝇 App —— 开发环境一键安装
# 用法:  powershell -ExecutionPolicy Bypass -File tools\setup-android-env.ps1
#
# 全部装到 D:\Android 下，不碰系统里已有的 Java 25。
# 下载走华为云镜像（直连 Google/Adoptium 只有 80KB/s，镜像能到 3MB/s）。

$ErrorActionPreference = 'Continue'
$root      = 'D:\Android'
$dlDir     = "$root\dl"
$jdkDir    = "$root\jdk17"
$sdkDir    = "$root\sdk"
$gradleDir = "$root\gradle-8.9"

New-Item -ItemType Directory -Force -Path $dlDir, $sdkDir | Out-Null

# 判断一个 zip 是不是完整的（能打开且至少有一个条目）。
# 这一步很关键：网络中断会留下一个"看起来很大但其实是残缺的"文件，
# 如果只看文件大小就会误判成下载成功，后面解压时才炸，很难排查。
function Test-Zip($path) {
    if (-not (Test-Path $path)) { return $false }
    if ((Get-Item $path).Length -lt 1024) { return $false }
    try {
        $z = [System.IO.Compression.ZipFile]::OpenRead($path)
        $n = $z.Entries.Count
        $z.Dispose()
        return ($n -gt 0)
    } catch {
        return $false
    }
}

# 依次尝试多个镜像源下载，每个源失败后支持断点续传重试。
# $urls 可以传多个候选源，避免单点故障（华为云的 Gradle 源就会中途重置连接）。
function Get-File($urls, $out, $name) {
    if (Test-Zip $out) {
        Write-Host "  [跳过] $name 已完整下载" -ForegroundColor DarkGray
        return $true
    }
    if (Test-Path $out) {
        Write-Host "  [重下] $name 上次下载不完整，重新来" -ForegroundColor DarkYellow
        Remove-Item $out -Force -ErrorAction SilentlyContinue
    }

    if ($urls -is [string]) { $urls = @($urls) }

    foreach ($url in $urls) {
        if (Test-Zip $out) { break }
        $host_ = ([Uri]$url).Host
        Write-Host "  [下载] $name  <- $host_" -ForegroundColor Cyan

        for ($i = 1; $i -le 3; $i++) {
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            # 注意：不要加 --speed-limit，它会让慢速连接被判定为失败而中断
            & curl.exe -L --fail --retry 3 --retry-all-errors --retry-delay 3 `
                --connect-timeout 25 -C - -o $out $url 2>$null
            $code = $LASTEXITCODE
            $sw.Stop()

            if ($code -eq 0 -and (Test-Zip $out)) {
                $mb  = [math]::Round((Get-Item $out).Length / 1MB, 1)
                $sec = [math]::Max($sw.Elapsed.TotalSeconds, 0.1)
                Write-Host ("     -> {0} MB, 平均 {1} MB/s" -f $mb, [math]::Round($mb / $sec, 1)) -ForegroundColor Green
                break
            }

            $have = if (Test-Path $out) { [math]::Round((Get-Item $out).Length / 1MB, 1) } else { 0 }
            Write-Host "     -> 第 $i 次中断 (curl $code), 已下 $have MB" -ForegroundColor DarkYellow
            Start-Sleep -Seconds 2
        }
    }

    if (-not (Test-Zip $out)) {
        Write-Host "  [失败] $name 所有源都没能下完" -ForegroundColor Red
        return $false
    }
    return $true
}

function Unzip-To($zip, $finalDir, $innerName) {
    $tmp = "$dlDir\_tmp_extract"
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $src = if ($innerName) { Join-Path $tmp $innerName } elseif ((Get-ChildItem $tmp -Directory).Count -eq 1) { (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName } else { $tmp }
    if (Test-Path $finalDir) { Remove-Item $finalDir -Recurse -Force -ErrorAction SilentlyContinue }
    New-Item -ItemType Directory -Force -Path (Split-Path $finalDir) | Out-Null
    Move-Item $src $finalDir -Force
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

# ------------------------------------------------------------------ 1. JDK 17
Write-Host "`n===== [1/4] JDK 17 =====" -ForegroundColor Yellow
if (Test-Path "$jdkDir\bin\javac.exe") {
    Write-Host "  已安装，跳过" -ForegroundColor DarkGray
} else {
    $jdkZip = "$dlDir\jdk17.zip"
    if (Get-File @('https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip','https://mirrors.tuna.tsinghua.edu.cn/AdoptOpenJDK/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.13_11.zip') $jdkZip 'OpenJDK 17') {
        Unzip-To $jdkZip $jdkDir $null
    }
}
if (Test-Path "$jdkDir\bin\javac.exe") {
    $v = & "$jdkDir\bin\java.exe" -version 2>&1 | Select-Object -First 1
    Write-Host "  JDK 就绪: $v" -ForegroundColor Green
} else {
    Write-Host "  JDK 未就绪" -ForegroundColor Red
}

# ------------------------------------------------------ 2. Android 命令行工具
Write-Host "`n===== [2/4] Android 命令行工具 =====" -ForegroundColor Yellow
$cmdDest = "$sdkDir\cmdline-tools\latest"
if (Test-Path "$cmdDest\bin\sdkmanager.bat") {
    Write-Host "  已安装，跳过" -ForegroundColor DarkGray
} else {
    $cmdZip = "$dlDir\cmdline-tools.zip"
    if (Get-File @('https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip') $cmdZip 'cmdline-tools') {
        # 包里顶层目录叫 cmdline-tools，而 sdkmanager 要求它必须叫 latest
        Unzip-To $cmdZip $cmdDest 'cmdline-tools'
    }
}
if (Test-Path "$cmdDest\bin\sdkmanager.bat") {
    Write-Host "  cmdline-tools 就绪" -ForegroundColor Green
} else {
    Write-Host "  cmdline-tools 未就绪" -ForegroundColor Red
}

# ------------------------------------------------------------------ 3. Gradle
Write-Host "`n===== [3/4] Gradle 8.9 =====" -ForegroundColor Yellow
if (Test-Path "$gradleDir\bin\gradle.bat") {
    Write-Host "  已安装，跳过" -ForegroundColor DarkGray
} else {
    $gzZip = "$dlDir\gradle-8.9-bin.zip"
    if (Get-File @('https://mirrors.huaweicloud.com/gradle/gradle-8.9-bin.zip','https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip','https://services.gradle.org/distributions/gradle-8.9-bin.zip') $gzZip 'Gradle 8.9') {
        Unzip-To $gzZip $gradleDir $null
    }
}
if (Test-Path "$gradleDir\bin\gradle.bat") {
    Write-Host "  Gradle 就绪: $gradleDir" -ForegroundColor Green
} else {
    Write-Host "  Gradle 未就绪" -ForegroundColor Red
}

# ------------------------------------------------------ 4. 许可 + SDK 组件
Write-Host "`n===== [4/4] Android SDK 组件 =====" -ForegroundColor Yellow
$sdkmanager = "$cmdDest\bin\sdkmanager.bat"
if (Test-Path $sdkmanager) {
    $env:JAVA_HOME = $jdkDir
    $yes = ("y`r`n" * 80)

    Write-Host "  [许可] 自动接受 Android SDK 许可协议" -ForegroundColor Cyan
    $yes | & cmd /c "`"$sdkmanager`" --sdk_root=`"$sdkDir`" --licenses" 2>&1 | Out-Null

    foreach ($pkg in @('platform-tools', 'platforms;android-35', 'build-tools;35.0.0')) {
        Write-Host "  [安装] $pkg" -ForegroundColor Cyan
        $yes | & cmd /c "`"$sdkmanager`" --sdk_root=`"$sdkDir`" `"$pkg`"" 2>&1 |
            Select-Object -Last 2 | ForEach-Object { Write-Host "     $_" -ForegroundColor DarkGray }
    }
} else {
    Write-Host "  找不到 sdkmanager，跳过 SDK 组件安装" -ForegroundColor Red
}

# ---------------------------------------------------------------------- 汇总
Write-Host "`n===== 环境检查 =====" -ForegroundColor Yellow
$checks = @(
    @{ n = 'JDK 17 编译器';   p = "$jdkDir\bin\javac.exe" },
    @{ n = 'Gradle 8.9';      p = "$gradleDir\bin\gradle.bat" },
    @{ n = 'adb';             p = "$sdkDir\platform-tools\adb.exe" },
    @{ n = 'Platform 35';     p = "$sdkDir\platforms\android-35\android.jar" },
    @{ n = 'Build-Tools 35';  p = "$sdkDir\build-tools\35.0.0\aapt2.exe" }
)
$allOk = $true
foreach ($c in $checks) {
    $ok = Test-Path $c.p
    if (-not $ok) { $allOk = $false }
    $tag = if ($ok) { 'OK  ' } else { '缺少' }
    $col = if ($ok) { 'Green' } else { 'Red' }
    Write-Host ("  [{0}] {1}" -f $tag, $c.n) -ForegroundColor $col
}

if ($allOk) {
    Write-Host "`n环境就绪。下一步编译 APK：" -ForegroundColor Cyan
    Write-Host "  powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1" -ForegroundColor White
} else {
    Write-Host "`n有组件没装好，把上面的红色项发我。" -ForegroundColor Yellow
}