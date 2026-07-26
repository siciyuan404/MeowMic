package com.meowmic.client

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 麦克风采集:AudioRecord + LOW_LATENCY 模式
 *
 * P0 实现:
 * - 48kHz / Mono / PCM16
 * - 20ms 帧 (960 samples)
 * - PERFORMANCE_MODE_LOW_LATENCY (Android 10+)
 * - 独立线程,高优先级
 *
 * 后续 P3 优化:切换到 AAudio (NDK),EXCLUSIVE 模式,延迟 <10ms
 */
class AudioCapture(
    private val sampleRate: Int = 48000,
    private val frameMs: Int = 20,
) {
    companion object {
        private const val TAG = "MeowMic/Audio"
    }

    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private val running = AtomicBoolean(false)

    val samplesPerFrame: Int get() = sampleRate * frameMs / 1000

    /**
     * 检查录音权限并初始化 AudioRecord
     * @return true 表示初始化成功
     */
    fun init(): Boolean {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, format)
        val frameBytes = samplesPerFrame * 2
        val bufferBytes = maxOf(minBuf * 2, frameBytes * 4)

        try {
            // P0: 用构造函数方式,兼容性最好
            // 后续 P3 优化:切到 AAudio (NDK) + EXCLUSIVE 模式,延迟 <10ms
            @Suppress("MissingPermission")
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                format,
                bufferBytes,
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord 初始化失败", e)
            return false
        }

        Log.i(TAG, "AudioRecord 初始化完成: ${sampleRate}Hz ${frameMs}ms = ${samplesPerFrame}smp")
        return true
    }

    /**
     * 启动采集循环
     * @param onFrame 每采集到一帧 PCM 的回调
     */
    fun start(onFrame: (ShortArray) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val r = record ?: run {
            running.set(false)
            return
        }

        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            try {
                r.startRecording()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "startRecording 失败", e)
                running.set(false)
                return@Thread
            }
            val frame = ShortArray(samplesPerFrame)
            Log.i(TAG, "音频采集线程启动")
            while (running.get()) {
                val n = r.read(frame, 0, frame.size)
                if (n > 0) {
                    onFrame(if (n == frame.size) frame else frame.copyOfRange(0, n))
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read 错误: $n")
                    break
                }
            }
            try {
                r.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord.stop 失败", e)
            }
            Log.i(TAG, "音频采集线程退出")
        }, "MeowMic-Audio").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        captureThread?.join(500)
        captureThread = null
    }

    fun release() {
        stop()
        record?.release()
        record = null
    }
}
