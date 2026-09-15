# 昆虫轮廓对比工具
#
# 用途：把任意参考图（真实昆虫照片 / 透明 PNG）的背面轮廓提取出来，
#       和"我们 App 实际画出来的轮廓"做逐行宽度对比，找出比例差异。
#
# 用法:
#   pwsh -File tools\compare-silhouette.ps1 -RefPath <参考图> [-RefBg auto|<hex>] [-MinePath <App截图>]
#
# 输出：归一化后的宽度剖面（0%~100% 体长处各位置的相对宽度），
#       以及与模型侧数值的对比表。
#
# 为什么用"逐行宽度剖面"而不是别的指标：
#   昆虫背面识别度最高的是**轮廓**，而轮廓可以用"沿体轴各处的宽度"完整描述。
#   这个量对光照、颜色、纹理都不敏感，只反映形状，所以最适合用来校正模型。

param(
    [Parameter(Mandatory = $true)][string]$RefPath,
    [string]$RefBg = 'auto',
    [string]$MinePath = '',
    [int]$Step = 1,
    [int]$Threshold = 110
)

Add-Type -AssemblyName System.Drawing

function Get-Silhouette($path, $bgMode, $thr, $step) {
    $img = [System.Drawing.Image]::FromFile($path)
    $bmp = New-Object System.Drawing.Bitmap($img)
    $W = $bmp.Width; $H = $bmp.Height

    # --- 判断背景 ---
    # 不能只看 PixelFormat 是否带 alpha：带 alpha 通道但全不透明的图也很常见。
    # 所以要采样确认"真的存在透明区"且"真的存在不透明区"，才算可抠图的 PNG。
    $transparentN = 0; $opaqueN = 0
    for ($y = 0; $y -lt $H; $y += 5) {
        for ($x = 0; $x -lt $W; $x += 5) {
            $a = $bmp.GetPixel($x, $y).A
            if ($a -lt 40) { $transparentN++ } elseif ($a -gt 200) { $opaqueN++ }
        }
    }
    $isPngAlpha = ($transparentN -gt 20) -and ($opaqueN -gt 20)

    $bgR = 0; $bgG = 0; $bgB = 0
    if ($bgMode -ne 'auto') {
        $h = $bgMode.TrimStart('#')
        $bgR = [Convert]::ToInt32($h.Substring(0, 2), 16)
        $bgG = [Convert]::ToInt32($h.Substring(2, 2), 16)
        $bgB = [Convert]::ToInt32($h.Substring(4, 2), 16)
    } else {
        # 四角平均作为背景估计
        $cs = @($bmp.GetPixel(1,1), $bmp.GetPixel($W-2,1), $bmp.GetPixel(1,$H-2), $bmp.GetPixel($W-2,$H-2))
        $bgR = [int](($cs | Measure-Object -Property R -Average).Average)
        $bgG = [int](($cs | Measure-Object -Property G -Average).Average)
        $bgB = [int](($cs | Measure-Object -Property B -Average).Average)
    }

    $rowMin = @{}; $rowMax = @{}
    $minX = [int]::MaxValue; $maxX = -1; $minY = [int]::MaxValue; $maxY = -1; $cnt = 0

    for ($y = 0; $y -lt $H; $y += $step) {
        for ($x = 0; $x -lt $W; $x += $step) {
            $c = $bmp.GetPixel($x, $y)
            if ($isPngAlpha) {
                $fg = ($c.A -ge 40)                    # 透明 = 背景
            } else {
                $d = [math]::Abs($c.R-$bgR) + [math]::Abs($c.G-$bgG) + [math]::Abs($c.B-$bgB)
                $fg = ($d -gt $thr)
            }
            if ($fg) {
                $cnt++
                if ($x -lt $minX){$minX=$x}; if ($x -gt $maxX){$maxX=$x}
                if ($y -lt $minY){$minY=$y}; if ($y -gt $maxY){$maxY=$y}
                if (-not $rowMin.ContainsKey($y)) { $rowMin[$y]=$x; $rowMax[$y]=$x }
                else { if ($x -lt $rowMin[$y]){$rowMin[$y]=$x}; if ($x -gt $rowMax[$y]){$rowMax[$y]=$x} }
            }
        }
    }
    $bmp.Dispose(); $img.Dispose()

    if ($cnt -lt 50) { return $null }

    # --- 归一化宽度剖面：把体长分成 21 个采样点 ---
    $h = $maxY - $minY
    $w = $maxX - $minX
    $prof = @()
    $maxRowW = 1
    foreach ($k in $rowMin.Keys) { $rw = $rowMax[$k]-$rowMin[$k]; if ($rw -gt $maxRowW) { $maxRowW = $rw } }
    for ($i = 0; $i -le 20; $i++) {
        $y = $minY + [int]($h * $i / 20)
        # 附近 3 行取平均，减少噪声
        $acc = 0; $n = 0
        for ($dy = -1; $dy -le 1; $dy++) {
            $yy = $y + $dy
            if ($rowMin.ContainsKey($yy)) { $acc += ($rowMax[$yy]-$rowMin[$yy]); $n++ }
        }
        $rw = if ($n -gt 0) { $acc / $n } else { 0 }
        $prof += [math]::Round($rw / $maxRowW, 3)
    }

    return [PSCustomObject]@{
        BboxW = $w; BboxH = $h
        Aspect = [math]::Round([math]::Max($w,$h) / [math]::Max(1,[math]::Min($w,$h)), 3)
        Coverage = [math]::Round(100.0 * $cnt / ($W * $H), 2)
        MaxRowW = $maxRowW
        Profile = $prof
        IsPngAlpha = $isPngAlpha
    }
}

Write-Host "`n=== 参考图轮廓 ===" -ForegroundColor Cyan
# 注意：函数返回单个对象时 PowerShell 管道会把它当可枚举对象拆开，
# 所以用 @(...)[0] 取回原始对象，保证 .Profile 等属性可用
$ref = @(Get-Silhouette $RefPath $RefBg $Threshold $Step)[0]
if ($null -eq $ref -or (@($ref.Profile).Count -lt 21)) {
    Write-Host "  提取失败：前景像素太少。试试 -RefBg <hex> 或调 -Threshold" -ForegroundColor Red
    exit 1
}
Write-Host ("  文件      : {0}" -f (Split-Path $RefPath -Leaf))
Write-Host ("  透明PNG   : {0}" -f $ref.IsPngAlpha)
Write-Host ("  bbox      : {0} x {1}" -f $ref.BboxW, $ref.BboxH)
Write-Host ("  长宽比    : {0}" -f $ref.Aspect)
Write-Host ("  前景覆盖  : {0}%" -f $ref.Coverage)

function Show-Profile($p, $label) {
    Write-Host ("`n  --- {0} 的归一化宽度剖面（0%=前端 100%=尾端）---" -f $label) -ForegroundColor DarkCyan
    $chars = ' .:-=+*#%@'
    for ($i = 0; $i -le 20; $i++) {
        $v = $p[$i]
        $barLen = [int]([math]::Round($v * 40))
        $bar = '#' * $barLen
        Write-Host ("    {0,3}% {1,5:N3} {2}" -f ($i*5), $v, $bar)
    }
}
Show-Profile $ref.Profile '参考图'

if ($MinePath) {
    Write-Host "`n=== 模型轮廓（App 截图）===" -ForegroundColor Cyan
    $mine = @(Get-Silhouette $MinePath 'auto' $Threshold $Step)[0]
    if ($null -eq $mine -or (@($mine.Profile).Count -lt 21)) { Write-Host "  提取失败" -ForegroundColor Red; exit 1 }
    Write-Host ("  bbox {0} x {1}   长宽比 {2}   覆盖 {3}%" -f $mine.BboxW, $mine.BboxH, $mine.Aspect, $mine.Coverage)
    Show-Profile $mine.Profile '模型'

    Write-Host "`n=== 逐点差异（模型 - 参考）===" -ForegroundColor Yellow
    Write-Host ("    {0,5} {1,8} {2,8} {3,9}" -f '位置%','参考','模型','差值')
    $sum = 0.0
    for ($i = 0; $i -le 20; $i++) {
        $d = $mine.Profile[$i] - $ref.Profile[$i]
        $sum += [math]::Abs($d)
        $flag = if ([math]::Abs($d) -gt 0.15) { '  <<< 明显差异' } else { '' }
        Write-Host ("    {0,5} {1,8:N3} {2,8:N3} {3,9:+0.000;-0.000;0.000}{4}" -f ($i*5), $ref.Profile[$i], $mine.Profile[$i], $d, $flag) `
            -ForegroundColor $(if ([math]::Abs($d) -gt 0.15) { 'Red' } else { 'Gray' })
    }
    Write-Host ("`n  平均绝对差 = {0:N3}   （< 0.10 算轮廓匹配良好）" -f ($sum/21))
    Write-Host ("  长宽比差异  = 模型 {0} vs 参考 {1}（比值 {2:N2}）" -f $mine.Aspect, $ref.Aspect, ($mine.Aspect/$ref.Aspect))
}

Write-Host "`n提示：参考图最好是「纯色背景 + 完整俯视」。PNG 透明图无需设背景。" -ForegroundColor DarkGray
