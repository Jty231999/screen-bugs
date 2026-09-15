package com.bt.flyprank

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.bt.flyprank.FlySpecies.HeadStyle
import com.bt.flyprank.FlySpecies.Locomotion
import com.bt.flyprank.FlySpecies.WingShape
import kotlin.math.abs
import kotlin.math.sin

/**
 * 一只虫子：运动状态（FlyMotion）+ 手绘外观（按品种 FlySpecies 区分）。
 *
 * 全部用 Canvas 画，不加载任何位图，所以 APK 里没有图片资源。
 *
 * 真实感主要靠这几处：
 *  - 翅脉：主脉 + 若干分支，翅形按品种区分
 *  - 扇翅残影：高频扇动的翅膀画两层不同相位的半透明翅膜，
 *    模拟真实昆虫翅膀在视觉上"糊成一片"的效果（比单层清晰翅更像真的）
 *  - 腿：三节带关节（股—胫—跗），按品种区分粗细与长度
 *  - 复眼：深色底 + 小眼面纹理 + 高光点
 *  - 品种纹理：鞘翅中缝、瓢虫斑点、蜜蜂条纹、蛾蝶翅斑
 *  - 动态投影：飞得高时影子更大更淡更靠后
 *
 * 性能说明（数量多时很关键）：
 *   任何依赖尺寸的 Shader 都在 [setSize] 里一次性建好并缓存。
 *   如果每帧重建，20 只虫子就是每秒上千次对象分配，会引发 GC 抖动掉帧。
 */
class FlyActor(
    context: Context,
    seed: Long,
    private val w: Float,
    private val h: Float,
    val species: FlySpecies,
) {
    val motion: FlyMotion = FlyMotion(
        java.util.Random(seed), w / 2f, h / 2f, species.traits()
    )

    private val density = context.resources.displayMetrics.density
    private val bodyPath = Path()
    private val wingPath = Path()
    private val legPath = Path()
    private val detailPath = Path()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val veinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val legPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 主界面滑块 1.0 对应的基准尺寸（44dp），再乘品种倍率。 */
    private var baseSizePx: Float = 44f * density
    var sizePx: Float = baseSizePx * species.sizeScale
        private set

    /** 最近一次运动事件（由 FlyMotion 通过 eventListener 写入）。 */
    private var pendingEvent: Int = 0

    /** 取出并清空事件，保证一个事件只被反馈一次。 */
    fun consumeEvent(): Int {
        val e = pendingEvent
        pendingEvent = 0
        return e
    }

    // ---- 缓存的渐变（依赖 sizePx，只在尺寸变化时重建） ----
    private var shadowShader: Shader? = null
    private var bodyShader: Shader? = null
    private var headShader: Shader? = null
    private var shineShader: Shader? = null

    init {
        motion.enterFromEdge(w, h)

        // 把运动事件缓存下来，由 FlyLayerView 每帧统一收集。
        // 不在回调里直接触发振动：一帧内可能多只虫子同时落地，
        // 聚合之后才能决定"只给最值得的那一个振"。
        motion.eventListener = { ev -> pendingEvent = ev }

        wingPaint.style = Paint.Style.FILL
        veinPaint.style = Paint.Style.STROKE
        veinPaint.strokeCap = Paint.Cap.ROUND
        legPaint.style = Paint.Style.STROKE
        legPaint.strokeCap = Paint.Cap.ROUND
        eyePaint.style = Paint.Style.FILL
        shinePaint.style = Paint.Style.FILL
        shadowPaint.style = Paint.Style.FILL
        shadowPaint.color = Color.argb(46, 0, 0, 0)
        markPaint.style = Paint.Style.FILL

        rebuildShaders()
    }

    /** 重建所有依赖尺寸的渐变（局部坐标半径约 ±24，绘制时统一 scale）。 */
    private fun rebuildShaders() {
        shadowShader = RadialGradient(
            0f, 0f, sizePx * 1.5f,
            intArrayOf(Color.argb(44, 0, 0, 0), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        bodyShader = LinearGradient(
            -24f, -11f, 16f, 11f,
            intArrayOf(
                species.bodyLighter, species.bodyCore,
                species.bodyDarker, species.bodyCore
            ),
            floatArrayOf(0f, 0.38f, 0.74f, 1f), Shader.TileMode.CLAMP
        )
        headShader = RadialGradient(
            18f, -2.4f, 9f,
            intArrayOf(species.bodyLighter, species.bodyCore, species.bodyDarker),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        shineShader = LinearGradient(
            -20f, -6f, 12f, -2f,
            intArrayOf(
                Color.argb(0, 255, 255, 255),
                Color.argb(130, 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    fun setSize(px: Float) {
        baseSizePx = px
        val newSize = px * species.sizeScale
        if (abs(newSize - sizePx) > 0.5f) {
            sizePx = newSize
            rebuildShaders()
        }
    }

    fun update(dt: Float) {
        motion.update(dt, w, h, sizePx)
    }

    fun startled(fromX: Float, fromY: Float) = motion.startled(fromX, fromY)

    /** 点到虫子身上了没（判定半径略大于身体，手感更"欠揍"）。 */
    fun hitTest(px: Float, py: Float): Boolean {
        val r = sizePx * 1.05f
        val dx = px - motion.x
        val dy = py - motion.y
        return dx * dx + dy * dy <= r * r
    }

    /** 手指靠近就提前躲开，比"点中才动"更自然。 */
    fun nearTest(px: Float, py: Float, extra: Float): Boolean {
        val r = sizePx * 1.05f + extra
        val dx = px - motion.x
        val dy = py - motion.y
        return dx * dx + dy * dy <= r * r
    }

    fun draw(canvas: Canvas) {
        val m = motion
        val s = sizePx / 24f
        val flap = 0.18f + 0.82f * abs(sin(m.wingPhase))
        val spread = m.wingSpread
        val detail = species.detailLevel

        canvas.save()
        canvas.translate(m.x, m.y + m.bobOffset(sizePx))
        canvas.rotate(m.angle)
        canvas.scale(s, s)

        // 动态投影：飞得"高"（翅膀全开）时影子更大更淡更靠后。
        // detail 0 的小虫子（果蝇、蚂蚁）只有几十像素，影子看不出来，直接省掉。
        if (detail >= 1) {
            val altitude = spread
            canvas.save()
            canvas.rotate(-m.angle)
            canvas.scale(1f / s, 1f / s)
            shadowPaint.shader = shadowShader
            shadowPaint.alpha = (52 - 20 * altitude).toInt().coerceIn(10, 60)
            val shadowR = sizePx * (1.15f + 0.45f * altitude)
            canvas.drawCircle(
                sizePx * 0.22f * altitude, sizePx * (0.55f + 0.35f * altitude),
                shadowR, shadowPaint
            )
            canvas.restore()
            shadowPaint.alpha = 255
        }

        // 远侧腿先画（压在身体下面）
        drawLegs(canvas, farSide = true)

        // 翅膀
        drawWings(canvas, flap, spread)

        // 身体 + 品种纹理
        val (headX, headR) = drawBody(canvas)

        // 触角画在头之后：头的位置是按 HeadStyle 算出来的，
        // 所以触角的根也必须用同一个坐标，否则会飘在头外面
        if (species.hasAntennae) drawAntennaeAt(canvas, flap, headX, headR)

        // 近侧腿
        drawLegs(canvas, farSide = false)

        canvas.restore()
    }

    // ================================================================ 翅膀

    /**
     * 画翅膀。
     *
     * 真实的翅数不一样，这里按品种区分：
     *   蜻蜓      四翅，前后翅形状接近，独立扇动（残影相位错开）
     *   蛾 / 蝶   四翅，前翅窄长、后翅圆阔，后翅位置略靠后靠外 —— 这是它们最好认的特征
     *   其它      只有一对翅（蝇、蜂），蝇类的平衡棒不画（太小看不见）
     *   鞘翅类   走 drawElytra
     *
     * 翅膀的铰链点不在正中心，而是在胸部（x≈+6），翅膀是从胸部伸出去并向后延伸的。
     */
    private fun drawWings(canvas: Canvas, flap: Float, spread: Float) {
        // 跳蚤：无翅 + 侧扁，走单独的画法
        if (species.wingShape == WingShape.FLEA) {
            drawFleaBack(canvas)
            return
        }
        // 无翅类（臭虫/虱/衣鱼）：背面就是分节的体节，画上翅膀就全错了
        if (species.wingShape == WingShape.WINGLESS) {
            drawWinglessBack(canvas, spread)
            return
        }
        if (species.wingShape == WingShape.ELYTRA) {
            drawElytra(canvas, spread)
            return
        }
        if (species.wingShape == WingShape.DRAGONFLY) {
            // 蜻蜓四翅：前后各一对，四片各自扇动
            drawWing(canvas, -1, flap, spread, hind = false)
            drawWing(canvas, +1, phase2(0.35f), spread, hind = false)
            drawWing(canvas, -1, phase2(0.18f), spread, hind = true)
            drawWing(canvas, +1, phase2(0.53f), spread, hind = true)
            return
        }
        if (species.wingShape == WingShape.MOTH || species.wingShape == WingShape.BUTTERFLY) {
            // 蛾蝶四翅：先画后翅（压在下面），再画前翅盖上去
            drawWing(canvas, -1, phase2(0.12f), spread, hind = true)
            drawWing(canvas, +1, phase2(0.44f), spread, hind = true)
            drawWing(canvas, -1, flap, spread, hind = false)
            drawWing(canvas, +1, phase2(0.35f), spread, hind = false)
            return
        }
        drawWing(canvas, -1, flap, spread, hind = false)
        drawWing(canvas, +1, phase2(0.35f), spread, hind = false)
    }

    private fun phase2(offset: Float) =
        0.18f + 0.82f * abs(sin(motion.wingPhase + offset))

    /**
     * 画一片翅膀。
     *
     * 这里做了「扇翅残影」：高频扇动的品种会叠一层相位偏移的半透明翅膜。
     * 真实昆虫扇翅频率远高于屏幕刷新率，肉眼看到的本就是一片模糊，
     * 所以残影比画一片锐利的翅膀更像真的。
     */
    private fun drawWing(
        canvas: Canvas,
        side: Int,
        flap: Float,
        spread: Float,
        hind: Boolean,
    ) {
        val shape = species.wingShape
        // 残影只给"看得见"的品种画：小虫子（detail 0）叠了也看不出来，纯浪费
        val fast = species.flapHz >= 26f && species.detailLevel >= 2
        val isBroad = shape == WingShape.MOTH || shape == WingShape.BUTTERFLY
        val angleStep = if (isBroad) 44f else 30f

        // 后翅张得更开、更靠后靠外；前翅盖在它上面
        val baseOpen = if (hind) {
            side * (angleStep + 52f * spread)
        } else {
            side * (angleStep + 32f * spread)
        }
        val lenScale = if (hind) 0.80f else 1f

        // 残影层（只在高频扇动时画，省性能）
        if (fast && spread > 0.5f) {
            drawWingMembrane(canvas, side, baseOpen + side * 7f, flap * 0.72f, spread, lenScale, 0.45f, hind)
        }
        drawWingMembrane(canvas, side, baseOpen, flap, spread, lenScale, 1f, hind)
    }

    private fun drawWingMembrane(
        canvas: Canvas,
        side: Int,
        openAngle: Float,
        flap: Float,
        spread: Float,
        lenScale: Float,
        alphaMul: Float,
        hind: Boolean,
    ) {
        val shape = species.wingShape
        val isBroad = shape == WingShape.MOTH || shape == WingShape.BUTTERFLY
        val len = (if (isBroad) (26f + 10f * spread) else (20f + 9f * spread)) *
                lenScale * (0.95f + 0.05f * flap)

        canvas.save()
        // 后翅挂点略靠后，视觉上像从胸部后缘伸出来
        if (hind) canvas.translate(-5f, side * 2.6f)
        canvas.rotate(openAngle)
        // 纵向压扁 = 扇动
        canvas.scale(1f, (0.26f + 0.74f * flap) * (0.5f + 0.5f * spread))

        wingPath.reset()
        when (shape) {
            WingShape.MOTH -> {
                wingPath.moveTo(4f, 0f)
                wingPath.cubicTo(2f, -12f, -14f, -15f, -len, -6f)
                wingPath.cubicTo(-len - 5f, -2f, -len - 3f, 4f, -8f, 5f)
                wingPath.cubicTo(-1f, 5.5f, 3f, 3f, 4f, 0f)
            }
            WingShape.BUTTERFLY -> {
                if (hind) {
                    // 蝴蝶后翅：更圆更阔，边缘带一点波浪
                    wingPath.moveTo(3f, 0f)
                    wingPath.cubicTo(1f, -9f, -9f, -13f, -len + 2f, -9f)
                    wingPath.cubicTo(-len - 4f, -5f, -len - 3f, 3f, -len + 2f, 6.5f)
                    wingPath.cubicTo(-9f, 9f, 0f, 6f, 3f, 0f)
                } else {
                    // 蝴蝶前翅：更尖更长，向斜前方伸出
                    wingPath.moveTo(4f, 0f)
                    wingPath.cubicTo(3f, -11f, -10f, -17f, -len, -9f)
                    wingPath.cubicTo(-len - 6f, -4f, -len - 4f, 3f, -9f, 6f)
                    wingPath.cubicTo(-1f, 7f, 3f, 3.5f, 4f, 0f)
                }
            }
            WingShape.DRAGONFLY -> {
                // 蜻蜓翅：细长，前后缘平行
                wingPath.moveTo(4f, -1.4f)
                wingPath.cubicTo(-4f, -5.2f, -14f, -6.4f, -len - 6f, -3.4f)
                wingPath.lineTo(-len - 6f, -0.6f)
                wingPath.cubicTo(-14f, 1.2f, -4f, 2.2f, 4f, 1.4f)
                wingPath.close()
            }
            WingShape.BEE -> {
                wingPath.moveTo(4f, 0f)
                wingPath.cubicTo(2f, -6f, -10f, -8.4f, -len, -3.4f)
                wingPath.cubicTo(-len - 3f, -1.2f, -len - 2f, 1.2f, -7f, 2.2f)
                wingPath.cubicTo(-1f, 2.8f, 3f, 1.8f, 4f, 0f)
            }
            else -> {
                wingPath.moveTo(4f, 0f)
                wingPath.cubicTo(2f, -7.6f, -12f, -9.8f, -len, -3.6f)
                wingPath.cubicTo(-len - 3.6f, -1.2f, -len - 2f, 1.6f, -8f, 2.6f)
                wingPath.cubicTo(-1f, 3.2f, 3f, 2f, 4f, 0f)
            }
        }
        wingPath.close()

        val tint = species.wingTint
        val baseAlpha = when (shape) {
            WingShape.MOTH -> 158
            WingShape.BUTTERFLY -> 176
            WingShape.DRAGONFLY -> 96
            else -> 104
        }
        wingPaint.color = Color.argb(
            ((baseAlpha + 36 * flap) * alphaMul).toInt().coerceIn(0, 255),
            Color.red(tint), Color.green(tint), Color.blue(tint)
        )
        canvas.drawPath(wingPath, wingPaint)

        // 翅脉：主脉 + 分支，比单根线更像真翅膀。
        // detail 0 的小虫子翅膀只有十几像素，翅脉完全看不见，跳过。
        if (species.detailLevel >= 1) {
            val veinBase = if (side < 0) 62 else 92
            veinPaint.color = Color.argb(
                (veinBase * alphaMul).toInt().coerceIn(0, 255), 132, 142, 158
            )
            veinPaint.strokeWidth = 0.5f
            // 主脉
            canvas.drawLine(3f, 0f, -len * 0.92f, -1.8f, veinPaint)
            // 上缘支脉
            canvas.drawLine(1f, -1f, -len * 0.62f, -5.4f, veinPaint)
            canvas.drawLine(-len * 0.3f, -2.4f, -len * 0.72f, -5.8f, veinPaint)
            // 下缘支脉
            canvas.drawLine(1f, 1f, -len * 0.55f, 3.4f, veinPaint)
            if (shape == WingShape.MOTH || shape == WingShape.BUTTERFLY) {
                canvas.drawLine(-len * 0.5f, -2.6f, -len * 0.45f, 2.8f, veinPaint)
            }
            // 翅痣（蜻蜓和蜂类翅端的小色斑）
            if (shape == WingShape.DRAGONFLY || shape == WingShape.BEE) {
                veinPaint.color = Color.argb((190 * alphaMul).toInt().coerceIn(0, 255), 90, 96, 106)
                canvas.drawCircle(-len - 2.4f, -2f, 1.15f, veinPaint)
            }
        }

        // 蝶蛾翅斑
        if (species.wingAccent != 0 && (shape == WingShape.BUTTERFLY || shape == WingShape.MOTH)) {
            markPaint.color = Color.argb(
                (150 * alphaMul).toInt().coerceIn(0, 255),
                Color.red(species.wingAccent),
                Color.green(species.wingAccent),
                Color.blue(species.wingAccent)
            )
            if (shape == WingShape.BUTTERFLY) {
                canvas.drawCircle(-len * 0.62f, -2.2f, 2.7f, markPaint)
                canvas.drawCircle(-len * 0.34f, -3.4f, 1.6f, markPaint)
                canvas.drawCircle(-len * 0.55f, 2.6f, 1.5f, markPaint)
                markPaint.color = Color.argb(
                    (190 * alphaMul).toInt().coerceIn(0, 255),
                    Color.red(species.wingTint),
                    Color.green(species.wingTint),
                    Color.blue(species.wingTint)
                )
                canvas.drawCircle(-len * 0.62f, -2.2f, 1.1f, markPaint)
            } else {
                // 蛾翅：几条横向波纹
                markPaint.color = Color.argb((110 * alphaMul).toInt().coerceIn(0, 255),
                    Color.red(species.wingAccent), Color.green(species.wingAccent),
                    Color.blue(species.wingAccent))
                canvas.drawRect(-len * 0.80f, -6.4f, -len * 0.74f, 3.2f, markPaint)
                canvas.drawRect(-len * 0.60f, -5.0f, -len * 0.55f, 2.6f, markPaint)
            }
        }

        canvas.restore()
    }

    /**
     * 无翅类的背面（臭虫 / 虱 / 衣鱼）。
     *
     * 它们背面看到的就是**分节的腹部**，没有翅膀。这里画：
     *  - 宽扁的身体上覆盖层分节的横带（节间缢缩）
     *  - 两侧半透明的浅色边缘（臭虫很明显的特征）
     *  - 银色鳞片反光（衣鱼）
     *
     * 具体轮廓由 drawBody 的腹部负责，这里只补"背面的分节纹理"。
     */
    private fun drawWinglessBack(canvas: Canvas, spread: Float) {
        val ground = species.locomotion == Locomotion.CRAWL
        val squash = if (ground) 0.80f else 1f
        val stretch = if (ground) 1.08f else 1f

        // 节间横带：把腹部横向切成若干节
        val segCount = 5
        for (i in 0 until segCount) {
            val t = i.toFloat() / (segCount - 1)
            val bx = (4f - 26f * t) * stretch
            val half = (9.0f - 6.4f * t * t) * squash
            // 每一节的后缘略呈弧形 —— 臭虫的腹节后缘是波浪状的
            val wave = sin(t * 3.4f) * 0.8f
            veinPaint.color = Color.argb(
                (110 - i * 12).coerceAtLeast(40), 16, 10, 8
            )
            veinPaint.strokeWidth = 0.9f
            for (side in intArrayOf(-1, 1)) {
                canvas.drawLine(bx, side * half, bx + wave, side * 1.6f, veinPaint)
            }
        }

        // 两侧半透明的浅色边缘（臭虫的腹缘）
        if (species.headStyle == HeadStyle.HEAD_FLAT && species.bodySegments >= 3) {
            markPaint.color = Color.argb(70, 255, 226, 196)
            for (side in intArrayOf(-1, 1)) {
                detailPath.reset()
                detailPath.moveTo(6f * stretch, side * 8.4f * squash)
                detailPath.cubicTo(
                    0f, side * 9.8f * squash,
                    -14f * stretch, side * 8.8f * squash,
                    -22f * stretch, side * 4.0f * squash
                )
                detailPath.cubicTo(-18f * stretch, side * 7.4f * squash, 0f, side * 7.6f * squash, 6f * stretch, side * 6.6f * squash)
                detailPath.close()
                canvas.drawPath(detailPath, markPaint)
            }
        }

        // 银色鳞片反光（衣鱼）：整个背面一层冷色高光
        if (species.bodyLighter == 0xFFD8DEE4.toInt()) {
            shinePaint.shader = LinearGradient(
                -20f, -6f, 10f, 6f,
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(150, 235, 244, 252),
                    Color.argb(60, 210, 226, 240),
                    Color.argb(0, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.34f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.rotate(-6f)
            canvas.drawOval(-22f * stretch, -7.4f * squash, 8f * stretch, -1.0f * squash, shinePaint)
            canvas.restore()
        }
    }

    /** 判断是不是瓢虫：它的鞘翅覆盖整个背面，需要单独的画法。 */
    private fun isLadybug(): Boolean = species.bodyCore == 0xFFD8361A.toInt() ||
            species.bodyCore == 0xFFD8361F.toInt()

    /** 德国小蠊：靠"棕黄底色 + 鞘翅"判定。 */
    private fun isGermanCockroach(): Boolean = species.bodyCore == 0xFF3A2016.toInt()

    /** 蟋蟀。 */
    private fun isCricket(): Boolean = species.bigHindLegs

    /**
     * 蟑螂前胸背板上的**两条深色纵纹**。
     *
     * 这是德国小蠊最好认的背面特征（也是和棕带蟑螂区分的关键：
     * 后者是腹部横带，没有前胸纵纹）。真实结构是：
     * 前胸整体棕黄，上面有两条**大致平行、贯穿前胸全长**的深色纵纹
     * （从头部后方一直到翅膀基部）。背面从左到右依次是：
     * 浅色外缘 — 深色纵纹 — **浅色中央区** — 深色纵纹 — 浅色外缘。
     *
     * 注意：真实蟑螂的头部从背面**看不见**（被前胸盖住），
     * 所以这里也顺便把前胸画到盖住头。
     */
    private fun drawCockroachPronotum(canvas: Canvas, squash: Float) {
        val px = thoraxFront - 6f      // 前胸中心
        val halfW = 9.0f
        // 前胸：横宽的板，前缘略凸、两侧圆
        detailPath.reset()
        detailPath.moveTo(px + 8.6f, 0f)
        detailPath.cubicTo(
            px + 8.2f, -halfW * 0.96f * squash,
            px - 6.0f, -halfW * 0.88f * squash,
            px - 7.4f, 0f
        )
        detailPath.cubicTo(
            px - 6.0f, halfW * 0.88f * squash,
            px + 8.2f, halfW * 0.96f * squash,
            px + 8.6f, 0f
        )
        detailPath.close()
        bodyPaint.shader = LinearGradient(
            px + 8.6f, -halfW * squash, px - 7.4f, halfW * squash,
            intArrayOf(0xFFE5C68C.toInt(), 0xFFC89552.toInt(), 0xFF8A5A2B.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(detailPath, bodyPaint)

        // 两条贯穿全长的深色纵纹
        markPaint.color = Color.argb(225, 36, 23, 8)
        for (side in intArrayOf(-1, 1)) {
            val yc = side * halfW * 0.42f * squash
            detailPath.reset()
            detailPath.moveTo(px + 8.4f, yc - halfW * 0.11f * squash)
            detailPath.lineTo(px - 7.0f, yc - halfW * 0.13f * squash)
            detailPath.lineTo(px - 7.0f, yc + halfW * 0.13f * squash)
            detailPath.lineTo(px + 8.4f, yc + halfW * 0.11f * squash)
            detailPath.close()
            canvas.drawPath(detailPath, markPaint)
        }

        // 表面上油亮光泽（德国小蠊体表有油亮感）
        shinePaint.shader = LinearGradient(
            px + 6f, -6f * squash, px - 4f, 4f * squash,
            intArrayOf(
                Color.argb(0, 255, 255, 255),
                Color.argb(110, 255, 248, 226),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.rotate(-5f)
        canvas.drawOval(px - 5f, -3.2f * squash, px + 6f, -0.6f * squash, shinePaint)
        canvas.restore()
    }

    /**
     * 蟋蟀前胸上的**五个深色斑**，以及复眼之间那条**深色横带**。
     *
     * 这两处是蟋蟀背面最好认的标记。另外蟋蟀的头**几乎和前胸等宽**
     * （甚至略宽），前胸本身则是**宽大于长**—— 和蟑螂正好相反。
     */
    private fun drawCricketMarks(canvas: Canvas, squash: Float, headX: Float, headR: Float) {
        val px = thoraxFront - 5f
        // 前胸的 5 个深色斑
        markPaint.color = Color.argb(200, 94, 67, 33)
        canvas.drawCircle(px + 1.5f, 0f, 2.0f, markPaint)                       // 中
        for (side in intArrayOf(-1, 1)) {
            canvas.drawCircle(px + 3.5f, side * 3.4f * squash, 1.6f, markPaint) // 前侧
            canvas.drawCircle(px - 2.6f, side * 3.9f * squash, 1.8f, markPaint) // 后侧
        }

        // 复眼之间的深色横带（不规则）
        markPaint.color = Color.argb(215, 51, 37, 15)
        detailPath.reset()
        detailPath.moveTo(headX - headR * 0.30f, -headR * 0.62f)
        detailPath.lineTo(headX + headR * 0.55f, -headR * 0.34f)
        detailPath.lineTo(headX + headR * 0.50f, headR * 0.30f)
        detailPath.lineTo(headX - headR * 0.34f, headR * 0.58f)
        detailPath.lineTo(headX - headR * 0.18f, 0f)
        detailPath.close()
        canvas.drawPath(detailPath, markPaint)
    }

    /**
     * 瓢虫的背面。
     *
     * 解剖要点（数据来源见 README）：
     *  - 体形是**椭圆**，宽:长 = 0.77 —— 不是圆。长 5.2~8.6mm、宽 4.0~6.6mm。
     *  - 鞘翅**完全盖住腹部**，所以整块背面就是一个椭圆。
     *  - 一条细黑**中缝**贯穿全长（两片鞘翅的接缝）。
     *  - 斑点固定 7 枚：每鞘翅 3 枚（前、中、后）+ 1 枚**盾片斑**跨在中缝上。
     *  - 两鞘翅前缘外侧各有一枚**小白斑**。
     *  - 前胸**黑底 + 两枚白斑**（前侧角），不是纯黑。
     *  - 后缘是钝的，不收成尖。
     */
    private fun drawLadybugBack(canvas: Canvas) {
        // 宽:长 = 0.77，令长半径 23 → 宽半径 17.7
        val rx = 23f
        val ry = 17.7f

        // 鞘翅：整块椭圆（两片鞘翅合起来就是整个背面）
        detailPath.reset()
        detailPath.addOval(-rx, -ry, rx * 0.86f, ry, Path.Direction.CW)
        bodyPaint.shader = LinearGradient(
            -rx, -ry, rx * 0.86f, ry,
            intArrayOf(
                species.bodyLighter, species.bodyCore,
                species.bodyCore, species.bodyDarker
            ),
            floatArrayOf(0f, 0.3f, 0.72f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(detailPath, bodyPaint)

        // 背部隆起高光（瓢虫是 strongly convex，不是平的）
        shinePaint.shader = LinearGradient(
            -rx * 0.7f, -ry * 0.9f, rx * 0.3f, ry * 0.2f,
            intArrayOf(
                Color.argb(0, 255, 255, 255),
                Color.argb(120, 255, 250, 235),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.rotate(-8f)
        canvas.drawOval(-rx * 0.72f, -ry * 0.62f, rx * 0.28f, -ry * 0.06f, shinePaint)
        canvas.restore()

        // 中缝：贯穿全长的细黑线
        veinPaint.color = Color.argb(200, 16, 12, 10)
        veinPaint.strokeWidth = 1.1f
        canvas.drawLine(rx * 0.84f, 0f, -rx * 0.97f, 0f, veinPaint)

        // 盾片：中缝前端的小三角
        markPaint.color = Color.argb(215, 18, 14, 12)
        detailPath.reset()
        detailPath.moveTo(rx * 0.70f, 0f)
        detailPath.lineTo(rx * 0.42f, -ry * 0.20f)
        detailPath.lineTo(rx * 0.42f, ry * 0.20f)
        detailPath.close()
        canvas.drawPath(detailPath, markPaint)

        // 7 枚斑点：盾片斑（跨中缝）+ 每侧 3 枚
        markPaint.color = species.wingAccent
        canvas.drawCircle(rx * 0.44f, 0f, 2.6f, markPaint)
        for (side in intArrayOf(-1, 1)) {
            canvas.drawCircle(rx * 0.10f, side * ry * 0.46f, 3.0f, markPaint)
            canvas.drawCircle(-rx * 0.34f, side * ry * 0.40f, 2.8f, markPaint)
            canvas.drawCircle(-rx * 0.72f, side * ry * 0.28f, 2.4f, markPaint)
        }

        // 两鞘翅前缘外侧的小白斑
        markPaint.color = Color.argb(235, 242, 242, 234)
        for (side in intArrayOf(-1, 1)) {
            canvas.drawCircle(rx * 0.62f, side * ry * 0.42f, 1.7f, markPaint)
        }

        // 前胸背板：黑底横向梯形
        detailPath.reset()
        detailPath.moveTo(rx * 1.02f, 0f)
        detailPath.cubicTo(
            rx * 1.00f, -ry * 0.62f,
            rx * 0.76f, -ry * 0.80f,
            rx * 0.58f, -ry * 0.74f
        )
        detailPath.cubicTo(
            rx * 0.64f, -ry * 0.34f,
            rx * 0.64f, ry * 0.34f,
            rx * 0.58f, ry * 0.74f
        )
        detailPath.cubicTo(
            rx * 0.76f, ry * 0.80f,
            rx * 1.00f, ry * 0.62f,
            rx * 1.02f, 0f
        )
        detailPath.close()
        bodyPaint.shader = LinearGradient(
            rx * 1.02f, -ry * 0.6f, rx * 0.58f, ry * 0.6f,
            intArrayOf(0xFF2A2420.toInt(), 0xFF14100E.toInt(), 0xFF241E1A.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(detailPath, bodyPaint)

        // 前胸的两枚白斑（前侧角）
        markPaint.color = Color.argb(240, 242, 242, 234)
        for (side in intArrayOf(-1, 1)) {
            detailPath.reset()
            detailPath.moveTo(rx * 0.96f, side * ry * 0.34f)
            detailPath.lineTo(rx * 0.90f, side * ry * 0.62f)
            detailPath.lineTo(rx * 0.76f, side * ry * 0.56f)
            detailPath.lineTo(rx * 0.80f, side * ry * 0.30f)
            detailPath.close()
            canvas.drawPath(detailPath, markPaint)
        }
    }

    /**
     * 跳蚤的背面。
     *
     * 它和所有其它品种都不同：**侧扁**（左右压扁），所以俯视是一条窄长条。
     * 关键特征（来源见 README）：
     *  - 轮廓是 **3:1 的窄长条**，大致平行边、向尾端收圆 —— 不是椭圆
     *  - 头**三角形**、额头平斜
     *  - **颊栉**：眼下方一排 8~9 枚扁刺（"胡子"）
     *  - **前胸栉**：头胸交界处一排横刺（"项圈"）
     *    两排刺都**向后指**
     *  - 眼是**黑色圆形单眼**，在触角窝前方
     *  - 腹部 7 节，每节背板后缘一排向后指的鬃毛
     *  - 尾端一个圆形**臀板**
     */
    private fun drawFleaBack(canvas: Canvas) {
        val rx = 22f          // 纵向半径
        val ry = 7.4f         // 横向半径：22/7.4 ≈ 3.0 —— 这就是 3:1 的来源

        // 身体：窄长条，前段略收（头颈）、中后段平行、尾端收圆
        detailPath.reset()
        detailPath.moveTo(rx * 0.95f, 0f)
        detailPath.cubicTo(
            rx * 0.72f, -ry * 0.86f,
            rx * 0.10f, -ry * 0.98f,
            -rx * 0.38f, -ry * 1.0f
        )
        detailPath.cubicTo(
            -rx * 0.80f, -ry * 0.96f,
            -rx * 1.0f, -ry * 0.52f,
            -rx * 1.0f, 0f
        )
        detailPath.cubicTo(
            -rx * 1.0f, ry * 0.52f,
            -rx * 0.80f, ry * 0.96f,
            -rx * 0.38f, ry * 1.0f
        )
        detailPath.cubicTo(
            rx * 0.10f, ry * 0.98f,
            rx * 0.72f, ry * 0.86f,
            rx * 0.95f, 0f
        )
        detailPath.close()
        bodyPaint.shader = LinearGradient(
            rx, -ry, -rx, ry,
            intArrayOf(
                species.bodyLighter, species.bodyCore,
                species.bodyDarker, species.bodyCore
            ),
            floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(detailPath, bodyPaint)

        // 硬壳油亮感（跳蚤体表高度硬化、抛光）
        shinePaint.shader = LinearGradient(
            -rx * 0.2f, -ry * 0.9f, rx * 0.4f, ry * 0.2f,
            intArrayOf(
                Color.argb(0, 255, 255, 255),
                Color.argb(130, 255, 246, 230),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.rotate(-3f)
        canvas.drawOval(-rx * 0.42f, -ry * 0.66f, rx * 0.58f, -ry * 0.10f, shinePaint)
        canvas.restore()

        // 腹部 7 节的节间线 + 每节后缘向后指的鬃毛
        for (i in 0 until 7) {
            val t = i / 6f
            val bx = rx * 0.34f - t * rx * 1.16f
            val half = ry * (0.96f - 0.30f * t * t)
            veinPaint.color = Color.argb(120, 26, 15, 8)
            veinPaint.strokeWidth = 0.75f
            for (side in intArrayOf(-1, 1)) {
                canvas.drawLine(bx, side * half, bx, side * half * 0.18f, veinPaint)
            }
            // 后缘的鬃毛（全部向后指）
            veinPaint.color = Color.argb(190, 42, 26, 15)
            veinPaint.strokeWidth = 0.7f
            for (side in intArrayOf(-1, 1)) {
                canvas.drawLine(bx, side * half * 0.84f, bx - 2.4f, side * half * 1.14f, veinPaint)
            }
        }

        // 颊栉：眼下方一排扁刺，向后指（"胡子"）
        for (side in intArrayOf(-1, 1)) {
            drawSpineCombSide(canvas, rx * 0.74f, side * ry * 0.30f, side * ry * 0.98f, 4)
        }

        // 前胸栉：头胸交界处一排横刺（"项圈"）
        drawSpineCombSide(canvas, rx * 0.44f, -ry * 0.99f, ry * 0.99f, 5)
        drawSpineCombSide(canvas, rx * 0.44f, ry * 0.99f, -ry * 0.99f, 5)

        // 黑色圆形单眼（触角窝前方）
        eyePaint.color = species.eyeMain
        for (side in intArrayOf(-1, 1)) {
            canvas.drawCircle(rx * 0.80f, side * ry * 0.52f, 1.9f, eyePaint)
        }
        eyePaint.color = Color.argb(150, 200, 200, 200)
        for (side in intArrayOf(-1, 1)) {
            canvas.drawCircle(rx * 0.82f, side * ry * 0.44f, 0.6f, eyePaint)
        }

        // 尾端臀板：一个带小刺的圆形感觉板
        markPaint.color = Color.argb(200, 52, 34, 20)
        canvas.drawCircle(-rx * 0.90f, 0f, 2.6f, markPaint)
        veinPaint.color = Color.argb(170, 30, 18, 10)
        veinPaint.strokeWidth = 0.45f
        for (i in 0 until 8) {
            val a = (i / 8f) * 6.2831855f
            canvas.drawLine(
                -rx * 0.90f + kotlin.math.cos(a) * 1.4f, kotlin.math.sin(a) * 1.4f,
                -rx * 0.90f + kotlin.math.cos(a) * 3.3f, kotlin.math.sin(a) * 3.3f,
                veinPaint
            )
        }
    }

    /** 沿身体一侧铺一排向后指的刺。 */
    private fun drawSpineCombSide(
        canvas: Canvas,
        x: Float, yStart: Float, yEnd: Float, count: Int,
    ) {
        veinPaint.color = Color.argb(225, 58, 36, 21)
        veinPaint.strokeWidth = 1.5f
        for (i in 0 until count) {
            val t = (i + 0.5f) / count
            val y = yStart + (yEnd - yStart) * t
            canvas.drawLine(x, y, x - 4.4f, y, veinPaint)
        }
    }

    /** 鞘翅（甲虫、蟑螂、蟋蟀的硬壳）：不开不扇，画成盖住背部的壳。 */
    private fun drawElytra(canvas: Canvas, spread: Float) {
        // 瓢虫的鞘翅几乎覆盖整个背面，走单独的画法
        if (isLadybug()) {
            drawLadybugBack(canvas)
            return
        }
        // 蟑螂/蚂蚁/蟋蟀平时壳是合拢的，起飞瞬间才微微张开
        val open = (1f - spread) * 0f + spread * 5f

        for (side in intArrayOf(-1, 1)) {
            canvas.save()
            canvas.rotate(side * (6f + open))

            detailPath.reset()
            detailPath.moveTo(19f, 0f)
            detailPath.cubicTo(14f, side * 8.6f, -2f, side * 11.6f, -22f, side * 4.6f)
            detailPath.cubicTo(-25f, side * 2.2f, -24f, side * -0.6f, -14f, side * -0.5f)
            detailPath.cubicTo(-2f, side * -0.4f, 12f, side * 0.2f, 19f, 0f)
            detailPath.close()

            bodyPaint.shader = LinearGradient(
                19f, side * 11f, -22f, side * 4f,
                intArrayOf(
                    species.bodyLighter, species.bodyCore,
                    species.bodyDarker
                ),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawPath(detailPath, bodyPaint)

            // 壳面高光
            shinePaint.shader = shineShader
            canvas.save()
            canvas.rotate(side * -5f)
            canvas.drawOval(-18f, side * 2.2f, 10f, side * 6.4f, shinePaint)
            canvas.restore()

            // 瓢虫斑点
            if (species.wingAccent == 0xFF141414.toInt()) {
                markPaint.color = species.wingAccent
                canvas.drawCircle(2f, side * 6.6f, 2.5f, markPaint)
                canvas.drawCircle(-8f, side * 6.0f, 2.3f, markPaint)
                canvas.drawCircle(-16f, side * 4.4f, 1.9f, markPaint)
            }

            canvas.restore()
        }

        // 中缝
        veinPaint.color = Color.argb(160, 12, 10, 8)
        veinPaint.strokeWidth = 0.9f
        canvas.drawLine(19f, 0f, -22f, 0f, veinPaint)
    }

    // ================================================================ 身体
    //
    // 真实昆虫是「头 + 胸 + 腹」三节，不是一个整椭圆。这里按三节分别绘制：
    //   腹部  向后延伸、略下垂，蚁类的腹节之间有明显缢缩
    //   胸部  最粗的一段，所有腿和翅膀都长在这里
    //   头部  按 HeadStyle 分五种形状
    //
    // 局部坐标：x 正方向朝前（头部方向），原点大致在胸部中心。

    /** 胸部前缘：头挂在这里 */
    private val thoraxFront = 11f

    /**
     * 画完头胸腹三段，返回头部中心与半径，供触角和复眼定位。
     */
    private fun drawBody(canvas: Canvas): Pair<Float, Float> {
        if (species.bristles) drawFuzz(canvas)

        val ground = species.locomotion == Locomotion.CRAWL
        // 爬行类更扁平（贴地爬行），飞行类更饱满
        val squash = if (ground) 0.80f else 1f
        val stretch = if (ground) 1.08f else 1f

        drawAbdomen(canvas, squash, stretch)
        drawThorax(canvas, squash, stretch)

        // 蜜蜂：腹部黄带 + 胸部金褐色绒毛团（它最好认的特征）
        if (isBeeLike()) {
            drawBeeStripes(canvas, squash)
            drawBeeThoraxFuzz(canvas)
        }

        val head = drawHead(canvas)
        drawCompoundEyes(canvas, head.first, head.second)

        // 蟋蟀：前胸 5 斑 + 复眼间深色横带（画在头之后才盖得住）
        if (isCricket()) drawCricketMarks(canvas, squash, head.first, head.second)

        // 蟑螂：前胸背板连两条纵纹，画在最后 —— 真实的蟑螂从背面看不到头，
        // 前胸会把头盖住
        if (isGermanCockroach()) drawCockroachPronotum(canvas, squash)

        return head
    }

    /** 腹部：向后收窄并略微下垂，蚁类画出分节缢缩。 */
    private fun drawAbdomen(canvas: Canvas, squash: Float, stretch: Float) {
        val isAntLike = species.headStyle == HeadStyle.HEAD_FREE && species.sizeScale < 0.9f

        // 蚁类腹部是独立的卵形，和前胸之间由细腰连接
        val backStart = if (isAntLike) -4f else 6f
        val backEnd = if (isAntLike) -22f else -23.5f
        val maxHalf = if (isAntLike) 7.5f else 9.4f

        bodyPath.reset()
        bodyPath.moveTo(backStart * stretch, 0f)
        bodyPath.cubicTo(
            (backStart - 4f) * stretch, -maxHalf * squash,
            (backStart - 12f) * stretch, -(maxHalf + 1.2f) * squash,
            -8f * stretch, -maxHalf * squash
        )
        bodyPath.cubicTo(
            -16f * stretch, -(maxHalf - 1.2f) * squash,
            backEnd * stretch, -(maxHalf - 5.2f) * squash,
            backEnd * stretch, 0f
        )
        bodyPath.cubicTo(
            backEnd * stretch, (maxHalf - 5.2f) * squash,
            -16f * stretch, (maxHalf - 1.2f) * squash,
            -8f * stretch, maxHalf * squash
        )
        bodyPath.cubicTo(
            (backStart - 12f) * stretch, (maxHalf + 1.2f) * squash,
            (backStart - 4f) * stretch, maxHalf * squash,
            backStart * stretch, 0f
        )
        bodyPath.close()
        bodyPaint.shader = bodyShader
        canvas.drawPath(bodyPath, bodyPaint)

        // 背脊高光
        shinePaint.shader = shineShader
        canvas.save()
        canvas.rotate(-8f)
        canvas.drawOval(-19f * stretch, -5.4f * squash, 10f * stretch, -1.6f * squash, shinePaint)
        canvas.restore()

        // 金属虹彩：绿色金属蝇和蟑螂的甲壳有很强的镜面反射带
        if (isMetallic()) drawIridescence(canvas, -20f, 8f, squash)

        // 腹节缢缩
        veinPaint.color = Color.argb(105, 8, 8, 8)
        veinPaint.strokeWidth = 0.85f
        if (isAntLike) {
            // 蚁类：3~4 道明显的节间缢缩
            for (i in 0 until 3) {
                val bx = -7f - i * 5.2f
                val hw = (maxHalf - 0.6f - i * 1.1f) * squash
                for (side in intArrayOf(-1, 1)) {
                    canvas.drawLine(bx, side * hw, bx, side * 1.4f, veinPaint)
                }
            }
            // 细腰：腹部与前胸之间的缢缩
            veinPaint.color = Color.argb(150, 6, 6, 6)
            veinPaint.strokeWidth = 1.5f
            for (side in intArrayOf(-1, 1)) {
                canvas.drawLine(backStart + 1f, side * 3.4f, backStart - 1.5f, side * 2.4f, veinPaint)
            }
        } else if (species.bodySegments >= 2) {
            for (i in 1..2) {
                val bx = -3f - i * 6.5f
                val halfW = (9.6f - i * 1.6f) * squash
                for (side in intArrayOf(-1, 1)) {
                    canvas.drawLine(bx, side * halfW, bx, side * 1.4f, veinPaint)
                }
            }
            // 胸腹之间
            veinPaint.color = Color.argb(120, 8, 8, 8)
            veinPaint.strokeWidth = 0.9f
            for (side in intArrayOf(-1, 1)) {
                canvas.drawLine(6f, side * 8.4f * squash, 6f, side * 1.2f, veinPaint)
            }
        }

        // 胸背刚毛
        if (species.bristles) {
            veinPaint.color = Color.argb(150, 20, 20, 22)
            veinPaint.strokeWidth = 0.7f
            for (i in 0 until 6) {
                val bx = -6f + i * 3.4f
                canvas.drawLine(bx, -8.6f * squash, bx + 1.2f, -11.6f * squash, veinPaint)
            }
        }
    }

    /** 胸部：最粗的一段，腿和翅膀都长在这里，前端有明显的「颈部」收窄。 */
    private fun drawThorax(canvas: Canvas, squash: Float, stretch: Float) {
        val front = thoraxFront
        bodyPath.reset()
        bodyPath.moveTo(front, 0f)
        bodyPath.cubicTo(front - 1f, -8.8f * squash, front - 6f, -10.2f * squash, front - 10f, -9.4f * squash)
        bodyPath.cubicTo(front - 14f, -8.4f * squash, front - 15f, -2f, front - 15f, 0f)
        bodyPath.cubicTo(front - 15f, 2f, front - 14f, 8.4f * squash, front - 10f, 9.4f * squash)
        bodyPath.cubicTo(front - 6f, 10.2f * squash, front - 1f, 8.8f * squash, front, 0f)
        bodyPath.close()
        bodyPaint.shader = bodyShader
        canvas.drawPath(bodyPath, bodyPaint)

        // 前胸背板：头后面那一小块盾片，飞行类特别明显
        if (species.headStyle != HeadStyle.HEAD_FREE) {
            bodyPath.reset()
            bodyPath.moveTo(front - 1f, 0f)
            bodyPath.cubicTo(front - 1.5f, -6.6f * squash, front - 5f, -7.6f * squash, front - 8f, -6.6f * squash)
            bodyPath.cubicTo(front - 9.5f, -3f, front - 9.5f, 3f, front - 8f, 6.6f * squash)
            bodyPath.cubicTo(front - 5f, 7.6f * squash, front - 1.5f, 6.6f * squash, front - 1f, 0f)
            bodyPath.close()
            bodyPaint.shader = headShader
            canvas.drawPath(bodyPath, bodyPaint)
        }

        // 胸部金属反光（绿蝇的胸背非常亮）
        if (isMetallic()) drawIridescence(canvas, front - 12f, front - 1f, squash)
    }

    /**
     * 头部：按 HeadStyle 画五种不同的头。
     * 返回头心 x 与头半径，供复眼定位使用。
     */
    private fun drawHead(canvas: Canvas): Pair<Float, Float> {
        val style = species.headStyle
        val (headX, headR) = when (style) {
            HeadStyle.HEAD_FLY -> Pair(thoraxFront + 6.0f, 7.2f)
            HeadStyle.HEAD_DOME -> Pair(thoraxFront + 7.0f, 8.2f)
            HeadStyle.HEAD_POINTY -> Pair(thoraxFront + 6.5f, 5.8f)
            HeadStyle.HEAD_BROAD -> Pair(thoraxFront + 6.2f, 7.6f)
            HeadStyle.HEAD_FREE -> Pair(thoraxFront + 8.0f, 6.8f)
            // 臭虫/虱/衣鱼：头小而宽扁，缩在前胸之下
            HeadStyle.HEAD_FLAT -> Pair(thoraxFront + 5.0f, 5.4f)
        }

        bodyPath.reset()
        when (style) {
            HeadStyle.HEAD_FLY -> {
                // 蝇类：头几乎全是复眼，呈半球紧贴前胸，后缘平直
                bodyPath.moveTo(headX + headR * 0.95f, 0f)
                bodyPath.cubicTo(
                    headX + headR * 0.75f, -headR * 1.02f,
                    headX - headR * 0.5f, -headR * 1.12f,
                    headX - headR * 0.85f, -headR * 0.55f
                )
                bodyPath.lineTo(headX - headR * 0.85f, headR * 0.55f)
                bodyPath.cubicTo(
                    headX - headR * 0.5f, headR * 1.12f,
                    headX + headR * 0.75f, headR * 1.02f,
                    headX + headR * 0.95f, 0f
                )
            }
            HeadStyle.HEAD_DOME -> {
                // 蜻蜓：复眼极大，头呈饱满球形
                bodyPath.addCircle(headX, 0f, headR, Path.Direction.CW)
            }
            HeadStyle.HEAD_POINTY -> {
                // 甲虫/蚊：小而尖，缩在前胸下
                bodyPath.moveTo(headX + headR * 1.15f, 0f)
                bodyPath.cubicTo(
                    headX + headR * 0.4f, -headR * 1.05f,
                    headX - headR * 0.9f, -headR * 0.85f,
                    headX - headR, 0f
                )
                bodyPath.cubicTo(
                    headX - headR * 0.9f, headR * 0.85f,
                    headX + headR * 0.4f, headR * 1.05f,
                    headX + headR * 1.15f, 0f
                )
            }
            HeadStyle.HEAD_BROAD -> {
                // 蛾/蝶/蟋蟀：宽圆的头，覆有绒毛
                bodyPath.moveTo(headX + headR * 0.5f, 0f)
                bodyPath.cubicTo(
                    headX + headR * 0.5f, -headR * 1.05f,
                    headX - headR * 0.9f, -headR * 1.1f,
                    headX - headR * 0.95f, 0f
                )
                bodyPath.cubicTo(
                    headX - headR * 0.9f, headR * 1.1f,
                    headX + headR * 0.5f, headR * 1.05f,
                    headX + headR * 0.5f, 0f
                )
            }
            HeadStyle.HEAD_FREE -> {
                // 蚂蚁/蟑螂：明显分离的一节，可自由转动，与胸之间有细颈
                bodyPath.moveTo(headX + headR * 0.8f, 0f)
                bodyPath.cubicTo(
                    headX + headR * 0.7f, -headR * 1.0f,
                    headX - headR * 0.7f, -headR * 1.0f,
                    headX - headR * 0.8f, 0f
                )
                bodyPath.cubicTo(
                    headX - headR * 0.7f, headR * 1.0f,
                    headX + headR * 0.7f, headR * 1.0f,
                    headX + headR * 0.8f, 0f
                )
            }
            HeadStyle.HEAD_FLAT -> {
                // 臭虫/虱/衣鱼：头短而宽扁，直接贴在前胸上，没有脖子。
                // 背面看几乎被前胸背板盖住，所以画得很扁（宽 > 高）。
                val hw = headR * 1.25f
                bodyPath.moveTo(headX + hw, 0f)
                bodyPath.cubicTo(
                    headX + hw * 0.85f, -headR * 0.85f,
                    headX - hw * 0.85f, -headR * 0.85f,
                    headX - hw, 0f
                )
                bodyPath.cubicTo(
                    headX - hw * 0.85f, headR * 0.85f,
                    headX + hw * 0.85f, headR * 0.85f,
                    headX + hw, 0f
                )
            }
        }
        bodyPath.close()
        bodyPaint.shader = headShader
        canvas.drawPath(bodyPath, bodyPaint)

        // 颈部：头和胸之间的连接，HEAD_FREE 的脖子最明显
        if (species.headStyle == HeadStyle.HEAD_FREE) {
            veinPaint.color = Color.argb(190, 12, 10, 9)
            veinPaint.strokeWidth = 3.0f
            canvas.drawLine(thoraxFront, 0f, headX - headR * 0.7f, 0f, veinPaint)
        }

        return Pair(headX, headR)
    }

    /** 判断是不是"金属光泽"类（绿头蝇、蟑螂）：甲壳有明显镜面反射。 */
    private fun isMetallic(): Boolean =
        species.bodyCore == 0xFF1B3028.toInt() ||   // 绿豆蝇
        species.bodyCore == 0xFF3A2016.toInt() ||   // 蟑螂
        species.bodyCore == 0xFF1F4A46.toInt()      // 蜻蜓

    /**
     * 虹彩反光带：一条沿身体纵轴的窄亮带 + 一条更窄的副反射。
     * 这是金属蝇和蟑螂最有辨识度的特征——真实甲壳会随角度变色，
     * 这里用两条不同色温的亮带来近似。
     */
    private fun drawIridescence(canvas: Canvas, x1: Float, x2: Float, squash: Float) {
        shinePaint.shader = LinearGradient(
            x2, -8f * squash, x1, 8f * squash,
            intArrayOf(
                Color.argb(0, 255, 255, 255),
                Color.argb(150, 255, 255, 255),
                Color.argb(60, 220, 240, 255),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.34f, 0.62f, 1f), Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.rotate(-6f)
        canvas.drawOval(x1 * 0.85f, -3.6f * squash, x2 * 0.9f, -0.8f * squash, shinePaint)
        canvas.restore()

        // 副反射：偏冷色，让甲壳有"两层光"的立体感
        shinePaint.shader = LinearGradient(
            x2, 6f * squash, x1, -6f * squash,
            intArrayOf(
                Color.argb(0, 255, 255, 255),
                Color.argb(90, 200, 235, 255),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.rotate(-6f)
        canvas.drawOval(x1 * 0.7f, 2.0f * squash, x2 * 0.7f, 4.4f * squash, shinePaint)
        canvas.restore()
    }

    /**
     * 蜜蜂：靠"深色腹 + 眼小（不占满头）"来判定。
     * 不能再用 bodyCore 判定了 —— 真实的蜜蜂腹部底色是深褐（#3A2A1C），
     * 不再是黄色，原来那个判据会失效。
     */
    private fun isBeeLike(): Boolean =
        species.bodyCore == 0xFF3A2A1C.toInt() && species.antennaeStyle == 4

    /**
     * 蜜蜂腹部的黄带。
     *
     * 真实布局（来源见 README）：**深色底**，黄带位于**腹节前缘**
     * （terga 2~4，工蜂共 3 条）。所以是"几条黄带压在深色腹部上"，
     * 每条第 2~4 节隔开的深色部分自然形成深色横条 —— 而不是我原来那样
     * 在黄底上画三条黑纹（方向完全反了）。
     */
    private fun drawBeeStripes(canvas: Canvas, squash: Float) {
        val bandColor = 0xFFE8A93C.toInt()
        // 腹节 2/3/4 的前缘，从腹前端往后的三个位置
        val segX = floatArrayOf(-1.5f, -8.5f, -15.5f)
        val segHalf = floatArrayOf(9.6f, 8.6f, 7.2f)

        for (i in segX.indices) {
            val bx = segX[i]
            val hw = segHalf[i] * squash
            // 黄带：略呈弧形（腹节前缘本来是弧的），带一点宽度
            detailPath.reset()
            detailPath.moveTo(bx + 1.7f, -hw * 0.97f)
            detailPath.quadTo(bx + 3.0f, 0f, bx + 1.7f, hw * 0.97f)
            detailPath.lineTo(bx - 1.7f, hw * 0.97f)
            detailPath.quadTo(bx - 0.4f, 0f, bx - 1.7f, -hw * 0.97f)
            detailPath.close()
            markPaint.color = bandColor
            canvas.drawPath(detailPath, markPaint)
        }
    }

    /**
     * 蜜蜂胸部的绒毛。
     *
     * 真实的蜜蜂胸部是一团**金褐色绒毛**，明显比腹部浅、更蓬松 ——
     * 这是它在背面最好认的特征，之前完全没画。
     */
    private fun drawBeeThoraxFuzz(canvas: Canvas) {
        val tx = thoraxFront - 7f      // 胸部中心
        val fuzz = 0xFFC79A55.toInt()

        // 绒毛团：一层柔和的径向色块
        shinePaint.shader = RadialGradient(
            tx, 0f, 15f,
            intArrayOf(
                Color.argb(225, 224, 190, 124),
                Color.argb(190, Color.red(fuzz), Color.green(fuzz), Color.blue(fuzz)),
                Color.argb(0, Color.red(fuzz), Color.green(fuzz), Color.blue(fuzz))
            ),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.rotate(-4f)
        canvas.drawOval(tx - 13f, -12.4f, tx + 12f, 12.4f, shinePaint)
        canvas.restore()

        // 绒毛的毛尖：一圈放射状短毛，让边缘"毛"起来
        veinPaint.color = Color.argb(185, 232, 200, 140)
        veinPaint.strokeWidth = 0.85f
        for (i in 0 until 22) {
            val a = (i / 22f) * 6.2831855f
            val ca = kotlin.math.cos(a)
            val sa = kotlin.math.sin(a)
            val sway = sin(motion.wingPhase * 0.03f + i) * 0.8f
            canvas.drawLine(
                tx + ca * 10.4f, sa * 10.0f,
                tx + ca * 16.2f + sway, sa * 15.4f + sway,
                veinPaint
            )
        }
    }

    /** 复眼：深色底 + 小眼面点阵 + 高光，比两个圆点真实得多。 */
    private fun drawCompoundEyes(canvas: Canvas, headX: Float, headR: Float) {
        val eyeX = headX + headR * 0.66f
        val eyeR = headR * 0.42f
        val gap = headR * 0.44f

        for (side in intArrayOf(-1, 1)) {
            val ex = eyeX
            val ey = side * gap
            eyePaint.color = species.eyeMain
            canvas.drawCircle(ex, ey, eyeR, eyePaint)

            // 小眼面：几排细线，模拟复眼的颗粒感
            veinPaint.color = Color.argb(70, 0, 0, 0)
            veinPaint.strokeWidth = 0.28f
            for (i in 0 until 3) {
                val yy = ey + (i - 1) * eyeR * 0.55f
                canvas.drawLine(ex - eyeR * 0.85f, yy, ex + eyeR * 0.85f, yy, veinPaint)
            }
            veinPaint.color = Color.argb(55, 255, 255, 255)
            canvas.drawLine(ex, ey - eyeR * 0.8f, ex, ey + eyeR * 0.8f, veinPaint)

            // 高光点
            eyePaint.color = species.eyeHighlight
            canvas.drawCircle(ex - eyeR * 0.3f, ey - eyeR * 0.34f, eyeR * 0.36f, eyePaint)
        }
    }

    /** 蛾/蝶/蜂的绒毛。小体型品种跳过，绒毛在几十像素上看不出来。 */
    private fun drawFuzz(canvas: Canvas) {
        if (species.detailLevel >= 1) {
            veinPaint.color = Color.argb(112, 235, 222, 195)
            veinPaint.strokeWidth = 0.85f
            for (i in 0 until 6) {
                val bx = 8f - i * 5f
                val sway = sin(motion.wingPhase * 0.04f + i) * 1.1f
                canvas.drawLine(bx, -8.2f, bx + sway, -13.4f, veinPaint)
                canvas.drawLine(bx, 8.2f, bx + sway, 13.4f, veinPaint)
            }
        }
    }

    // ================================================================ 触角

    /**
     * 触角。按品种分四种：
     *  1 细长（蚊/蟑螂/蚁/蜻蜓）
     *  2 羽状（蛾/蝶）
     *  3 短而带芒（蝇类的 aristate）
     * 蚁类额外画一个"肘状"折角，这是蚂蚁最好认的特征。
     */
    private fun drawAntennaeAt(canvas: Canvas, flap: Float, headX: Float, headR: Float) {
        val style = species.antennaeStyle
        if (style <= 0) return

        val sway = sin(motion.wingPhase * 0.05f) * 1.4f
        val isAnt = species.headStyle == HeadStyle.HEAD_FREE && species.sizeScale < 0.9f

        // 触角根长在头的前缘，按头型略有偏移
        val rootX = when (species.headStyle) {
            HeadStyle.HEAD_FLY -> headX + headR * 0.55f
            HeadStyle.HEAD_DOME -> headX + headR * 0.30f
            HeadStyle.HEAD_POINTY -> headX + headR * 0.90f
            HeadStyle.HEAD_BROAD -> headX + headR * 0.30f
            HeadStyle.HEAD_FREE -> headX + headR * 0.60f
            // 扁头类触角长在头的前侧角上，向前外侧伸出
            HeadStyle.HEAD_FLAT -> headX + headR * 0.85f
        }
        val rootY = headR * 0.26f

        when (style) {
            1 -> {
                veinPaint.color = Color.argb(215, 40, 34, 26)
                veinPaint.strokeWidth = if (isAnt) 0.85f else 0.7f
                for (side in intArrayOf(-1, 1)) {
                    legPath.reset()
                    legPath.moveTo(rootX, side * rootY)
                    if (isAnt) {
                        // 蚂蚁的肘状触角：先斜向前，再折向侧前方，折角很明显
                        legPath.lineTo(rootX + 5.5f, side * (rootY + 3.4f))
                        legPath.lineTo(rootX + 11.5f, side * (rootY + 1.2f))
                    } else {
                        legPath.cubicTo(
                            rootX + 6f, side * (rootY + 1.6f + 1.2f * flap),
                            rootX + 12f + sway, side * (rootY + 4.6f + 1.8f * flap),
                            rootX + 18f + sway, side * (rootY + 7.2f + 2.2f * flap)
                        )
                    }
                    canvas.drawPath(legPath, veinPaint)
                }
            }

            2 -> {
                // 羽状：一根主轴 + 两侧细齿（蛾/蝶）
                veinPaint.color = Color.argb(210, 66, 56, 42)
                veinPaint.strokeWidth = 1.5f
                val tipX = rootX + 15f + sway
                val tipY = rootY + 8.4f
                for (side in intArrayOf(-1, 1)) {
                    legPath.reset()
                    legPath.moveTo(rootX, side * rootY)
                    legPath.cubicTo(
                        rootX + 6f, side * (rootY + 3.4f),
                        rootX + 11f + sway, side * (rootY + 5.8f),
                        tipX, side * tipY
                    )
                    canvas.drawPath(legPath, veinPaint)

                    // 细齿
                    veinPaint.strokeWidth = 0.5f
                    for (i in 0 until 5) {
                        val t = 0.25f + i * 0.16f
                        val ax = rootX + (tipX - rootX) * t
                        val ay = side * (rootY + (tipY - rootY) * t)
                        canvas.drawLine(ax, ay, ax + 1.6f, ay + side * 2.4f, veinPaint)
                    }
                    veinPaint.strokeWidth = 1.5f
                }
            }

            3 -> {
                // 蝇类：很短，末端有一根芒（aristate）
                veinPaint.color = Color.argb(200, 46, 42, 38)
                veinPaint.strokeWidth = 1.1f
                for (side in intArrayOf(-1, 1)) {
                    legPath.reset()
                    legPath.moveTo(rootX, side * rootY)
                    legPath.lineTo(rootX + 5.0f, side * (rootY + 2.4f))
                    canvas.drawPath(legPath, veinPaint)
                    veinPaint.strokeWidth = 0.5f
                    canvas.drawLine(
                        rootX + 5.0f, side * (rootY + 2.4f),
                        rootX + 9f + sway, side * (rootY + 3.8f), veinPaint
                    )
                    veinPaint.strokeWidth = 1.1f
                }
            }
        }
    }

    // ================================================================ 腿

    /**
     * 昆虫的腿由三节组成，这里按真实解剖结构画：
     *
     *   股节 (femur)  最粗的一节，从胸部斜向外上方伸出
     *   胫节 (tibia)  细一些，从膝关节折向外下方
     *   跗节 (tarsus) 最末端贴地的一小段，分成几小节
     *
     * 关键点是**股节明显比胫节粗**（[FlySpecies.femurWidth] 控制倍数），
     * 蟋蟀的跳跃股节尤其粗大。以前用等宽直线画，所以腿看起来像铁丝。
     */
    private fun drawLegs(canvas: Canvas, farSide: Boolean) {
        val crawlBoost = 1f - motion.wingSpread
        val phase = motion.wingPhase * 0.05f
        val ySign = if (farSide) 1f else -1f

        val lw = species.legWidth
        val sideMul = if (farSide) 0.78f else 1f
        // 远侧腿画得更暗更细，产生景深
        val baseColor = if (farSide) Color.argb(160, 44, 39, 34) else Color.argb(234, 26, 22, 19)

        val legLen = when (species.legStyle) {
            3 -> 1.55f   // 极细长：蚊、蚁、蜻蜓
            2 -> 0.92f   // 粗短：甲虫、蟑螂、蟋蟀
            else -> 1.1f
        }

        // 蛹类腿根位置：前足在胸前，中足在胸中，后足在胸后
        val roots = floatArrayOf(9.5f, -1.5f, -12f)

        for (i in 0 until 3) {
            val isHind = i == 2
            val big = isHind && species.bigHindLegs
            val thickMul = if (big) 1.6f else 1f
            val lenMul = if (big) 1.45f else 1f

            val rx = roots[i]
            val swing = sin(phase + i * 2.1f) * (0.9f + 2.4f * crawlBoost)

            // 股节末端 = 膝关节，向外上方抬
            val jx = rx + (2.8f + swing * 0.35f) * lenMul
            val jy = ySign * (6.6f + i * 0.6f) * legLen * lenMul
            // 胫节末端
            val kx = rx + (5.6f + swing) * lenMul
            val ky = ySign * (12.8f + i * 1.0f) * legLen * lenMul
            // 跗节：贴地
            val fx = kx + 2.8f * lenMul
            val fy = ky + ySign * 1.9f * lenMul

            val color = if (i == 2 && big) {
                // 蟋蟀的粗后腿颜色更深、更亮
                if (farSide) Color.argb(170, 52, 44, 28) else Color.argb(240, 38, 32, 20)
            } else baseColor

            drawLegSegment(
                canvas,
                rx, ySign * 2.2f, jx, jy,
                lw * sideMul * thickMul * species.femurWidth,
                color
            )
            drawLegSegment(
                canvas,
                jx, jy, kx, ky,
                lw * sideMul * thickMul * 0.62f,
                color
            )
            drawLegSegment(
                canvas,
                kx, ky, fx, fy,
                lw * sideMul * 0.40f,
                color
            )

            // 跗节末端的小爪（detail 2 才画，太小看不清）
            if (species.detailLevel >= 2) {
                veinPaint.color = color
                veinPaint.strokeWidth = lw * sideMul * 0.32f
                canvas.drawLine(fx, fy, fx + 1.5f, fy + ySign * 0.9f, veinPaint)
            }

            // 膝关节的小圆点，让关节看起来有结构
            if (species.detailLevel >= 1) {
                markPaint.color = color
                canvas.drawCircle(jx, jy, lw * sideMul * thickMul * 0.62f * 0.9f, markPaint)
            }
        }
    }

    /**
     * 画一节腿：用「中点加宽」的填充形状而不是等宽描边，
     * 这样一节腿是两端细、中间粗的纺锤形，接近真实几丁质肢体。
     */
    private fun drawLegSegment(
        canvas: Canvas,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        width: Float,
        color: Int,
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = kotlin.math.hypot(dx, dy)
        if (len < 0.01f) return
        // 垂直方向的单位向量
        val nx = -dy / len
        val ny = dx / len
        val halfStart = width * 0.42f   // 起点细
        val halfMid = width * 0.58f     // 中段最粗
        val halfEnd = width * 0.40f     // 终点细

        val mx = (x1 + x2) * 0.5f
        val my = (y1 + y2) * 0.5f

        detailPath.reset()
        detailPath.moveTo(x1 + nx * halfStart, y1 + ny * halfStart)
        detailPath.quadTo(mx + nx * halfMid, my + ny * halfMid, x2 + nx * halfEnd, y2 + ny * halfEnd)
        detailPath.lineTo(x2 - nx * halfEnd, y2 - ny * halfEnd)
        detailPath.quadTo(mx - nx * halfMid, my - ny * halfMid, x1 - nx * halfStart, y1 - ny * halfStart)
        detailPath.close()

        bodyPaint.shader = null
        bodyPaint.color = color
        canvas.drawPath(detailPath, bodyPaint)
    }
}
