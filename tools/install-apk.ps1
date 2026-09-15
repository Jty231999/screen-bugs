# 屏幕苍蝇 App —— 用数据线装到手机
# 用法:  powershell -ExecutionPolicy Bypass -File tools\install-apk.ps1
#
# 前置：手机已开启「开发者选项 -> USB 调试」，插上数据线，
#       手机上弹「允许 USB 调试吗？」时点允许。

$ErrorActionPreference = 'Stop'

$root   = Split-Path -Parent $PSScriptRoot
$sdkDir = 'D:\Android\sdk'
$adb    = "$sdkDir\platform-tools\adb.exe"
$apk    = "$root\app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $adb)) {
    Write-Host "找不到 adb: $adb" -ForegroundColor Red
    Write-Host "请先运行 tools\setup-android-env.ps1" -ForegroundColor Yellow
    exit 1
}
if (-not (Test-Path $apk)) {
    Write-Host "找不到 APK: $apk" -ForegroundColor Red
    Write-Host "请先运行 tools\build-apk.ps1" -ForegroundColor Yellow
    exit 1
}

Write-Host "检查手机连接..." -ForegroundColor Cyan
& $adb start-server | Out-Null
$lines = & $adb devices
$lines | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

$ready = $lines | Where-Object { $_ -match "\tdevice$" }
$unauth = $lines | Where-Object { $_ -match "unauthorized" }

if (-not $ready) {
    Write-Host "`n没检测到已授权的手机。" -ForegroundColor Red
    if ($unauth) {
        Write-Host "手机显示 unauthorized：请在手机屏幕上点「允许 USB 调试」。" -ForegroundColor Yellow
    } else {
        Write-Host "排查顺序：" -ForegroundColor Yellow
        Write-Host "  1. 手机是否开启「开发者选项 -> USB 调试」"
        Write-Host "  2. 数据线是否只能充电（换一根数据线试试）"
        Write-Host "  3. 手机 USB 模式改成「传输文件 / MTP」"
        Write-Host "  4. 手机上是否弹出了授权弹窗（没弹出就拔插一次）"
    }
    exit 1
}

Write-Host "`n正在安装（-g 自动授予运行时权限）..." -ForegroundColor Cyan
# -g: 安装时就把清单里声明的运行时权限授好（POST_NOTIFICATIONS 等），
#     省掉首次启动的权限弹窗。悬浮窗权限无法用这种方式授予，
#     只能由用户在系统页面手动打开（Android 的安全设计，无法绕过）。
& $adb install -r -g $apk
if ($LASTEXITCODE -ne 0) {
    Write-Host "`n带 -g 安装失败，回退为普通安装..." -ForegroundColor Yellow
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) {
        Write-Host "`n安装失败。" -ForegroundColor Red
        Write-Host "如果提示 INSTALL_FAILED_UPDATE_INCOMPATIBLE，先在手机上卸载旧版本再试。" -ForegroundColor Yellow
        exit $LASTEXITCODE
    }
}

# 关键顺序：**先把悬浮窗权限授好，再启动 App**。
# 反过来的话，首次启动会因为没权限而放弃自动放出，
# 但 versionCode 已经被消费掉了，重启也不会再触发。
Write-Host "`n尝试用 adb 授予悬浮窗权限（必须在启动之前）..." -ForegroundColor Cyan
& $adb shell appops set com.bt.flyprank SYSTEM_ALERT_WINDOW allow 2>&1 | Out-Null
$state = & $adb shell appops get com.bt.flyprank SYSTEM_ALERT_WINDOW 2>&1
$granted = ("$state" -match 'allow')

if ($granted) {
    Write-Host "  已自动授予。" -ForegroundColor Green
} else {
    Write-Host "  这台机型不允许 adb 直接设，需要手动授权：" -ForegroundColor Yellow
    Write-Host "  App 启动后会把授权页推到你面前，打开开关返回即可自动放出。"
}

Write-Host "`n正在启动 App（应自动放满虫子）..." -ForegroundColor Green
& $adb shell am start -n com.bt.flyprank/.MainActivity | Out-Null

Write-Host ""
Write-Host "接下来：" -ForegroundColor Cyan
Write-Host "  - 已自动授权：虫子应该已经自己放满 50 只了，直接递给朋友"
Write-Host "  - 需手动授权：按 App 里的提示打开「允许显示在其他应用上层」-> 返回即自动放出"
Write-Host "  收场：下拉通知栏点「收网」" -ForegroundColor Yellow