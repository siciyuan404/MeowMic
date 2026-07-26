package com.meowmic.client

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val serverAddr: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

class MeowMicViewModel : ViewModel() {

    companion object {
        private const val TAG = "MeowMic/VM"
        private const val PREFS_NAME = "meowmic_client_prefs"
        private const val KEY_HISTORY = "history_addr"
        private const val MAX_HISTORY = 5
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _stats = MutableStateFlow("暂无数据")
    val stats: StateFlow<String> = _stats.asStateFlow()

    private val _historyAddresses = MutableStateFlow<List<String>>(emptyList())
    val historyAddresses: StateFlow<List<String>> = _historyAddresses.asStateFlow()

    private var audioCapture: AudioCapture? = null
    private var touchHandler: TouchHandler? = null
    private var context: Context? = null

    private val _micEnabled = MutableStateFlow(false)
    val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    private val _muteSpeaker = MutableStateFlow(false)
    val muteSpeaker: StateFlow<Boolean> = _muteSpeaker.asStateFlow()

    private val audioInputManager = AudioInputManager()
    val currentAudioMode: StateFlow<AudioInputManager.InputMode> = audioInputManager.currentMode

    fun init(context: Context) {
        this.context = context.applicationContext
        loadHistory()
    }

    private fun loadHistory() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs?.getStringSet(KEY_HISTORY, emptySet()) ?: emptySet()
        _historyAddresses.value = saved.toList().take(MAX_HISTORY)
    }

    private fun saveHistory(address: String) {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = _historyAddresses.value.toMutableList()
        current.remove(address)
        current.add(0, address)
        val limited = current.take(MAX_HISTORY)
        _historyAddresses.value = limited
        prefs?.edit()?.putStringSet(KEY_HISTORY, limited.toSet())?.apply()
    }

    fun connect(serverAddr: String, clientName: String = "Android-Client") {
        if (_connectionState.value is ConnectionState.Connecting) return

        _connectionState.value = ConnectionState.Connecting

        viewModelScope.launch(Dispatchers.IO) {
            if (!NativeBridge.isLoaded()) {
                _connectionState.value = ConnectionState.Error("libmeowmic.so 未加载")
                return@launch
            }

            // 带超时的连接检测
            val result = withContext(Dispatchers.Default) {
                val connectResult = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
                val thread = Thread {
                    try {
                        connectResult.set(NativeBridge.nativeConnect(serverAddr, clientName))
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "Native 错误", e)
                        connectResult.set(false)
                    }
                }
                thread.start()
                thread.join(5000)
                if (thread.isAlive) {
                    thread.interrupt()
                    null // 超时
                } else {
                    connectResult.get()
                }
            }

            if (result == null) {
                _connectionState.value = ConnectionState.Error("连接超时,请检查地址或网络")
                return@launch
            }

            if (result != true) {
                _connectionState.value = ConnectionState.Error("连接失败,检查地址或防火墙")
                return@launch
            }

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

            touchHandler = TouchHandler()

            _connectionState.value = ConnectionState.Connected(serverAddr)
            saveHistory(serverAddr)
            startStatsPolling()
        }
    }

    fun handleTouch(event: android.view.MotionEvent): Boolean {
        return touchHandler?.handle(event) ?: false
    }

    fun setScreenRotation(rotation: Int) {
        touchHandler?.let { it.screenRotation = rotation }
    }

    fun setMicEnabled(enabled: Boolean) {
        _micEnabled.value = enabled
        if (enabled) {
            audioCapture?.start { pcm ->
                try {
                    NativeBridge.nativeSendAudioFrame(pcm)
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "sendAudioFrame 失败: ${e.message}")
                }
            }
        } else {
            audioCapture?.stop()
        }
    }

    fun setMuteSpeaker(mute: Boolean) {
        _muteSpeaker.value = mute
    }

    fun switchAudioMode(mode: AudioInputManager.InputMode) {
        when (mode) {
            AudioInputManager.InputMode.MICROPHONE -> {
                audioInputManager.switchToMicrophone()
                if (_micEnabled.value) {
                    setMicEnabled(true)
                }
            }
            AudioInputManager.InputMode.MUSIC_FILE -> {
                setMicEnabled(false)
            }
        }
    }

    suspend fun playMusicFile(path: String): Boolean {
        setMicEnabled(false)
        return audioInputManager.playMusicFile(path)
    }

    fun stopMusicPlayback() {
        audioInputManager.stopMusicPlayback()
        if (_micEnabled.value) {
            setMicEnabled(true)
        }
    }

    fun sendButtonClick(button: Int): Boolean {
        return try {
            NativeBridge.nativeSendTouch(button, 0f, 0f)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "sendButtonClick 失败: ${e.message}")
            false
        }
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

    fun disconnect() {
        audioCapture?.release()
        audioCapture = null
        touchHandler?.reset()
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
