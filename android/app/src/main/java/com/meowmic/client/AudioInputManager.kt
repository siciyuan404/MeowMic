package com.meowmic.client

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class AudioInputManager {

    companion object {
        private const val TAG = "MeowMic/AudioInput"
    }

    enum class InputMode {
        MICROPHONE,
        MUSIC_FILE,
    }

    private val _currentMode = MutableStateFlow(InputMode.MICROPHONE)
    val currentMode: StateFlow<InputMode> = _currentMode.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var pcmFileThread: Thread? = null
    private var isPlaying = false

    fun switchToMicrophone() {
        stopMusicPlayback()
        _currentMode.value = InputMode.MICROPHONE
        Log.i(TAG, "切换到麦克风输入模式")
    }

    suspend fun playMusicFile(filePath: String): Boolean {
        stopMusicPlayback()

        return withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "文件不存在: $filePath")
                    return@withContext false
                }

                val fileName = file.name.lowercase()
                if (fileName.endsWith(".pcm")) {
                    return@withContext playPcmFile(file)
                } else if (fileName.endsWith(".mp3") || fileName.endsWith(".wav") ||
                           fileName.endsWith(".m4a") || fileName.endsWith(".aac")) {
                    return@withContext playCompressedAudio(file)
                } else {
                    Log.e(TAG, "不支持的文件格式: $fileName")
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e(TAG, "播放音乐文件失败", e)
                false
            }
        }
    }

    private fun playPcmFile(file: File): Boolean {
        val samplesPerFrame = 960
        val frameBytes = samplesPerFrame * 2

        pcmFileThread = Thread({
            isPlaying = true
            Log.i(TAG, "开始播放 PCM 文件: ${file.name}")

            try {
                val fis = FileInputStream(file)
                val buffer = ByteArray(frameBytes)

                while (isPlaying) {
                    val bytesRead = fis.read(buffer)
                    if (bytesRead <= 0) {
                        fis.channel.position(0)
                        continue
                    }

                    if (bytesRead < frameBytes) {
                        val newBuffer = ByteArray(bytesRead)
                        System.arraycopy(buffer, 0, newBuffer, 0, bytesRead)
                        sendPcmData(newBuffer)
                    } else {
                        sendPcmData(buffer)
                    }

                    Thread.sleep(20)
                }
                fis.close()
            } catch (e: Exception) {
                Log.e(TAG, "PCM 播放线程异常", e)
            } finally {
                isPlaying = false
                _currentMode.value = InputMode.MICROPHONE
            }
        }, "MeowMic-PCM-Player").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun playCompressedAudio(file: File): Boolean {
        return try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                isLooping = true
                start()
            }
            _currentMode.value = InputMode.MUSIC_FILE
            Log.i(TAG, "开始播放音乐文件: ${file.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer 初始化失败", e)
            false
        }
    }

    private fun sendPcmData(data: ByteArray) {
        try {
            NativeBridge.nativeSendAudioFrame(data.toShortArray())
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "发送 PCM 数据失败: ${e.message}")
        }
    }

    private fun ByteArray.toShortArray(): ShortArray {
        val shortArray = ShortArray(this.size / 2)
        for (i in shortArray.indices) {
            shortArray[i] = (this[i * 2].toInt() or (this[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return shortArray
    }

    fun stopMusicPlayback() {
        isPlaying = false
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        pcmFileThread?.join(500)
        pcmFileThread = null
        if (_currentMode.value == InputMode.MUSIC_FILE) {
            _currentMode.value = InputMode.MICROPHONE
        }
    }

    fun isPlaying(): Boolean = isPlaying || (mediaPlayer?.isPlaying == true)

    override fun toString(): String {
        return "AudioInputManager(mode=${_currentMode.value}, playing=$isPlaying)"
    }
}
