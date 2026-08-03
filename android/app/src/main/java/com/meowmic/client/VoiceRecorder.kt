package com.meowmic.client

import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 语音录音器:MediaRecorder 录制 AAC/M4A 到文件
 *
 * 用途:PTT(按住说话)录音产生文件,落盘后入库 + 推流到 PC 播放。
 *
 * 格式:MPEG_4 容器 + AAC 编码,48kHz 单声道 128kbps。
 * 与 [AudioInputManager.playMusicFile] 解码链兼容,录完可直接播放。
 *
 * 注意:RECORD_AUDIO 权限由调用方在连接前已申请,这里用 @Suppress("MissingPermission")。
 */
class VoiceRecorder {

    companion object {
        private const val TAG = "MeowMic/Recorder"
        private const val SAMPLE_RATE = 48000
        private const val BIT_RATE = 128_000
        private const val CHANNELS = 1
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    @Volatile private var isRecording = false

    /** 是否正在录音 */
    val recording: Boolean get() = isRecording
    /** 当前录音目标文件(录音中有效) */
    val currentFile: File? get() = outputFile

    /**
     * 开始录音
     * @param outputFile 输出文件(.m4a),父目录会自动创建
     * @return true 表示启动成功
     */
    @Suppress("MissingPermission")
    fun start(outputFile: File): Boolean {
        if (isRecording) {
            Log.w(TAG, "已在录音中,忽略重复 start")
            return false
        }

        outputFile.parentFile?.mkdirs()

        val rec = createRecorder() ?: run {
            Log.e(TAG, "MediaRecorder 创建失败")
            return false
        }

        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(SAMPLE_RATE)
            rec.setAudioEncodingBitRate(BIT_RATE)
            rec.setAudioChannels(CHANNELS)
            rec.setOutputFile(outputFile.absolutePath)
            rec.prepare()
            rec.start()

            recorder = rec
            this.outputFile = outputFile
            isRecording = true
            Log.i(TAG, "开始录音: ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder 启动失败", e)
            try { rec.release() } catch (_: Exception) {}
            recorder = null
            outputFile.delete()
            this.outputFile = null
            isRecording = false
            return false
        }
    }

    /**
     * 停止录音并返回文件
     * @return 录音文件(失败或录制过短返回 null)
     */
    fun stop(): File? {
        if (!isRecording) return null
        isRecording = false
        val file = outputFile
        val rec = recorder
        recorder = null
        outputFile = null

        try {
            rec?.stop()
        } catch (e: Exception) {
            // 录制过短(< 1s)会抛 RuntimeException,文件不可用
            Log.w(TAG, "MediaRecorder.stop 失败(可能录制过短)", e)
            try { rec?.reset() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            file?.delete()
            return null
        }
        try { rec?.release() } catch (_: Exception) {}

        if (file == null || !file.exists() || file.length() == 0L) {
            Log.w(TAG, "录音文件无效或为空")
            file?.delete()
            return null
        }
        Log.i(TAG, "录音完成: ${file.absolutePath} (${file.length()} bytes)")
        return file
    }

    /**
     * 取消录音(删除文件,不入库)
     */
    fun cancel() {
        if (!isRecording) return
        isRecording = false
        val rec = recorder
        recorder = null
        val file = outputFile
        outputFile = null

        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        file?.delete()
        Log.i(TAG, "录音已取消,文件已删除")
    }

    private fun createRecorder(): MediaRecorder? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(null)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
        } catch (e: Exception) {
            Log.e(TAG, "createRecorder 失败", e)
            null
        }
    }
}
