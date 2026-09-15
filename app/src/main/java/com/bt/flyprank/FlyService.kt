package com.bt.flyprank

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

/**
 * 把苍蝇放到整个屏幕上。
 *
 * 两个悬浮窗配合工作：
 *
 *  1) FlyLayerView（全屏、不吃触摸）
 *     FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE，只负责画。因为不吃触摸，
 *     苍蝇飞过的地方下面的 App 依然能正常点。
 *
 *  2) FlyTouchTarget（苍蝇大小、跟屁虫）
 *     一个只有苍蝇那么大的可触摸窗口，始终罩在离手指最近的那只苍蝇身上。
 *     戳到苍蝇 -> 被它吃掉 -> 苍蝇弹走 + 震一下 + 嗡一声。
 *     没戳到苍蝇 -> 它不在那儿 -> 触摸照常传给下面的 App。
 *
 * 这样既有"戳不中"的手感，又不会把用户整块屏幕的触摸都吃掉。
 */
class FlyService : Service() {

    companion object {
        const val ACTION_START = "com.bt.flyprank.action.START"
        const val ACTION_STOP = "com.bt.flyprank.action.STOP"
        const val ACTION_TOGGLE = "com.bt.flyprank.action.TOGGLE"

        const val PREFS = "fly_prank_prefs"
        const val KEY_COUNT = "fly_count"
        const val KEY_SOUND = "sound_enabled"
        const val KEY_HAPTIC = "haptic_enabled"
        const val KEY_PAUSED = "paused"

        /**
         * 上次运行时的 versionCode。
         * 和当前版本的 versionCode 不一致 = 这次是新装或刚更新，
         * MainActivity 据此触发"自动放出最大数量"。
         */
        const val KEY_LAST_VERSION = "last_version_code"

        /**
         * 虫子体型，固定值。
         *
         * 之前是主界面上的滑块（0.5~3.0 倍），现在固定成 0.5 倍、滑块已移除。
         * 各品种之间的相对大小仍由 [FlySpecies.sizeScale] 决定
         * （果蝇 0.62、蟑螂 1.7、蛾子 2.05…），这里只是整体基准。
         *
         * 想再调大小就改这一个数（0.5 是实机上手感较合适的值）。
         */
        const val FIXED_SIZE_SCALE = 0.5f

        /**
         * 同时上屏的数量上限 = min(品种数 × 每種上限, FlySpecies.MAX_TOTAL)。
         * 和 MainActivity.MAX_FLIES、布局 valueTo 三者必须一致。
         */
        const val MAX_FLIES = 50

        /**
         * 触摸替身窗口的最大数量。
         *
         * 1 个替身只能让 1 只虫子可戳；50 只时其余 49 只怎么点都没反应，手感就死了。
         * 开 6 个替身分摊到不同虫子身上，屏幕各处都有能戳中的虫子。
         * 代价是这 6 块"虫子大小"的区域会拦截触摸，其余屏幕照常穿透。
         */
        const val TARGET_MAX = 6

        private const val CHANNEL_ID = "fly_prank_running"
        private const val NOTIF_ID = 0x1F17

        /** 苍蝇是否正在屏幕上活动。 */
        @Volatile
        var running: Boolean = false
            private set

        /** 是否处于暂停（苍蝇冻在原地）。 */
        @Volatile
        var paused: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, FlyService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, FlyService::class.java).setAction(ACTION_STOP))
        }

        fun canDrawOverlays(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private lateinit var wm: WindowManager
    private lateinit var layer: FlyLayerView

    /**
     * 触摸替身窗口池。
     *
     * 为什么是"池"而不是 1 个：
     *   虫子少的时候 1 个替身（罩住最近的那只）就够用了。
     *   但 50 只虫子遍布屏幕时，单个替身只有 1 只可戳，其余 49 只怎么点都点不到，
     *   手感就"死"了。所以开一小批替身，均匀分摊到不同的虫子身上，
     *   让屏幕各处都有能戳中的虫子。
     *
     * 代价是这一小批窗口会各自吃掉"虫子那么大"的一块触摸区域（最多 6 块），
     * 其余屏幕照常穿透。这是数量和手感之间的折中。
     */
    private val targets = ArrayList<FlyTouchTarget>()
    private val targetParams = ArrayList<WindowManager.LayoutParams>()
    private val targetAttached = ArrayList<Boolean>()
    private val lastTargetX = ArrayList<Float>()
    private val lastTargetY = ArrayList<Float>()
    private val targetFlyIndex = ArrayList<Int>()

    private val buzz = BuzzPlayer()
    private lateinit var haptic: HapticPlayer

    /**
     * 屏幕常亮 + CPU 唤醒锁。
     *
     * 为什么需要：虫子是画在悬浮层上的，一旦屏幕熄灭，Choreographer 和
     * 主循环都会停，虫子就全部静止了 —— 恶搞效果直接失效。
     * （调试时踩过：把手机放几分钟再回来看，一只虫子都不动了。）
     *
     * 用 SCREEN_DIM_WAKE_LOCK 而不是 FULL_WAKE_LOCK：允许屏幕变暗但仍显示，
     * 省电又不会让虫子停下。随服务一起释放，不会泄漏。
     */
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastFrame = 0L
    private var frameNo = 0L
    private var frameMs = 11L

    private val frameTick = object : Runnable {
        override fun run() {
            step()
            if (running) handler.postDelayed(this, frameMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        haptic = HapticPlayer(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE -> {
                if (running) {
                    // 暂停/继续：冻结在原地，而不是彻底收走
                    paused = !paused
                    prefs(this).edit().putBoolean(KEY_PAUSED, paused).apply()
                    if (paused) buzz.setActivity(0f)
                    updateNotification()
                    return START_STICKY
                }
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            // 没有悬浮窗权限就别硬撑，直接收工，避免前台服务空转
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) start()
        return START_STICKY
    }

    // ------------------------------------------------------------------ 启动

    private fun start() {
        startForeground(NOTIF_ID, buildNotification())

        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        val p = prefs(this)
        // 上限 50（11 个品种，每種最多 5 只）
        val count = p.getInt(KEY_COUNT, 1).coerceIn(1, MAX_FLIES)
        val sizePx = 44f * metrics.density * FIXED_SIZE_SCALE

        layer = FlyLayerView(this, w, h, count, sizePx)
        layer.onFlyTapped = { buzz.spike() }
        layer.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            wm.addView(layer, lp)
        } catch (e: Throwable) {
            stopSelf()
            return
        }

        // 自适应主循环周期：虫子越多，一帧的绘制量越大。
        // 与其每 8ms 触发一次、每次都在做同一帧做不完的活，不如把周期放长到能出完整帧，
        // 这样动画实际更流畅（少了无效重绘），也不影响触摸替身的跟随精度。
        frameMs = when {
            count <= 8 -> 8L
            count <= 16 -> 10L
            count <= 32 -> 13L
            else -> 16L
        }

        buildTargets(count, sizePx)

        buzz.enabled = p.getBoolean(KEY_SOUND, true)
        if (buzz.enabled) buzz.start(this)

        haptic.enabled = p.getBoolean(KEY_HAPTIC, true)

        paused = p.getBoolean(KEY_PAUSED, false)
        running = true
        lastFrame = SystemClock.uptimeMillis()

        acquireWakeLock()

        // 放出虫子时的一串开场振动，像虫群涌出来
        if (haptic.enabled) haptic.onLaunch()

        handler.post(frameTick)
    }

    /**
     * 拿唤醒锁，让虫子一直在动。
     * 失败也不影响其他功能（有些 ROM 会限制后台唤醒锁）。
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "FlyPrank:bugs"
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            wakeLock = lock
        } catch (e: Throwable) {
            wakeLock = null
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    /**
     * 建触摸替身窗口池。
     *
     * 数量 ≤ [TARGET_MAX] 时每只虫子一个替身（全都可戳）；
     * 更多时用 [TARGET_MAX] 个替身，按等间隔分摊到不同的虫子身上，
     * 保证屏幕各处都有能戳中的虫子，而不是只有某一只。
     */
    private fun buildTargets(count: Int, sizePx: Float) {
        val n = minOf(count, TARGET_MAX)
        // 替身比虫子略大一圈，手指不用点得那么准（手感更"欠揍"）
        val win = (sizePx * 2.6f).toInt().coerceAtLeast(dp(72))
        // 分摊间隔：50 只配 6 个替身 -> 每 ~8 只一个
        val step = if (n >= count) 1 else (count.toFloat() / n).toInt().coerceAtLeast(1)

        for (k in 0 until n) {
            val t = FlyTouchTarget(this)
            t.onTouchAt = { x, y, isDown -> onPoke(x, y, isDown) }
            val params = WindowManager.LayoutParams(
                win, win,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            targets.add(t)
            targetParams.add(params)
            targetAttached.add(false)
            lastTargetX.add(-99999f)
            lastTargetY.add(-99999f)
            targetFlyIndex.add((k * step) % count)
        }
    }

    // ------------------------------------------------------------ 每帧循环

    private fun step() {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        var dt = (now - lastFrame) / 1000f
        lastFrame = now
        // 卡顿时钳制步长，避免苍蝇瞬移出屏幕
        if (dt > 0.05f) dt = 0.05f

        // 暂停时只保持静止画面，嗡声和振动都停
        if (paused) {
            layer.invalidate()
            moveTarget()
            buzz.setActivity(0f)
            return
        }

        layer.updateAll(dt)
        layer.invalidate()
        moveTarget()

        // 把这一帧的运动事件翻译成振动（只对最值得反馈的那只虫子）
        dispatchHaptics()

        // 活跃度驱动嗡声音量：乱窜时响，停落时几乎没声
        buzz.setActivity((layer.activity * 0.85f).coerceIn(0f, 1f))
    }

    /**
     * 让每个触摸替身跟上它负责的那只虫子。
     *
     * 性能上有个坑要注意：updateViewLayout 是跨进程调用，每帧都调会很费。
     * 所以这里做两件事：
     *   1) 隔帧更新（替身位置不需要每帧都精确到像素）
     *   2) 位移不到 12px 就不调
     * 替身比虫子大一圈（2.6 倍），所以这点偏差不影响戳中判定。
     */
    private fun moveTarget() {
        val flies = layer.flies
        if (flies.isEmpty() || targets.isEmpty()) return

        // 隔帧更新，省一半 IPC
        if ((frameNo++ % 2L) != 0L) return

        for (t in targets.indices) {
            val view = targets[t]
            val tp = targetParams[t]
            var idx = targetFlyIndex[t]
            if (idx < 0 || idx >= flies.size) idx = 0
            val fly = flies[idx]

            val left = fly.motion.x - tp.width / 2f
            val top = fly.motion.y - tp.height / 2f

            val lx = lastTargetX[t]
            val ly = lastTargetY[t]
            if (kotlin.math.abs(left - lx) < 12f && kotlin.math.abs(top - ly) < 12f) continue
            lastTargetX[t] = left
            lastTargetY[t] = top

            tp.x = left.toInt()
            tp.y = top.toInt()

            if (!targetAttached[t]) {
                try {
                    wm.addView(view, tp)
                    targetAttached[t] = true
                } catch (e: Throwable) {
                    // 加不上就算了，虫子照样画，只是这一个戳不中
                }
            } else {
                try {
                    wm.updateViewLayout(view, tp)
                } catch (e: Throwable) {
                }
            }
        }
    }

    /**
     * 替身收到了触摸：说明手指正好落在苍蝇身上。
     * @param isDown 是"按下/抬起"这类明确点击，还是"滑过"
     */
    private fun onPoke(screenX: Float, screenY: Float, isDown: Boolean) {
        val idx = layer.findHit(screenX, screenY)
        if (idx < 0) {
            // 没打中（苍蝇刚好移开）——只当是扫过，轻轻吓它一下
            layer.scareNear(screenX, screenY, layer.flies[0].sizePx * 0.8f)
            return
        }
        layer.startleFly(idx, screenX, screenY)
        buzz.spike()
        // 受惊时的振动由 FlyMotion 的 EV_FLEE 事件统一发出（见 dispatchHaptics），
        // 这里不再直接振，避免同一次点击振两下
    }

    /**
     * 把这一帧里虫子发生的事件翻译成振动反馈。
     *
     * 这里有两个层次的抑制，不然 50 只虫子会让手机一直轻微振动：
     *
     *  1. 虫群的自然事件（落地/起飞）**只在靠近触摸替身时才振** ——
     *     也就是你手指附近那只。远处的虫子落地你本来就感知不到，
     *     给它振动反而是噪音（实测未加这个判断时每秒振 3~4 次）。
     *  2. "戳中"和"跳跃"是用户直接造成的，永远给反馈，并且优先于自然事件。
     *
     * 最后还有 HapticPlayer 内部的 90ms 限流兜底。
     */
    private fun dispatchHaptics() {
        val (ev, flyIdx) = layer.collectEvent()
        if (ev == 0 || flyIdx < 0 || flyIdx >= layer.flies.size) return

        val fly = layer.flies[flyIdx]

        // 与最近触摸替身的距离（替身就是用户能戳到的地方）
        var best = Float.MAX_VALUE
        for (t in targets.indices) {
            if (!targetAttached.getOrElse(t) { false }) continue
            val tx = lastTargetX[t] + targetParams[t].width / 2f
            val ty = lastTargetY[t] + targetParams[t].height / 2f
            val d = kotlin.math.hypot(fly.motion.x - tx, fly.motion.y - ty)
            if (d < best) best = d
        }
        val nearTouch = best < fly.sizePx * 3.2f

        // 用户直接造成的事件永远反馈；虫群自然事件只在"近处"才反馈
        val interactive = ev == FlyMotion.EV_FLEE || ev == FlyMotion.EV_HOP
        if (!interactive && !nearTouch) return

        if (!haptic.enabled) return

        // 体型也会影响振感：把相对 44dp 基准的倍率算出来
        val sizeScale = (fly.sizePx / (44f * resources.displayMetrics.density))
            .coerceIn(0.4f, 2.2f)

        when (ev) {
            FlyMotion.EV_FLEE -> haptic.onFlee(fly.species, sizeScale)
            FlyMotion.EV_LAND -> haptic.onLand(fly.species, sizeScale)
            FlyMotion.EV_TAKE_OFF -> haptic.onTakeOff(fly.species, sizeScale)
            FlyMotion.EV_HOP -> haptic.onHop(fly.species, sizeScale)
        }
    }

    // ------------------------------------------------------------------ 收尾

    private fun shutdown() {
        running = false
        handler.removeCallbacks(frameTick)
        buzz.stop()
        // 收网时一下明确的"结束"反馈
        if (haptic.enabled) haptic.onStop()
        releaseWakeLock()

        removeAllTargets()
        if (::layer.isInitialized) {
            try {
                wm.removeView(layer)
            } catch (_: Throwable) {
            }
        }
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(frameTick)
        buzz.stop()
        releaseWakeLock()
        removeAllTargets()
        if (::layer.isInitialized) {
            try {
                wm.removeView(layer)
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }

    /** 撤掉全部触摸替身窗口。 */
    private fun removeAllTargets() {
        for (t in targets.indices) {
            if (targetAttached.getOrElse(t) { false }) {
                try {
                    wm.removeView(targets[t])
                } catch (_: Throwable) {
                }
                targetAttached[t] = false
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // -------------------------------------------------------------- 通知相关

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FlyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, FlyService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fly)
            .setContentTitle(getString(if (paused) R.string.notif_title_paused else R.string.notif_title))
            .setContentText(getString(if (paused) R.string.notif_text_paused else R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)

        val toggleLabel = getString(
            if (paused) R.string.notif_action_resume else R.string.notif_action_pause
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.addAction(Notification.Action.Builder(null, toggleLabel, toggleIntent).build())
            b.addAction(
                Notification.Action.Builder(null, getString(R.string.notif_action_stop), stopIntent).build()
            )
        } else {
            @Suppress("DEPRECATION")
            b.addAction(0, toggleLabel, toggleIntent)
            @Suppress("DEPRECATION")
            b.addAction(0, getString(R.string.notif_action_stop), stopIntent)
        }

        return b.build()
    }

    /** 暂停状态变化后刷新通知文案。 */
    private fun updateNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
