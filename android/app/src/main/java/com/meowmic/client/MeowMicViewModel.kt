package com.meowmic.client

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 连接状态
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val serverAddr: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * MeowMic 客户端 ViewModel
 *
 * 职责:
 * - 管理 Rust core 连接生命周期
 * - 管理 AudioCapture 生命周期
 * - 暴露状态给 UI
 */
class MeowMicViewModel : ViewModel() {

    companion object {
        private const val TAG = "MeowMic/VM"
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _stats = MutableStateFlow("暂无数据")
    val stats: StateFlow<String> = _stats.asStateFlow()

    private var audioCapture: AudioCapture? = null
    private var touchHandler: TouchHandler? = null

    /**
     * 连接到服务端
     */
    fun connect(serverAddr: String, clientName: String = "Android-Client") {
        if (_connectionState.value is ConnectionState.Connecting) return

        _connectionState.value = ConnectionState.Connecting

        viewModelScope.launch(Dispatchers.IO) {
            // 1. Rust core 连接
            if (!NativeBridge.isLoaded()) {
                _connectionState.value = ConnectionState.Error("libmeowmic.so 未加载")
                return@launch
            }
            val ok = try {
                NativeBridge.nativeConnect(serverAddr, clientName)
            } catch (e: UnsatisfiedLinkError) {
                _connectionState.value = ConnectionState.Error("Native 方法未实现: ${e.message}")
                return@launch
            }
            if (!ok) {
                _connectionState.value = ConnectionState.Error("连接失败,检查地址或防火墙")
                return@launch
            }

            // 2. 初始化音频采集
            val audio = AudioCapture()
            if (!audio.init()) {
                Log.w(TAG, "音频初始化失败,继续连接(仅触控)")
            } else {
                audio.start { pcm ->
                    try {
                        NativeBridge.nativeSendAudioFrame(pcm)
                    } catch (e: UnsatisfiedLinkError) {
                        Log.w(TAG, "sendAudioFrame 失败: ${e.message}")
                    }
                }
                audioCapture = audio
            }

            // 3. 初始化触摸处理器
            touchHandler = TouchHandler()

            _connectionState.value = ConnectionState.Connected(serverAddr)
            startStatsPolling()
        }
    }

    /**
     * 处理触摸事件(由 UI 层转发)
     */
    fun handleTouch(event: android.view.MotionEvent): Boolean {
        return touchHandler?.handle(event) ?: false
    }

    private fun startStatsPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (_connectionState.value is ConnectionState.Connected) {
                try {
                    val json = NativeBridge.nativeGetStats()
                    _stats.value = json
                } catch (e: UnsatisfiedLinkError) {
                    break
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        audioCapture?.release()
        audioCapture = null
        touchHandler = null
        try {
            if (NativeBridge.isLoaded()) NativeBridge.nativeDisconnect()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeDisconnect 失败: ${e.message}")
        }
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
