# 把项目推送到 GitHub
#
# 用法（二选一）：
#   # A. 用 Personal Access Token（推荐，可顺带自动创建仓库）
#   powershell -ExecutionPolicy Bypass -File tools\push-to-github.ps1 -Token ghp_xxxxxxxx
#
#   # B. 已经配好 SSH key 或 git 凭据管理器，直接推
#   powershell -ExecutionPolicy Bypass -File tools\push-to-github.ps1 -UseSsh
#
# 需要 Token 时，去这里创建（勾选 repo 权限即可）：
#   https://github.com/settings/tokens
#
# 脚本会做四件事：
#   1. 本地 git init + 提交（只提交源码，构建产物已被 .gitignore 排除）
#   2. 用 api.github.com 创建仓库并写入描述/主页/话题
#   3. 配置 remote 并推送
#   4. 打印仓库地址

param(
    [string]$Token = '',
    [string]$Owner = '',
    [string]$RepoName = 'screen-bugs',
    [string]$Description = '安卓恶搞小工具：把 16 种手绘虫子放到屏幕最上层，会飞会爬会跳，戳它还会躲。纯 Canvas 绘制、无图片素材、不联网。',
    [switch]$UseSsh,
    [switch]$SkipCreate
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Fail($msg, $hint) {
    Write-Host ''
    Write-Host "× $msg" -ForegroundColor Red
    if ($hint) { Write-Host ''; Write-Host $hint -ForegroundColor Yellow }
    exit 1
}

# ---------------------------------------------------------------- 0. 前置检查
Write-Host '=== [0/4] 检查 git ===' -ForegroundColor Cyan
$gitExe = Get-Command git -ErrorAction SilentlyContinue
if (-not $gitExe) { Fail '没找到 git，请先安装：https://git-scm.com/download/win' }

# 有没有配置身份（没配的话 commit 会失败）
$userName = (& git config --global user.name) 2>$null
$userEmail = (& git config --global user.email) 2>$null
if (-not $userName -or -not $userEmail) {
    Fail 'git 还没配置提交身份' @'
请先执行（换成你自己的）：
  git config --global user.name  "你的名字"
  git config --global user.email "你的邮箱"
'@
}
Write-Host "  git 就绪，身份：$userName <$userEmail>"

# ---------------------------------------------------------------- 1. 本地提交
Write-Host ''
Write-Host '=== [1/4] 本地 git 初始化并提交 ===' -ForegroundColor Cyan
if (-not (Test-Path "$root\.git")) {
    & git init -q 2>&1 | Out-Null
    & git branch -M main 2>&1 | Out-Null
    Write-Host '  已初始化仓库（分支 main）'
} else {
    Write-Host '  仓库已存在，跳过初始化'
}

# 关键：只添加该提交的东西。build/ 和 local.properties 已被 .gitignore 排除
& git add -A
$staged = (& git diff --cached --name-only) 2>$null
if (-not $staged) {
    Write-Host '  没有新的改动需要提交'
} else {
    Write-Host ("  待提交文件 {0} 个" -f @($staged).Count)
    # 安全检查：绝不该提交进去的东西
    $bad = @($staged) | Where-Object { $_ -match 'build/|\.apk$|local\.properties|\.gradle/' }
    if ($bad) {
        Fail '暂存区里有构建产物或本机配置，不该提交' ("  问题文件：" + ($bad -join ', ') + "`n  请检查 .gitignore")
    }
    $msg = @'
首个版本：16 种手绘虫子，可飞到任意界面最上层

核心实现
- 双窗口方案解决"隐形但不吃触摸"：全屏绘制层 FLAG_NOT_TOUCHABLE + 虫子大小的
  触摸替身。戳中虫子被替身吃掉，戳别处照常穿透给下层 App。
  虫子多时用 6 个替身分摊，保证屏幕各处都有能戳中的虫子。
- 16 个品种全部用 Canvas 手绘，无任何图片素材：蝇/蛾/蝶/蚊/蜂/蟑/蟋/瓢/蚁/蜻/臭虫/虱/衣鱼/跳蚤。
  分四类运动方式（飞/爬/跳/悬停）+ 两类特殊形态（无翅、侧扁）。
- 嗡嗡声用 AudioTrack 实时合成（176Hz 锯齿波 + 28Hz 颤音），音量由实际位移速度驱动。
- 振动反馈分事件/品种/体型：被戳中、逃窜、跳跃、落地、起飞各有不同振感组合，
  强度按品种缩放（果蝇与蟑螂差近 3 倍）。尊重系统触感开关与勿扰模式。
- 解剖结构按分类学文献校正：头/胸/腹三段独立、六种头型、蛾蝶四翅、
  瓢虫 7 斑布局、蟑螂前胸双纵纹、蜜蜂腹部黄带方向等。

工程
- 主界面用同一个 FlyActor 绘制代码做实时效果预览，所见即所得。
- 深色模式适配、边到边(API 35)安全区处理、屏幕常亮唤醒锁。
- 新装/更新后自动放满最大数量（按 versionCode 变化触发）。

实测（vivo / Android 16）：50 只满载帧时间 p50 30ms，0 jank，0 丢帧；
覆盖层窗口恒为 7 个，不随虫子数量增长。
'@
    & git commit -q -m $msg
    if ($LASTEXITCODE -ne 0) { Fail 'git commit 失败' }
    Write-Host '  提交完成'
}

# ---------------------------------------------------------------- 2. 建仓库
Write-Host ''
Write-Host '=== [2/4] 在 GitHub 上创建仓库 ===' -ForegroundColor Cyan
if ($SkipCreate) {
    Write-Host '  按参数要求跳过创建'
} else {
    if (-not $Token) {
        Write-Host '  没提供 -Token，跳过自动创建（假设仓库已存在）' -ForegroundColor Yellow
        Write-Host '  提示：想自动创建就带上 -Token，见脚本顶部说明'
    } else {
        # 先确认 token 有效并拿到用户名
        $headers = @{ Authorization = "token $Token"; 'User-Agent' = 'FlyPrank-push' }
        try {
            $me = Invoke-RestMethod -Uri 'https://api.github.com/user' -Headers $headers -Method Get -TimeoutSec 30
            if (-not $Owner) { $Owner = $me.login }
            Write-Host "  已认证为：$Owner"
        } catch {
            Fail 'Token 无效或没有权限' "  请确认勾选了 repo 权限：https://github.com/settings/tokens"
        }

        $body = @{
            name        = $RepoName
            description = $Description
            private     = $false
            has_issues  = $true
            has_wiki    = $false
            auto_init   = $false
        } | ConvertTo-Json

        try {
            $created = Invoke-RestMethod -Uri 'https://api.github.com/user/repos' -Headers $headers `
                -Method Post -Body $body -ContentType 'application/json' -TimeoutSec 30
            Write-Host "  仓库已创建：$($created.html_url)" -ForegroundColor Green
        } catch {
            $msg = $_.Exception.Message
            if ($msg -match '422|already exists') {
                Write-Host '  仓库已存在，直接推送' -ForegroundColor Yellow
            } else {
                Write-Host "  创建失败：$msg" -ForegroundColor Yellow
                Write-Host '  如果只是网络问题，可以继续尝试推送'
            }
        }

        # 写话题（利于被搜到）
        try {
            $topics = @{ names = @('android','kotlin','prank','overlay','canvas','insects','no-dependencies') } | ConvertTo-Json
            Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$RepoName/topics" -Headers $headers `
                -Method Put -Body $topics -ContentType 'application/json' -TimeoutSec 30 | Out-Null
            Write-Host '  已设置话题标签'
        } catch {
            Write-Host '  话题设置失败（不影响推送）' -ForegroundColor DarkGray
        }
    }
}

# ---------------------------------------------------------------- 3. 推送
Write-Host ''
Write-Host '=== [3/4] 推送 ===' -ForegroundColor Cyan

if ($UseSsh) {
    if (-not $Owner) { Fail '用 SSH 时请一并提供 -Owner 你的GitHub用户名' }
    $remoteUrl = "git@github.com:$Owner/$RepoName.git"
} else {
    if (-not $Owner) { Fail '请提供 -Owner 你的GitHub用户名（或带 -Token 让它自动获取）' }
    if ($Token) {
        $remoteUrl = "https://$Token@github.com/$Owner/$RepoName.git"
    } else {
        $remoteUrl = "https://github.com/$Owner/$RepoName.git"
    }
}
$displayUrl = if ($Token) { "https://***@github.com/$Owner/$RepoName.git" } else { $remoteUrl }
Write-Host "  remote: $displayUrl"

$existing = (& git remote) 2>$null
if ($existing -contains 'origin') {
    & git remote set-url origin $remoteUrl
} else {
    & git remote add origin $remoteUrl
}

Write-Host '  正在推送...'
# 先测一下 github.com 通不通，好给出明确原因
$probe = & curl.exe -sL --max-time 12 -o NUL -w '%{http_code}' 'https://github.com/' 2>&1
if ("$probe" -eq '000') {
    Write-Host ''
    Write-Host '  × 连不上 github.com（curl 返回 000）' -ForegroundColor Red
    Write-Host @'

  这不是脚本的问题 —— 本机到 github.com 的连接被拒绝了。
  可以先自己确认：
    curl.exe -sI https://github.com/          # 返回 000 就是不通
    git ls-remote https://github.com/git/git.git HEAD

  本地仓库已经完全准备好了，网络恢复后直接执行：
    git push -u origin main

  如果这台机器始终连不上，可以在能上 GitHub 的机器上：
    git clone --bare . ../flyprank.git
    然后 scp/拷贝到那台机器，再 git push --mirror
'@ -ForegroundColor Yellow
    exit 1
}

& git push -u origin main
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Fail '推送失败' @'
  常见原因：
  1. 认证失败 -> 用 -Token ghp_xxx，或先配好 SSH key
  2. 远端已有内容（比如建仓库时勾了 README）-> 先执行：
       git pull --rebase origin main
     再重新推送
  3. 网络 -> 见上面的连接排查
'@
}

# ---------------------------------------------------------------- 4. 完成
Write-Host ''
Write-Host '=== [4/4] 完成 ===' -ForegroundColor Green
$webUrl = "https://github.com/$Owner/$RepoName"
Write-Host "  仓库地址：$webUrl" -ForegroundColor Green
Write-Host ''
Write-Host '建议再补两件事（网页上点两下就行）：' -ForegroundColor Cyan
Write-Host '  - Settings -> 勾选 Issues'
Write-Host '  - 想让人搜到的话，确认 About 里的 Description 和 Topics 已填好'
Write-Host ''
Write-Host '要发布 APK 的话：Releases -> Draft a new release -> 把 app-debug.apk 传上去'
