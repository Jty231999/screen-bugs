package com.bt.flyprank

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * 苍蝇的嗡嗡声。
 *
 * 不打包任何音频文件，而是在内存里实时合成后推给 AudioTrack 循环播放：
 *   基频 ~176Hz 的锯齿波（蝇翅振动的主导谐波）
 *   × 28Hz 的振幅颤动（听感上那种"嗡——"的抖动）
 * 取 0.25 秒整数个周期循环，接缝处不会有"咔哒"声。
 *
 * 音量随时间自动起伏（苍蝇飞远飞近、受惊时突然变响）。
 */
class BuzzPlayer {

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    @Volatile private var targetVolume = 0f
    private var currentVolume = 0f

    @Volatile var enabled: Boolean = true

    /** 受惊/靠近时调用，让嗡声瞬间拔高，配合"嗡——！"的惊吓效果。 */
    fun spike() {
        targetVolume = 1f
    }

    fun start(context: Context) {
        if (running) return
        running = true

        thread = Thread {
            val sampleRate = 22050
            val seconds = 0.25f
            val count = (sampleRate * seconds).toInt()
            val samples = ShortArray(count)

            val fBuzz = 176.0
            val fTrem = 28.0
            for (i in 0 until count) {
                val t = i.toDouble() / sampleRate
                // 锯齿波：蝇类振翅声富含奇次谐波
                val saw = 2.0 * ((t * fBuzz) % 1.0) - 1.0
                // 叠加一点二次谐波让声音更"扎人"
                val harm = 0.35 * kotlin.math.sin(2.0 * Math.PI * fBuzz * 2.0 * t)
                val trem = 0.55 + 0.45 * kotlin.math.sin(2.0 * Math.PI * fTrem * t)
                val v = (saw * 0.55 + harm) * trem
                samples[i] = (v * 9000.0).toInt().coerceIn(-32768, 32767).toShort()
            }

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, samples.size * 4)

            val t = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Throwable) {
                return@Thread
            }

            track = t
            try {
                t.setVolume(0f)
                t.play()
            } catch (e: Throwable) {
                return@Thread
            }

            // 循环写入，同时按帧平滑音量
            var offset = 0
            while (running) {
                val chunk = ShortArray(1024)
                for (i in 0 until chunk.size) {
                    chunk[i] = samples[(offset + i) % samples.size]
                }
                offset = (offset + chunk.size) % samples.size

                // 音量平滑靠近目标值，避免爆音
                currentVolume += (targetVolume - currentVolume) * 0.08f
                val vol = if (enabled) currentVolume.coerceIn(0f, 1f) else 0f

                try {
                    t.setVolume(vol)
                    t.write(chunk, 0, chunk.size)
                } catch (e: Throwable) {
                    break
                }
            }

            try {
                t.stop()
            } catch (_: Throwable) {
            }
            try {
                t.release()
            } catch (_: Throwable) {
            }
            track = null
        }.also { it.isDaemon = true; it.start() }
    }

    /**
     * 每帧调用，告知"当前应该有多响"。
     * 苍蝇高速乱窜时最响，停落时几乎无声，这样听感才像真的。
     */
    fun setActivity(level: Float) {
        targetVolume = level.coerceIn(0f, 1f)
    }

    fun stop() {
        running = false
        thread?.let {
            try {
                it.join(400)
            } catch (_: InterruptedException) {
            }
        }
        thread = null
        try {
            track?.pause()
            track?.flush()
        } catch (_: Throwable) {
        }
        track = null
    }
}
