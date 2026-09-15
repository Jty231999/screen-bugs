# 屏幕苍蝇 App —— 一键编译 APK
# 用法:  powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1
#
# 产物: app\build\outputs\apk\debug\app-debug.apk
# 用 Android 默认调试签名，可以直接装到手机。

$ErrorActionPreference = 'Stop'

$root      = Split-Path -Parent $PSScriptRoot
$jdkDir    = 'D:\Android\jdk17'
$sdkDir    = 'D:\Android\sdk'
$gradleDir = 'D:\Android\gradle-8.9'

function Need($path, $what) {
    if (-not (Test-Path $path)) {
        Write-Host "缺少 $what" -ForegroundColor Red
        Write-Host "  路径: $path" -ForegroundColor DarkGray
        Write-Host "请先运行: powershell -ExecutionPolicy Bypass -File tools\setup-android-env.ps1" -ForegroundColor Yellow
        exit 1
    }
}

Need "$jdkDir\bin\javac.exe"                     'JDK 17'
Need "$gradleDir\bin\gradle.bat"                 'Gradle 8.9'
Need "$sdkDir\platforms\android-35\android.jar"  'Android SDK Platform 35'
Need "$root\app\src\main\AndroidManifest.xml"    '项目源码'

# 只为这次构建设定环境变量，不改系统设置
$env:JAVA_HOME        = $jdkDir
$env:ANDROID_HOME     = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir
$env:PATH             = "$jdkDir\bin;$sdkDir\platform-tools;$env:PATH"

# 告诉 Gradle 本地 SDK 在哪（这个文件属于本机配置，不用提交）
$escaped = $sdkDir -replace '\\', '\\'
Set-Content -Path "$root\local.properties" -Value "sdk.dir=$escaped" -Encoding ASCII

Write-Host "JDK    : $jdkDir"
Write-Host "SDK    : $sdkDir"
Write-Host "Gradle : $gradleDir"
Write-Host "`n开始编译（第一次会下载 AGP/Kotlin/AndroidX，走国内镜像，通常几分钟）..." -ForegroundColor Yellow
Write-Host ""

Push-Location $root
$code = 1
try {
    & "$gradleDir\bin\gradle.bat" --no-daemon assembleDebug
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($code -ne 0) {
    Write-Host "`n编译失败 (exit $code)" -ForegroundColor Red
    Write-Host "把上面最后的报错发我，我来修。" -ForegroundColor Yellow
    exit $code
}

$apk = "$root\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
    $kb = [math]::Round((Get-Item $apk).Length / 1KB, 0)
    Write-Host "`n编译成功" -ForegroundColor Green
    Write-Host "APK: $apk  ($kb KB)" -ForegroundColor Green
    Write-Host "`n装到手机: powershell -ExecutionPolicy Bypass -File tools\install-apk.ps1" -ForegroundColor Cyan
} else {
    Write-Host "编译结束但没找到 APK" -ForegroundColor Red
    exit 1
}