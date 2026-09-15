package com.bt.flyprank

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/**
 * 苍蝇的绘制层。
 *
 * 这一层挂在悬浮窗上，本身**不吃任何触摸**（服务里加了 FLAG_NOT_TOUCHABLE），
 * 所以苍蝇飞过的地方，下面的 App 照常可以点。感知"戳中苍蝇"由另一个
 * 极小的触摸目标窗口（FlyTouchTarget）负责，见 FlyService 的说明。
 */
class FlyLayerView(
    context: Context,
    private val w: Float,
    private val h: Float,
    count: Int,
    sizePx: Float,
) : View(context) {

    val flies: MutableList<FlyActor> = ArrayList()

    /** 戳中某只苍蝇时回调（参数是它在列表里的下标）。 */
    var onFlyTapped: ((Int) -> Unit)? = null

    /** 首次启动时画一段说明，避免用户不知道怎么关掉。 */
    private var hintUntil: Long = SystemClock.uptimeMillis() + 5200L
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 20, 20, 20)
        textSize = 13f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val hintBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255)
    }

    /** 当前所有苍蝇的平均活跃度，用来驱动嗡声音量。 */
    var activity: Float = 0f
        private set

    init {
        setBackgroundColor(Color.TRANSPARENT)
        val base = SystemClock.uptimeMillis()
        // 每个品种最多 5 只，分配逻辑与主界面预览共用 FlySpecies.allocate
        val roster = FlySpecies.allocate(count)
        for (i in roster.indices) {
            val fly = FlyActor(context, base + i * 7919L, w, h, roster[i])
            fly.setSize(sizePx)
            flies.add(fly)
        }
    }

    /**
     * 这一帧里"最值得给振动反馈"的事件，以及是哪只虫子发出的。
     *
     * 为什么只留一个：50 只虫子可能同一帧里好几只都落地了，
     * 如果每个事件都振一下，手机就变成按摩器。
     * 所以只取一个，而且是**离某个触摸替身最近的那只** ——
     * 也就是用户此刻手指附近、真正能感知到的那只。
     */
    var pendingEvent: Int = 0
        private set
    var pendingEventFly: Int = -1
        private set

    fun setSize(px: Float) {
        flies.forEach { it.setSize(px) }
    }

    fun updateAll(dt: Float) {
        var sum = 0f
        // 每帧重置事件，重新收集
        pendingEvent = 0
        pendingEventFly = -1

        for (i in flies.indices) {
            val f = flies[i]
            val beforeX = f.motion.x
            val beforeY = f.motion.y
            f.update(dt)
            val dx = f.motion.x - beforeX
            val dy = f.motion.y - beforeY
            // 用实际位移衡量活跃度
            sum += kotlin.math.hypot(dx, dy) / dt.coerceAtLeast(0.001f) / f.sizePx
        }
        activity = if (flies.isEmpty()) 0f else (sum / flies.size) / 14f
    }

    /**
     * 从各只虫子收集这一帧发生的事件，只保留最值得反馈的一个。
     *
     * 优先级：跳跃/受惊（用户直接造成的）> 落地/起飞。
     * 同类事件里选**先遇到的那只**即可 —— 具体"要不要真的振"
     * 由 FlyService 再按"离触摸替身多远"过滤。
     */
    fun collectEvent(): Pair<Int, Int> {
        var bestEvent = 0
        var bestFly = -1
        var bestRank = -1

        for (i in flies.indices) {
            val ev = flies[i].consumeEvent()
            if (ev == 0) continue
            // 数值越大优先级越高
            val rank = when (ev) {
                FlyMotion.EV_FLEE -> 3
                FlyMotion.EV_HOP -> 2
                else -> 1
            }
            if (rank > bestRank) {
                bestRank = rank
                bestEvent = ev
                bestFly = i
            }
        }
        pendingEvent = bestEvent
        pendingEventFly = bestFly
        return Pair(bestEvent, bestFly)
    }

    /** 找出被点中的苍蝇（判定半径放宽一点，手感更"欠揍"）。 */
    fun findHit(x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in flies.indices) {
            val f = flies[i]
            if (f.hitTest(x, y)) {
                val d = kotlin.math.hypot(f.motion.x - x, f.motion.y - y)
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
        }
        return best
    }

    fun startleFly(index: Int, fromX: Float, fromY: Float) {
        if (index in flies.indices) flies[index].startled(fromX, fromY)
    }

    /** 手指靠近就提前躲，模拟真实的躲避反应。 */
    fun scareNear(x: Float, y: Float, radius: Float) {
        for (i in flies.indices) {
            val f = flies[i]
            if (f.nearTest(x, y, radius)) {
                f.startled(x, y)
                onFlyTapped?.invoke(i)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (f in flies) f.draw(canvas)

        val now = SystemClock.uptimeMillis()
        if (now < hintUntil) {
            val d = resources.displayMetrics.density
            val y = h * 0.16f
            canvas.drawRoundRect(
                w * 0.5f - 165f * d / 1.0f, y - 26f * d,
                w * 0.5f + 165f * d / 1.0f, y + 30f * d,
                14f * d, 14f * d, hintBgPaint
            )
            canvas.drawText("苍蝇已放出 · 点它试试", w * 0.5f, y, hintPaint)
            canvas.drawText("退出：下拉通知栏点「收网」", w * 0.5f, y + 18f * d, hintPaint)
        }
    }
}
