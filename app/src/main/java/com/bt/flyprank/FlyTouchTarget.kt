package com.bt.flyprank

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * 苍蝇的"触摸替身"。
 *
 * 为什么需要它：
 *   悬浮窗要在别的 App 上面画东西，就必须声明 FLAG_NOT_TOUCHABLE，否则会
 *   吃掉下面 App 的点击。但一旦声明了 NOT_TOUCHABLE，这个窗口就再也收不到
 *   任何触摸事件，也就没法知道"用户戳到苍蝇了没有"。
 *
 * 解法：
 *   用第二个**极小**的窗口当替身，尺寸只有苍蝇那么大，紧紧跟着苍蝇移动。
 *   用户戳到苍蝇时，这一次触摸被替身吃掉，我们据此让苍蝇弹开；
 *   戳在别处时，替身根本不在那个位置，触摸照常落到下面的 App 上。
 *   也就是说：只有"苍蝇身上那么一小块"会短暂拦截触摸——这正是我们想要的效果，
 *   毕竟真苍蝇被你戳到也会跑。
 */
@SuppressLint("ViewConstructor")
class FlyTouchTarget(context: Context) : View(context) {

    /** 触摸落下时回调屏幕坐标，由服务判断点中了哪只苍蝇。 */
    var onTouchAt: ((Float, Float, Boolean) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 用 raw 坐标统一到屏幕坐标系，跟悬浮窗位置计算保持一致
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downTime = SystemClock.uptimeMillis()
                moved = false
                // 按下就立刻反应，手感更灵敏
                onTouchAt?.invoke(downX, downY, true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val d = kotlin.math.hypot(event.rawX - downX, event.rawY - downY)
                if (d > 24f) {
                    moved = true
                    // 手指蹭过去也算"扫到苍蝇"
                    onTouchAt?.invoke(event.rawX, event.rawY, false)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val dt = SystemClock.uptimeMillis() - downTime
                if (!moved && dt < 400L) onTouchAt?.invoke(event.rawX, event.rawY, true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
