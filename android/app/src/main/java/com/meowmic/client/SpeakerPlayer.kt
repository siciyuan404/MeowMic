package com.meowmic.client

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PC→手机 音频播放(手机充当电脑喇叭)
 *
 * 从 Rust jitter buffer 拉取解码后的 PCM(48kHz 单声道 PCM16,20ms/帧),
 * 按帧写入 AudioTrack 播放。无可用帧时短暂休眠保持节拍。
 */
class SpeakerPlayer(
    private val sampleRate: Int = 48000,
    private val frameMs: Int = 20,
) {
    companion object {
        private const val TAG = "MeowMic/Speaker"
    }

    private var track: AudioTrack? = null
    private var playThread: Thread? = null
    private val running = AtomicBoolean(false)

    val samplesPerFrame: Int get() = sampleRate * frameMs / 1000

    /** 初始化 AudioTrack(48kHz / Mono / PCM16) */
    fun init(): Boolean {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val frameBytes = samplesPerFrame * 2
        val bufferBytes = maxOf(minBuf * 2, frameBytes * 4)
        return try {
            track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                bufferBytes,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            Log.i(TAG, "AudioTrack 初始化完成: ${sampleRate}Hz ${frameMs}ms")
            true
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack 初始化失败", e)
            false
        }
    }

    /** 启动播放循环 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = track ?: run {
            running.set(false)
            return
        }

        playThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                t.play()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioTrack.play 失败", e)
                running.set(false)
                return@Thread
            }
            val pcm = ShortArray(samplesPerFrame)
            Log.i(TAG, "PC 声音播放线程启动")
            while (running.get()) {
                val n = NativeBridge.nativePollAudioFrame(pcm)
                if (n > 0) {
                    try {
                        t.write(pcm, 0, n)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "AudioTrack.write 失败", e)
                        break
                    }
                } else {
                    // 暂无数据,短暂休眠(等下一帧,保持 20ms 节拍)
                    try {
                        Thread.sleep(5)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
            try {
                t.pause()
                t.flush()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioTrack.pause 失败", e)
            }
            Log.i(TAG, "PC 声音播放线程退出")
        }, "MeowMic-Speaker").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        playThread?.join(500)
        playThread = null
    }

    fun release() {
        stop()
        track?.release()
        track = null
    }
}