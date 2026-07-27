package com.meowmic.client

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * 音频输入管理器
 *
 * 两种模式:
 * - MICROPHONE: 麦克风采集 → PCM → nativeSendAudioFrame → PC 扬声器播放
 * - MUSIC_FILE: 音频文件解码 → PCM → nativeSendAudioFrame → PC 扬声器播放
 *
 * 关键:所有音频都通过 nativeSendAudioFrame 发给 PC,不在手机本地播放。
 * PC 端 server 收到后通过 cpal 输出到 PC 扬声器(相当于把手机当作 PC 的麦克风)。
 */
class AudioInputManager {

    companion object {
        private const val TAG = "MeowMic/AudioInput"
        // 目标 PCM 格式(与 PC server 协商一致)
        private const val TARGET_SAMPLE_RATE = 48000
        private const val TARGET_CHANNELS = 1
        private const val FRAME_SAMPLES = 960  // 20ms @ 48kHz
        private const val FRAME_BYTES = FRAME_SAMPLES * 2  // i16 mono
    }

    enum class InputMode {
        MICROPHONE,
        MUSIC_FILE,
    }

    private val _currentMode = MutableStateFlow(InputMode.MICROPHONE)
    val currentMode: StateFlow<InputMode> = _currentMode.asStateFlow()

    private var decodeThread: Thread? = null
    @Volatile private var isPlaying = false
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun switchToMicrophone() {
        stopMusicPlayback()
        _currentMode.value = InputMode.MICROPHONE
        Log.i(TAG, "切换到麦克风输入模式")
    }

    /**
     * 播放音频文件 - 解码为 PCM 并发送到 PC(不在手机本地播放)
     *
     * path 可以是:
     *  - 普通文件路径(支持 .pcm/.mp3/.wav/.m4a/.aac 等)
     *  - content:// Uri 字符串
     */
    suspend fun playMusicFile(path: String): Boolean {
        stopMusicPlayback()

        return withContext(Dispatchers.IO) {
            try {
                val ctx = appContext
                if (ctx == null) {
                    Log.e(TAG, "appContext 未初始化")
                    return@withContext false
                }

                if (path.startsWith("content://")) {
                    val uri = Uri.parse(path)
                    return@withContext decodeWithMediaCodec(ctx, uri, path)
                }

                val file = File(path)
                if (!file.exists()) {
                    Log.e(TAG, "文件不存在: $path")
                    return@withContext false
                }

                val fileName = file.name.lowercase()
                if (fileName.endsWith(".pcm")) {
                    return@withContext playPcmFile(file)
                }
                // 其它格式统一用 MediaCodec 解码
                return@withContext decodeWithMediaCodec(ctx, Uri.fromFile(file), path)
            } catch (e: Exception) {
                Log.e(TAG, "播放音频文件失败", e)
                false
            }
        }
    }

    /**
     * 直接读取 PCM 文件(假设格式已匹配:48kHz mono i16)
     */
    private fun playPcmFile(file: File): Boolean {
        isPlaying = true
        _currentMode.value = InputMode.MUSIC_FILE
        decodeThread = Thread({
            try {
                val fis = FileInputStream(file)
                val buffer = ByteArray(FRAME_BYTES)
                Log.i(TAG, "开始发送 PCM 到 PC: ${file.name}")
                while (isPlaying) {
                    val bytesRead = fis.read(buffer)
                    if (bytesRead <= 0) {
                        fis.channel.position(0)
                        continue
                    }
                    if (bytesRead < FRAME_BYTES) {
                        val pad = ByteArray(FRAME_BYTES)
                        System.arraycopy(buffer, 0, pad, 0, bytesRead)
                        sendPcmFrame(pad)
                    } else {
                        sendPcmFrame(buffer)
                    }
                    // 20ms 节流,匹配 PC 端消费速率
                    Thread.sleep(20)
                }
                fis.close()
            } catch (e: Exception) {
                Log.e(TAG, "PCM 发送线程异常", e)
            } finally {
                isPlaying = false
                _currentMode.value = InputMode.MICROPHONE
            }
        }, "MeowMic-PCM-Sender").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * 用 MediaExtractor + MediaCodec 解码任意容器格式为 PCM,
     * 然后按帧发送给 PC。支持 MP3/AAC/M4A/WAV 等。
     */
    private fun decodeWithMediaCodec(ctx: Context, uri: Uri, debugPath: String): Boolean {
        isPlaying = true
        _currentMode.value = InputMode.MUSIC_FILE
        decodeThread = Thread({
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(ctx, uri, null)

                // 找到音频轨道
                var audioTrackIndex = -1
                var inputFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        inputFormat = fmt
                        break
                    }
                }
                if (audioTrackIndex < 0 || inputFormat == null) {
                    Log.e(TAG, "未找到音频轨道: $debugPath")
                    return@Thread
                }
                extractor.selectTrack(audioTrackIndex)

                val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                val pcmBuffer = java.util.concurrent.ConcurrentLinkedQueue<Short>()
                var frameAccumulator = ShortArray(0)

                Log.i(TAG, "开始解码音频到 PC: $debugPath (mime=$mime)")

                // 输入/输出循环
                while (isPlaying) {
                    val ex = extractor ?: break
                    // 喂输入
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val ib = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = ex.readSampleData(ib, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }

                    // 取输出
                    val outIndex = codec.dequeueOutputBuffer(info, 10000)
                    if (outIndex >= 0) {
                        val ob = codec.getOutputBuffer(outIndex)!!
                        // 读取 PCM(假设是 i16)
                        val remaining = ob.remaining()
                        val bytes = ByteArray(remaining)
                        ob.get(bytes)
                        codec.releaseOutputBuffer(outIndex, false)

                        // 转换为 short 并累积到 frameAccumulator
                        val shorts = bytesToShorts(bytes)
                        frameAccumulator = concatShorts(frameAccumulator, shorts)

                        // 按 FRAME_SAMPLES 切片发送
                        while (frameAccumulator.size >= FRAME_SAMPLES) {
                            val frame = frameAccumulator.copyOfRange(0, FRAME_SAMPLES)
                            sendPcmFrameShorts(frame)
                            frameAccumulator = frameAccumulator.copyOfRange(FRAME_SAMPLES, frameAccumulator.size)
                            // 节流:每帧 20ms
                            Thread.sleep(20)
                        }

                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.i(TAG, "解码完成,循环重播")
                            // 循环播放:重新定位 extractor
                            ex.release()
                            val newExtractor = MediaExtractor()
                            newExtractor.setDataSource(ctx, uri, null)
                            extractor = newExtractor
                            // 重新查找音频轨道
                            for (i in 0 until newExtractor.trackCount) {
                                val fmt = newExtractor.getTrackFormat(i)
                                val m = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                                if (m.startsWith("audio/")) {
                                    newExtractor.selectTrack(i)
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaCodec 解码异常", e)
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                try { extractor?.release() } catch (_: Exception) {}
                isPlaying = false
                _currentMode.value = InputMode.MICROPHONE
            }
        }, "MeowMic-Decoder").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun sendPcmFrame(bytes: ByteArray) {
        // bytes 长度应为 FRAME_BYTES
        if (bytes.size != FRAME_BYTES) {
            val pad = ByteArray(FRAME_BYTES)
            System.arraycopy(bytes, 0, pad, 0, minOf(bytes.size, FRAME_BYTES))
            sendPcmFrameShorts(bytesToShorts(pad))
        } else {
            sendPcmFrameShorts(bytesToShorts(bytes))
        }
    }

    private fun sendPcmFrameShorts(shorts: ShortArray) {
        try {
            NativeBridge.nativeSendAudioFrame(shorts)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "发送 PCM 失败: ${e.message}")
        }
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val n = bytes.size / 2
        val out = ShortArray(n)
        for (i in 0 until n) {
            // little-endian i16
            out[i] = ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8 or
                    (bytes[i * 2].toInt() and 0xFF)).toShort()
        }
        return out
    }

    private fun concatShorts(a: ShortArray, b: ShortArray): ShortArray {
        val r = ShortArray(a.size + b.size)
        System.arraycopy(a, 0, r, 0, a.size)
        System.arraycopy(b, 0, r, a.size, b.size)
        return r
    }

    fun stopMusicPlayback() {
        isPlaying = false
        decodeThread?.join(500)
        decodeThread = null
        if (_currentMode.value == InputMode.MUSIC_FILE) {
            _currentMode.value = InputMode.MICROPHONE
        }
    }

    fun isPlaying(): Boolean = isPlaying

    override fun toString(): String {
        return "AudioInputManager(mode=${_currentMode.value}, playing=$isPlaying)"
    }
}
