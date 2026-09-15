package com.bt.flyprank

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * 主界面里的"实际效果预览"。
 *
 * 它复用 [FlyActor] 的绘制代码，也就是说这里看到的就是真放出去的样子
 * （同一套品种参数、同一套绘制逻辑），不是另画一张示意图。
 *
 * 动画用 Choreographer 驱动：只在界面可见时跑，
 * onVisibilityChanged 里挂/摘回调，离开界面就完全停掉，不浪费电。
 */
class InsectPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** 要预览的品种清单（含重复，顺序即绘制顺序）。 */
    private var roster: List<FlySpecies> = emptyList()

    private var actors: MutableList<FlyActor> = ArrayList()

    private var lastFrame = 0L
    private var started = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    init {
        bgPaint.color = ContextCompat_getColor(context, R.color.preview_bg)
        bgBorderPaint.color = ContextCompat_getColor(context, R.color.divider)
    }

    /** 兼容写法：Api 23+ 才能用 resources.getColor(id, theme)。 */
    private fun ContextCompat_getColor(context: Context, resId: Int): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            context.resources.getColor(resId, context.theme)
        } else {
            @Suppress("DEPRECATION")
            context.resources.getColor(resId)
        }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!started) return
            step()
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * 设置预览的品种。
     * @param roster 品种清单，长度建议 ≤ 12（预览区小，太多会糊）
     *
     * 体型用和正式放出时**同一个常量**（[FlyService.FIXED_SIZE_SCALE]），
     * 这样预览里的相对大小和真放出去完全一致 —— 预览才真的"所见即所得"。
     */
    fun setRoster(roster: List<FlySpecies>) {
        this.roster = roster
        buildActors()
        invalidate()
    }

    private fun buildActors() {
        actors = ArrayList()
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        if (roster.isEmpty() || w <= 1f || h <= 1f) return

        val base = SystemClock.uptimeMillis()
        val baseSize = 44f * resources.displayMetrics.density * FlyService.FIXED_SIZE_SCALE

        roster.forEachIndexed { i, sp ->
            val a = FlyActor(context, base + i * 4051L, w, h, sp)
            a.setSize(baseSize)
            // 直接把虫子撒在可视区域内，不要入场动画（预览要立刻看得见）
            a.motion.x = w * (0.14f + 0.72f * ((i * 0.37f) % 1f))
            a.motion.y = h * (0.18f + 0.64f * ((i * 0.61f + 0.2f) % 1f))
            actors.add(a)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildActors()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (started) return
        started = true
        lastFrame = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stop() {
        started = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        var dt = (now - lastFrame) / 1000f
        lastFrame = now
        if (dt > 0.05f) dt = 0.05f
        for (a in actors) a.update(dt)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 预览底板：一条淡淡的边界，暗示"这就是屏幕上会发生的事"
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        bgBorderPaint.strokeWidth = resources.displayMetrics.density
        canvas.drawRect(0f, 0f, w, h, bgBorderPaint)

        for (a in actors) {
            a.draw(canvas)
        }
    }
}
