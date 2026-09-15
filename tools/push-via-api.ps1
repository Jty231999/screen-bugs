# 通过 GitHub Git Data API 推送代码
#
# 为什么不用 git push：
#   本机到 github.com:443 被防火墙重置（curl 返回 000），而 api.github.com 可达。
#   git push 走的正是 github.com，所以走不通；API 走 api.github.com，可以。
#
# 为什么用 Git Data API 而不是逐个文件调 Contents API：
#   后者产生 39 个零碎提交；前者用 blob → tree → commit → 更新 ref
#   四步做出**一个完整提交**，和本地 git 提交一一对应。
#
# 两个必须注意的坑（都踩过）：
#   1. 空仓库不能用 Git Data API（409 Git Repository is empty）——
#      必须先用 Contents API 放一个文件激活仓库
#   2. **不要把 byte[] 通过函数位置参数传递** —— PowerShell 的隐式转换会破坏 body，
#      表现为 GitHub 报 422 "content: missing_field"（明明发了内容却说没发）。
#      所以下面每处都直接显式调用 Invoke-RestMethod，不套包装函数。

param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Owner = 'Jty231999',
    [string]$RepoName = 'screen-bugs',
    [string]$Branch = 'main'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$headers = @{
    Authorization = "token $Token"
    'User-Agent'  = 'FlyPrank-push'
    Accept        = 'application/vnd.github+json'
}
$base = "https://api.github.com/repos/$Owner/$RepoName"

# 手工拼 JSON + UTF-8 字节流。绝不用 ConvertTo-Json：PS 5.1 会把中文序列化成 ?
function JsonUtf8([string]$s) {
    return ,([System.Text.Encoding]::UTF8.GetBytes($s))
}

Write-Host "=== [1/6] 确认仓库 ===" -ForegroundColor Cyan
$info = Invoke-RestMethod -Uri $base -Headers $headers -Method Get -TimeoutSec 60
Write-Host ("  {0}   (private={1})" -f $info.html_url, $info.private)

# ---------------------------------------------------------------- 收集文件
Write-Host ""
Write-Host "=== [2/6] 收集文件 ===" -ForegroundColor Cyan
$files = @(& git -C $root ls-files)
if ($files.Count -eq 0) { throw "git ls-files 为空，确认 $root 是 git 仓库根目录" }
Write-Host ("  共 {0} 个文件" -f $files.Count)

# ---------------------------------------------------------------- 建 blob
Write-Host ""
Write-Host "=== [3/6] 上传内容（建 blob） ===" -ForegroundColor Cyan
$treeItems = New-Object System.Collections.ArrayList
$n = 0
foreach ($rel in $files) {
    $n++
    $full = Join-Path $root ($rel -replace '/', '\')
    if (-not (Test-Path $full)) {
        Write-Host ("  [跳过] 文件不存在: $rel") -ForegroundColor DarkYellow
        continue
    }

    $ext = [System.IO.Path]::GetExtension($full).ToLower()
    if (@('.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.jar') -contains $ext) {
        $b64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($full))
    } else {
        # ReadAllText 会按 BOM 自动识别编码，带 BOM 的 ps1 也能正确读
        $txt = [System.IO.File]::ReadAllText($full)
        $b64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($txt))
    }

    # 关键：显式命名参数，不经过任何函数包装
    $payload = '{"content":"' + $b64 + '","encoding":"base64"}'
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($payload)

    $blob = Invoke-RestMethod -Uri "$base/git/blobs" -Headers $headers -Method Post `
        -Body $bodyBytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 90

    [void]$treeItems.Add(
        '{"path":"' + $rel + '","mode":"100644","type":"blob","sha":"' + $blob.sha + '"}'
    )

    if ($n % 10 -eq 0 -or $n -eq $files.Count) {
        Write-Host ("  已上传 {0}/{1}" -f $n, $files.Count)
    }
}

# ---------------------------------------------------------------- 建 tree
Write-Host ""
Write-Host "=== [4/6] 建 tree ===" -ForegroundColor Cyan
$treePayload = '{"tree":[' + ($treeItems -join ',') + ']}'
$treeBytes = [System.Text.Encoding]::UTF8.GetBytes($treePayload)
$tree = Invoke-RestMethod -Uri "$base/git/trees" -Headers $headers -Method Post `
    -Body $treeBytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 90
Write-Host ("  tree sha = {0}  （{1} 个条目）" -f $tree.sha, $tree.tree.Count)

# ---------------------------------------------------------------- 建 commit
Write-Host ""
Write-Host "=== [5/6] 建 commit ===" -ForegroundColor Cyan
$msg = (& git -C $root log -1 --pretty=%B) -join "`n"
$msg = $msg.TrimEnd()
$esc = $msg.Replace('\', '\\').Replace('"', '\"').Replace("`r`n", '\n').Replace("`n", '\n')

# 父提交：远端分支已有提交就带上
$parents = ''
try {
    $ref = Invoke-RestMethod -Uri "$base/git/ref/heads/$Branch" -Headers $headers -Method Get -TimeoutSec 60
    $parents = ',"parents":["' + $ref.object.sha + '"]'
    Write-Host ("  父提交: " + $ref.object.sha.Substring(0, 7))
} catch {
    Write-Host "  远端分支为空，这是首个提交"
}

$commitPayload = '{"message":"' + $esc + '","tree":"' + $tree.sha + '"' + $parents + '}'
$commitBytes = [System.Text.Encoding]::UTF8.GetBytes($commitPayload)
$commit = Invoke-RestMethod -Uri "$base/git/commits" -Headers $headers -Method Post `
    -Body $commitBytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 90
Write-Host ("  commit sha = {0}" -f $commit.sha)

# ---------------------------------------------------------------- 更新 ref
Write-Host ""
Write-Host "=== [6/6] 更新 $Branch 分支 ===" -ForegroundColor Cyan
$refPayload = '{"sha":"' + $commit.sha + '","force":false}'
$refBytes = [System.Text.Encoding]::UTF8.GetBytes($refPayload)
try {
    $upd = Invoke-RestMethod -Uri "$base/git/refs/heads/$Branch" -Headers $headers -Method Patch `
        -Body $refBytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 60
    Write-Host ("  已更新分支 -> " + $upd.object.sha.Substring(0, 7)) -ForegroundColor Green
} catch {
    $newRefPayload = '{"ref":"refs/heads/' + $Branch + '","sha":"' + $commit.sha + '"}'
    $newRefBytes = [System.Text.Encoding]::UTF8.GetBytes($newRefPayload)
    $upd = Invoke-RestMethod -Uri "$base/git/refs" -Headers $headers -Method Post `
        -Body $newRefBytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 60
    Write-Host ("  已创建分支 -> " + $upd.object.sha.Substring(0, 7)) -ForegroundColor Green
}

Write-Host ""
Write-Host "=== 完成 ===" -ForegroundColor Green
Write-Host ("  仓库: https://github.com/$Owner/$RepoName") -ForegroundColor Green
$final = Invoke-RestMethod -Uri $base -Headers $headers -Method Get -TimeoutSec 60
Write-Host ("  默认分支: {0}   仓库大小: {1} KB" -f $final.default_branch, $final.size)
