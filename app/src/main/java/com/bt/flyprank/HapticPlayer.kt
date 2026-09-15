package com.bt.flyprank

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log

/**
 * 振动反馈。
 *
 * 设计原则（很重要，否则就是个按摩器）：
 *  1. **尊重系统设置**：系统里关掉「触感反馈」、或开了勿扰模式、或系统不支持振动，
 *     就完全不振。恶搞归恶搞，不能无视用户对手机的设定。
 *  2. **只报有意义的瞬间**：落地、起飞、受惊、跳跃这类"事件"，
 *     而不是每只虫子每帧都振。
 *  3. **限流**：两次振动至少间隔 [MIN_GAP_MS]，
 *     否则手指划过多只虫子时会连成一片乱振。
 *  4. **分品种强度**：按体型和类别给不同振幅 —— 蚂蚁轻、蟑螂重、蛾子软。
 *
 * 设备能力（COMPOSE_EFFECTS / AMPLITUDE_CONTROL / 预定义效果）在初始化时探测，
 * 不支持就自动降级，不会崩。
 */
class HapticPlayer(context: Context) {

    companion object {
        private const val TAG = "HapticPlayer"

        /** 两次振动的最小间隔，防止连成一片。 */
        private const val MIN_GAP_MS = 90L
    }

    /** 是否允许振动（用户在 App 里的开关）。 */
    @Volatile
    var enabled: Boolean = true

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Throwable) {
        null
    }

    /** 系统设置的解析器，用来读"触感反馈开关"。 */
    private val resolver = context.contentResolver

    private val hasVibrator: Boolean = try {
        vibrator?.hasVibrator() == true
    } catch (e: Throwable) {
        false
    }

    /** 支持振幅控制（可以给不同强度）。 */
    private val canAmplitude: Boolean = try {
        vibrator?.hasAmplitudeControl() == true
    } catch (e: Throwable) {
        false
    }

    /** 是否支持 primitive 组合效果（能做"咔哒+轻敲"这种细腻振感）。 */
    private val canCompose: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (vibrator?.areEffectsSupported(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    VibrationEffect.Composition.PRIMITIVE_TICK,
                    VibrationEffect.Composition.PRIMITIVE_THUD
                )?.isNotEmpty() == true)

    private var lastVibrateAt = 0L

    /** 设备/系统是否允许振动。 */
    private fun systemAllows(): Boolean {
        if (!hasVibrator) return false
        // 系统里的「触感反馈」总开关。默认 1（开启）。
        // 注意：Settings.System.HAPTIC_FEEDBACK_ENABLED 在较新 SDK 上被标记弃用，
        // 但这个系统设置本身仍然有效，所以直接用字符串键读取。
        val hapticOn = try {
            Settings.System.getInt(resolver, "haptic_feedback_enabled", 1) == 1
        } catch (e: Throwable) {
            true
        }
        if (!hapticOn) return false

        // 勿扰模式：zen_mode != 0 表示开了勿扰，不打扰用户
        val zen = try {
            Settings.Global.getInt(resolver, "zen_mode", 0)
        } catch (e: Throwable) {
            0
        }
        if (zen != 0) return false

        return true
    }

    /**
     * 现在能不能振。
     *
     * 三种情况会拒绝，并且各打一条日志 —— 这样在真机上能明确区分
     * "没振是因为限流" 还是 "没振是因为系统不让"，
     * 不然调试时只能靠猜。
     */
    private fun ready(tag: String): Boolean {
        if (!enabled) {
            Log.d(TAG, "skip($tag): 用户关了振动开关")
            return false
        }
        if (!hasVibrator) {
            Log.d(TAG, "skip($tag): 设备没有振动器")
            return false
        }
        if (!systemAllows()) {
            Log.d(TAG, "skip($tag): 系统不允许（触感反馈关闭 / 勿扰开启）")
            return false
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastVibrateAt < MIN_GAP_MS) {
            Log.d(TAG, "skip($tag): 限流（距上次 ${now - lastVibrateAt}ms < ${MIN_GAP_MS}ms）")
            return false
        }
        lastVibrateAt = now
        return true
    }

    /**
     * 只按强度振一下。
     * @param amplitude 1..255，会被映射到设备支持的范围
     * @param ms 时长
     */
    fun oneShot(amplitude: Int, ms: Long) {
        if (!ready("oneShot")) return
        try {
            val v = vibrator ?: return
            val amp = amplitude.coerceIn(1, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, amp))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "oneShot failed", e)
        }
    }

    /** 按波形振（timings 交替表示 振/停）。 */
    fun waveform(timings: LongArray, amplitudes: IntArray) {
        if (!ready("waveform")) return
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amps = if (canAmplitude) {
                    IntArray(amplitudes.size) { amplitudes[it].coerceIn(1, 255) }
                } else {
                    null   // 不支持振幅控制就交给系统默认
                }
                v.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, -1)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "waveform failed", e)
        }
    }

    /** 走设备的自定义效果（如果支持），不支持就退化成 oneShot。 */
    fun predefined(effectId: Int, fallbackAmp: Int, fallbackMs: Long) {
        if (!ready("predefined")) return
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                v.areEffectsSupported(effectId).isNotEmpty()
            ) {
                v.vibrate(VibrationEffect.createPredefined(effectId))
            } else {
                // 已经通过 ready() 扣过限流时间戳，这里直接调底层
                val amp = fallbackAmp.coerceIn(1, 255)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(fallbackMs, amp))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(fallbackMs)
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "predefined failed", e)
        }
    }

    // ================================================================ 各事件

    /**
     * 被戳中：最强的一次反馈，要"啪"地一下让人以为真拍到了虫子。
     * 用 HEAVY_CLICK 效果 + 前置一个极短的 CLICK 做冲击感。
     */
    fun onPoke(species: FlySpecies, sizeScale: Float) {
        if (!ready("onPoke")) return
        try {
            val v = vibrator ?: return
            val weight = species.hapticWeight * sizeScale
            val amp = (110 + 110 * weight).toInt().coerceIn(80, 255)
            Log.d(TAG, "onPoke ${species.displayName} weight=$weight amp=$amp compose=$canCompose")

            if (canCompose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val comp = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f, 0)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.95f, 28)
                    .compose()
                v.vibrate(comp)
            } else {
                v.vibrate(VibrationEffect.createOneShot(26, amp))
            }
        } catch (e: Throwable) {
            Log.d(TAG, "onPoke failed", e)
        }
    }

    /**
     * 飞走/逃窜：两三下快速的短振，像翅膀扑腾。
     * 强调整体的"嗡"一下而不是单击。
     */
    fun onFlee(species: FlySpecies, sizeScale: Float) {
        val weight = species.hapticWeight * sizeScale
        val amp = (80 + 90 * weight).toInt().coerceIn(60, 220)
        Log.d(TAG, "onFlee ${species.displayName} weight=$weight amp=$amp")
        waveform(
            longArrayOf(0, 14, 22, 12, 18, 10),
            intArrayOf(0, amp, 0, (amp * 0.8f).toInt(), 0, (amp * 0.6f).toInt())
        )
    }

    /** 落地/停住：一下轻微的"落"感，像有东西掉在屏幕上。 */
    fun onLand(species: FlySpecies, sizeScale: Float) {
        val weight = species.hapticWeight * sizeScale
        val amp = (40 + 60 * weight).toInt().coerceIn(30, 140)
        Log.d(TAG, "onLand ${species.displayName} weight=$weight amp=$amp")
        oneShot(amp, 12)
    }

    /** 起飞：比落地略强一点点。 */
    fun onTakeOff(species: FlySpecies, sizeScale: Float) {
        val weight = species.hapticWeight * sizeScale
        val amp = (55 + 70 * weight).toInt().coerceIn(40, 170)
        Log.d(TAG, "onTakeOff ${species.displayName} weight=$weight amp=$amp")
        oneShot(amp, 10)
    }

    /** 蟋蟀跳跃：一次干脆的"弹"感。 */
    fun onHop(species: FlySpecies, sizeScale: Float) {
        if (!ready("onHop")) return
        try {
            val v = vibrator ?: return
            val weight = species.hapticWeight * sizeScale
            val amp = (100 + 100 * weight).toInt().coerceIn(80, 240)
            if (canCompose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val comp = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f, 0)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 18)
                    .compose()
                v.vibrate(comp)
            } else {
                v.vibrate(VibrationEffect.createOneShot(18, amp))
            }
        } catch (e: Throwable) {
            Log.d(TAG, "onHop failed", e)
        }
    }

    /** 放出虫子时的开场：由远及近的一串，像虫群涌出来。 */
    fun onLaunch() {
        waveform(
            longArrayOf(0, 16, 40, 20, 40, 26, 40, 34),
            intArrayOf(0, 70, 0, 110, 0, 150, 0, 190)
        )
    }

    /** 收网：一下明确的"结束"感。 */
    fun onStop() {
        waveform(
            longArrayOf(0, 24, 30, 40),
            intArrayOf(0, 160, 0, 90)
        )
    }
}
