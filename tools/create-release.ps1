# 创建 GitHub Release 并上传 APK
#
# 用途：把编译好的 APK 作为 Release 资产发布，别人可以直接下载安装，
#       不用自己装 JDK + Gradle + Android SDK 去编译。
#
# 两个 API 细节：
#   1. 建 Release 走 api.github.com；**上传资产走 uploads.github.com**（不同域名）
#   2. 资产上传的 Content-Type 要用 application/vnd.android.package-archive，
#      否则 GitHub 会当成普通二进制，下载时浏览器可能不识别
#
# 中文注意：JSON body 一律手工拼字符串再转 UTF-8 字节流。
# PowerShell 5.1 的 ConvertTo-Json 会把中文序列化成 ?。

param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Owner = 'Jty231999',
    [string]$RepoName = 'screen-bugs',
    [string]$Tag = 'v1.1',
    [string]$ApkPath = '',
    [switch]$SkipAsset
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not $ApkPath) { $ApkPath = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk' }
if (-not (Test-Path $ApkPath)) { throw "找不到 APK: $ApkPath（先跑 tools\build-apk.ps1）" }

$headers = @{
    Authorization = "token $Token"
    'User-Agent'  = 'FlyPrank-release'
    Accept        = 'application/vnd.github+json'
}
$base = "https://api.github.com/repos/$Owner/$RepoName"

Write-Host "=== [1/4] 检查 APK ===" -ForegroundColor Cyan
$apk = Get-Item $ApkPath
Write-Host ("  {0}  {1:N0} KB  {2}" -f $apk.Name, ($apk.Length / 1KB), $apk.LastWriteTime)

Write-Host ""
Write-Host "=== [2/4] 建 tag 指向 main 最新提交 ===" -ForegroundColor Cyan
# 用 Git refs API 建 tag（轻量 tag），指向 main 当前提交
$mainRef = Invoke-RestMethod -Uri "$base/git/ref/heads/main" -Headers $headers -Method Get -TimeoutSec 60
$mainSha = $mainRef.object.sha
Write-Host ("  main 提交: " + $mainSha.Substring(0, 7))

$tagExists = $false
try {
    $null = Invoke-RestMethod -Uri "$base/git/ref/tags/$Tag" -Headers $headers -Method Get -TimeoutSec 30
    $tagExists = $true
    Write-Host "  tag $Tag 已存在，复用"
} catch {
    Write-Host "  tag $Tag 不存在，创建中"
}

if (-not $tagExists) {
    $tagBody = [System.Text.Encoding]::UTF8.GetBytes(
        '{"ref":"refs/tags/' + $Tag + '","sha":"' + $mainSha + '"}')
    $null = Invoke-RestMethod -Uri "$base/git/refs" -Headers $headers -Method Post `
        -Body $tagBody -ContentType 'application/json; charset=utf-8' -TimeoutSec 60
    Write-Host ("  ✅ 已创建 tag $Tag -> " + $mainSha.Substring(0, 7)) -ForegroundColor Green
}

Write-Host ""
Write-Host "=== [3/4] 创建 Release ===" -ForegroundColor Cyan
$name = "屏幕虫子 v1.1"
$notes = @'
安卓恶搞小工具：把 16 种手绘虫子放到屏幕最上层。

## 直接安装

下载下面的 `screen-bugs-v1.1.apk`（5.4 MB），传到手机上点安装即可。
首次安装需要允许「安装未知来源应用」——这是侧载 APK 的正常流程。

**系统要求**：Android 8.0 及以上（minSdk 26）。

首次启动会请求「显示在其他应用上层」权限，这是虫子能飞出 App 的必要条件。
打开开关返回后**会自动放满 50 只虫子**，不用再点按钮。

收场：下拉通知栏点「收网」。

## 这个版本有什么

- **16 个品种**，全部用 Canvas 手绘，无任何图片素材：
  家蝇 / 绿豆蝇 / 果蝇 / 蛾子 / 蚊子 / 蟑螂 / 蜜蜂 / 瓢虫 / 蟋蟀 /
  蝴蝶 / 蚂蚁 / 蜻蜓 / 臭虫 / 虱子 / 衣鱼 / 跳蚤
- 四类运动方式（飞 / 爬 / 跳 / 悬停）+ 两类特殊形态（无翅、侧扁）
- 虫子会飞到任何界面上面；手指去戳它会躲开、弹走、震一下、嗡声变响
- 振动反馈分事件 / 品种 / 体型，且尊重系统触感开关与勿扰模式
- 嗡嗡声实时合成（176Hz 锯齿波），音量由实际位移速度驱动
- 主界面有**实时效果预览**，所见即所得
- 深色模式适配、边到边安全区处理、屏幕常亮
- 新装/更新后自动放满最大数量

## 实测（vivo / Android 16）

- 50 只满载：帧时间 p50 30ms，**0 jank，0 丢帧**
- 覆盖层窗口恒为 7 个，不随虫子数量增长
- 触摸穿透与戳中反应均验证通过

## 说明

- 这是**调试签名**的 APK，仅供自己和朋友装玩，不适合上架
- 不联网、不读其他应用内容、不隐藏图标和通知
- 唯一的敏感权限是「显示在其他应用上层」
- 仅供当面整蛊朋友，注意场合

完整源码和开发笔记见仓库。
'@

# 手工拼 JSON（避免 ConvertTo-Json 把中文变 ?）
function Esc($s) { return $s.Replace('\', '\\').Replace('"', '\"').Replace("`r`n", '\n').Replace("`n", '\n') }
$payload = '{"tag_name":"' + $Tag + '","target_commitish":"main","name":"' + (Esc $name) +
           '","body":"' + (Esc $notes) + '","draft":false,"prerelease":false}'
$payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($payload)

$release = $null
try {
    $release = Invoke-RestMethod -Uri "$base/releases" -Headers $headers -Method Post `
        -Body $payloadBytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 90
    Write-Host ("  ✅ Release 已创建 id=" + $release.id) -ForegroundColor Green
    Write-Host ("  页面: " + $release.html_url)
} catch {
    Write-Host ("  创建失败: " + $_.Exception.Message) -ForegroundColor Red
    try { $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream()); Write-Host ("  " + $sr.ReadToEnd()) } catch {}
    exit 1
}

if ($SkipAsset) {
    Write-Host ""
    Write-Host "  按参数要求跳过资产上传"
    exit 0
}

Write-Host ""
Write-Host "=== [4/4] 上传 APK 资产 ===" -ForegroundColor Cyan
# 关键：资产上传走 uploads.github.com，不是 api.github.com
$assetName = 'screen-bugs-v1.1.apk'
$uploadUrl = "https://uploads.github.com/repos/$Owner/$RepoName/releases/$($release.id)/assets?name=$assetName"
Write-Host ("  目标: " + $assetName)

$assetHeaders = @{
    Authorization  = "token $Token"
    'User-Agent'   = 'FlyPrank-release'
    Accept         = 'application/vnd.github+json'
}
$bytes = [System.IO.File]::ReadAllBytes($ApkPath)
Write-Host ("  大小: {0:N0} KB" -f ($bytes.Length / 1KB))

try {
    $asset = Invoke-RestMethod -Uri $uploadUrl -Headers $assetHeaders -Method Post `
        -Body $bytes -ContentType 'application/vnd.android.package-archive' -TimeoutSec 600
    Write-Host ("  ✅ 上传成功") -ForegroundColor Green
    Write-Host ("  下载地址: " + $asset.browser_download_url)
    Write-Host ("  远端大小: {0:N0} KB" -f ($asset.size / 1KB))
    if ($asset.size -eq $bytes.Length) {
        Write-Host "  ✅ 大小一致，上传完整" -ForegroundColor Green
    } else {
        Write-Host "  ⚠️ 大小不一致，可能不完整" -ForegroundColor Yellow
    }
} catch {
    Write-Host ("  上传失败: " + $_.Exception.Message) -ForegroundColor Red
    try { $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream()); Write-Host ("  " + $sr.ReadToEnd()) } catch {}
    Write-Host ""
    Write-Host "  Release 已建好，可以手动把 APK 拖到 Release 页面补上：" -ForegroundColor Yellow
    Write-Host ("    " + $release.html_url)
    exit 1
}

Write-Host ""
Write-Host "=== 完成 ===" -ForegroundColor Green
Write-Host ("  Release 页面: " + $release.html_url) -ForegroundColor Green
