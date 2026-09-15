package com.bt.flyprank

import com.bt.flyprank.FlySpecies.Locomotion

/**
 * 虫子的运动与姿态。
 *
 * 设计目标：让它看起来"不像是程序在动"，而像真的虫子。
 * 做法是三层叠加：
 *   1. 巡航漂移   —— 缓慢、随机、略带起伏的基准泳道
 *   2. 突然窜逃   —— 高频出现的急加速 + 短暂滑翔，落点随机
 *   3. 停落整翅   —— 偶尔降速后停留，翅膀小幅抖动，腿在"爬"
 *
 * 不同品种的运动方式（Locomotion）会走不同的分支：
 *   FLY    蝇类：巡航 + 窜逃 + 偶尔落停，最"神经质"
 *   CRAWL  蟑螂/蚂蚁：绝大多数时间在地上爬，偶尔快速窜一下
 *   HOP    蟋蟀：爬行 + 突然的跳跃式冲刺（dartMul 很高）
 *   HOVER  蜻蜓/蝶/蜂：飞行为主，会在空中反复悬停，很少落地
 *
 * 坐标使用屏幕像素（悬浮层全屏坐标系），速度单位 px/s。
 */
class FlyMotion(
    private val random: java.util.Random,
    var x: Float,
    var y: Float,
    private val traits: FlyTraits = FlyTraits.DEFAULT,
) {
    companion object {
        /** 落地那一刻 */
        const val EV_LAND = 1
        /** 起飞那一刻 */
        const val EV_TAKE_OFF = 2
        /** 跳跃（蟋蟀） */
        const val EV_HOP = 3
        /** 被戳到而逃窜 */
        const val EV_FLEE = 4
    }

    /**
     * 状态变化时上报事件。
     *
     * 振动反馈就靠它：不能每帧都振，只能对"发生了什么"给一次反馈。
     * 由 FlyLayerView 聚合后再交给 FlyService 决定要不要真的振动
     * （因为 50 只虫子时只该为离用户最近的那只振）。
     */
    var eventListener: ((Int) -> Unit)? = null

    /** 上一帧是不是在地上（用来判断"刚落地"和"刚起飞"）。 */
    private var wasGrounded = false

    var vx: Float = 0f
        private set
    var vy: Float = 0f
        private set

    /** 朝向角度（度）。虫子侧着走，所以按速度方向旋转。 */
    var angle: Float = 0f
        private set

    /** 翅膀扇动相位，用于驱动翅膜形变。 */
    var wingPhase: Float = random.nextFloat() * 6.28f
        private set

    /** 翅膀张开的整体比例：降落时收拢，起飞时全开。 */
    var wingSpread: Float = 1f
        private set

    /** 爪子/脚是否处于着地姿态（爬行类用来决定要不要贴地）。 */
    var grounded: Boolean = false
        private set

    private var mode: Mode = Mode.CRUISE
    private var modeTimer: Float = 0f
    private var cruiseDir: Float = random.nextFloat() * 6.28f
    private var bodyBob: Float = 0f

    /** 起跳后短暂无敌/加速的时间，避免被连续点击瞬间固定 */
    private var vigor: Float = 1f

    private enum class Mode { CRUISE, DART, LAND, CRAWL }

    /**
     * 被手指戳到时调用：往相反方向猛地弹开，并伴随一次爆发。
     * 不会飞或者飞不动的品种（蟑螂、蚂蚁）也会向前窜一下，只是幅度小。
     */
    fun startled(fromX: Float, fromY: Float) {
        val dx = x - fromX
        val dy = y - fromY
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
        val power = 900f + random.nextFloat() * 600f
        vx = dx / len * power * traits.dartMul
        vy = dy / len * power * traits.dartMul - 180f
        mode = Mode.DART
        modeTimer = 0.35f + random.nextFloat() * 0.3f
        wingSpread = 1f
        vigor = 1.5f
        wasGrounded = false
        eventListener?.invoke(EV_FLEE)
    }

    /** 让它从屏幕外进来（启动入场，比直接出现更自然）。 */
    fun enterFromEdge(w: Float, h: Float) {
        val side = random.nextInt(4)
        when (side) {
            0 -> { x = -60f; y = random.nextFloat() * h }
            1 -> { x = w + 60f; y = random.nextFloat() * h }
            2 -> { x = random.nextFloat() * w; y = -60f }
            else -> { x = random.nextFloat() * w; y = h + 60f }
        }
        val tx = w * (0.2f + random.nextFloat() * 0.6f)
        val ty = h * (0.2f + random.nextFloat() * 0.6f)
        val dx = tx - x
        val dy = ty - y
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
        val speed = 1100f * traits.speedMul
        vx = dx / len * speed
        vy = dy / len * speed
        mode = Mode.DART
        modeTimer = len / speed
        wingSpread = 1f
    }

    /**
     * 步进一帧。
     *
     * @param dt      秒
     * @param w,h     屏幕（悬浮层）尺寸
     * @param sizePx  虫子本体基准尺寸，用于让速度与体型成比例
     */
    fun update(dt: Float, w: Float, h: Float, sizePx: Float) {
        modeTimer -= dt
        vigor = (vigor - dt * 0.6f).coerceAtLeast(1f)

        val onGround = mode == Mode.CRAWL
        val bobRate = if (onGround) traits.landRate else traits.flyBobRate
        bodyBob += dt * bobRate
        grounded = onGround

        when (mode) {
            Mode.CRUISE -> {
                cruiseDir += (random.nextFloat() - 0.5f) * 2.4f * dt * traits.turnRate * 0.5f
                val targetSpeed = sizePx * (5.5f + random.nextFloat() * 1.5f) * vigor * traits.speedMul
                val tx = kotlin.math.cos(cruiseDir) * targetSpeed
                val ty = kotlin.math.sin(cruiseDir) * targetSpeed
                approach(tx, ty, dt, 3.2f)

                if (modeTimer <= 0f) nextFromCruise(sizePx)
            }

            Mode.CRAWL -> {
                // 爬行：速度低、方向稳定，偶尔改一下方向
                val crawlSpeed = sizePx * (1.2f + random.nextFloat() * 0.6f) * traits.speedMul
                if (random.nextFloat() < dt * 2.5f) {
                    cruiseDir += (random.nextFloat() - 0.5f) * 1.2f
                }
                approach(
                    kotlin.math.cos(cruiseDir) * crawlSpeed,
                    kotlin.math.sin(cruiseDir) * crawlSpeed,
                    dt, 4.5f
                )
                if (modeTimer <= 0f) nextFromCruise(sizePx)
            }

            Mode.DART -> {
                // 窜逃期间几乎不修正方向，只在撞墙时反弹，模拟"直线冲刺"
                approach(vx, vy, dt, 0.6f)
                if (modeTimer <= 0f) {
                    mode = Mode.CRUISE
                    // 爱落停的品种巡航时间更长，看起来更"懒"
                    modeTimer = 0.5f + random.nextFloat() * 1.6f + traits.landBias * 1.5f
                    wingSpread = 1f
                }
            }

            Mode.LAND -> {
                approach(0f, 0f, dt, 7f)
                if (modeTimer <= 0f) {
                    // 悬停型在空中停一下就走，很少真的落地
                    if (traits.hoverTendency > 0.15f) {
                        startDart(sizePx)
                    } else {
                        // landBias 越大越倾向于继续趴着爬
                        val continueCrawl = random.nextFloat() < (0.4f + traits.landBias * 0.5f)
                        // 落地事件统一由下面的 wasGrounded 状态变化发出，这里不重复发
                        if (continueCrawl) startCrawl() else startDart(sizePx)
                    }
                }
            }
        }

        // 姿态与着地状态。用 wasGrounded 对比来判断"刚落地/刚起飞"，
        // 这样振动反馈就能精确落在动作发生的那一帧。
        val groundNow = mode == Mode.CRAWL
        if (groundNow && !wasGrounded) {
            eventListener?.invoke(EV_LAND)
        } else if (!groundNow && wasGrounded) {
            // 蟋蟀从爬行直接窜出去 = 跳跃，比普通起飞更有力度
            eventListener?.invoke(if (traits.locomotion == Locomotion.HOP) EV_HOP else EV_TAKE_OFF)
        }
        wasGrounded = groundNow

        // 翅膀：飞行时高频全开，停落时收拢
        val flying = mode == Mode.CRUISE || mode == Mode.DART
        // 会在地面活动的品种（蟑螂/蚂蚁/蟋蟀）爬行时把翅膀收起来；
        // 蝇类即使落停也保持一点张开（它们落停时翅膀是搭在背上的）
        val groundDweller = traits.locomotion == Locomotion.CRAWL || traits.locomotion == Locomotion.HOP
        val targetSpread = when {
            flying -> 1f
            groundDweller -> 0.10f
            else -> 0.32f
        }
        if (traits.flapHz > 0f) {
            // 悬停时也要扇翅，否则看起来像块石头
            val flapHz = when {
                flying -> traits.flapHz
                mode == Mode.LAND && traits.hoverTendency > 0.15f -> traits.flapHz * 0.9f
                else -> 7f
            }
            wingPhase += dt * flapHz * 6.2831855f
            if (wingPhase > 1e6f) wingPhase -= 1e6f
        }
        wingSpread += (targetSpread - wingSpread) * (dt * 9f).coerceAtMost(1f)

        // 位移积分
        x += vx * dt
        y += vy * dt

        // 边界：撞到屏幕边缘反弹
        val m = sizePx * 1.1f
        if (x < m) { x = m; vx = kotlin.math.abs(vx) * 0.8f; cruiseDir = 0f }
        if (x > w - m) { x = w - m; vx = -kotlin.math.abs(vx) * 0.8f; cruiseDir = 3.14159f }
        if (y < m) { y = m; vy = kotlin.math.abs(vy) * 0.8f; cruiseDir = 1.5708f }
        if (y > h - m) { y = h - m; vy = -kotlin.math.abs(vy) * 0.8f; cruiseDir = -1.5708f }

        // 姿态：朝速度方向，转向带平滑。turnRate 小的品种（蛾子）更迟钝更"飘"
        if (kotlin.math.abs(vx) + kotlin.math.abs(vy) > sizePx * 0.6f) {
            val target = kotlin.math.atan2(vy, vx) * 57.29578f
            angle = lerpAngle(angle, target, (dt * traits.turnRate).coerceAtMost(1f))
        }
    }

    /** 巡航/爬行状态到期后决定下一步做什么，按运动方式分支。 */
    private fun nextFromCruise(sizePx: Float) {
        val r = random.nextFloat()
        when (traits.locomotion) {
            Locomotion.FLY -> {
                val dartCut = 0.75f - traits.landBias * 0.6f
                when {
                    r < dartCut -> startDart(sizePx)
                    r < 0.92f -> startLand()
                    else -> {
                        cruiseDir = random.nextFloat() * 6.28f
                        modeTimer = 1.2f + random.nextFloat() * 2f
                    }
                }
            }

            Locomotion.CRAWL -> {
                // 绝大多数时间在地上跑，偶尔快速窜一段
                when {
                    r < 0.42f -> startCrawl()
                    r < 0.90f -> startDart(sizePx)
                    else -> {
                        cruiseDir = random.nextFloat() * 6.28f
                        modeTimer = 1.5f + random.nextFloat() * 2f
                    }
                }
            }

            Locomotion.HOP -> {
                // 蟋蟀：爬一会儿，然后猛地跳一下（dartMul 很高）
                when {
                    r < 0.30f -> startDart(sizePx)
                    r < 0.95f -> startCrawl()
                    else -> {
                        cruiseDir = random.nextFloat() * 6.28f
                        modeTimer = 1.0f + random.nextFloat() * 1.5f
                    }
                }
            }

            Locomotion.HOVER -> {
                // 蜻蜓/蝶：飞为主，会在空中反复停住悬停，很少落地
                when {
                    r < 0.30f -> startLand()
                    r < 0.78f -> {
                        cruiseDir = random.nextFloat() * 6.28f
                        modeTimer = 0.8f + random.nextFloat() * 1.5f
                    }
                    else -> startDart(sizePx)
                }
            }
        }
    }

    /** 身体上下轻微起伏的偏移量，绘制时叠加到 y 上。 */
    fun bobOffset(sizePx: Float): Float =
        kotlin.math.sin(bodyBob) * sizePx * (if (grounded) 0.018f else 0.05f)

    private fun approach(tx: Float, ty: Float, dt: Float, rate: Float) {
        val k = (dt * rate).coerceAtMost(1f)
        vx += (tx - vx) * k
        vy += (ty - vy) * k
    }

    private fun startCrawl() {
        mode = Mode.CRAWL
        // 爱爬的品种爬得久
        modeTimer = 0.8f + random.nextFloat() * 2.2f + traits.landBias * 2.5f
        cruiseDir = random.nextFloat() * 6.2831855f
    }

    private fun startDart(sizePx: Float) {
        mode = Mode.DART
        modeTimer = 0.25f + random.nextFloat() * 0.55f
        val a = random.nextFloat() * 6.2831855f
        // 按品种倍率区分：果蝇窜得又快又碎，蛾子只是慢悠悠飘一下，
        // 蟋蟀的 dartMul 很高（跳跃），蟑螂则是猛冲一段
        val speed = sizePx * (13f + random.nextFloat() * 9f) * vigor * traits.dartMul
        vx = kotlin.math.cos(a) * speed
        vy = kotlin.math.sin(a) * speed
        cruiseDir = a
        wingSpread = 1f
    }

    private fun startLand() {
        mode = Mode.LAND
        // 爱落停的品种趴得更久；悬停型只是空中停一下
        val base = if (traits.hoverTendency > 0.15f) 0.35f else 0.5f
        modeTimer = base + random.nextFloat() * 1.4f + traits.landBias * 1.2f
    }

    private fun lerpAngle(from: Float, to: Float, t: Float): Float {
        var d = to - from
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return from + d * t
    }
}
