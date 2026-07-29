package com.meowmic.client

import android.content.Context
import android.net.Uri
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

/**
 * 快捷音频 slot 数据
 * @param index slot 索引(0..N-1)
 * @param name  显示名称(tag 文字)
 * @param path  文件路径
 */
data class QuickAudioSlot(
    val index: Int,
    val name: String,
    val path: String,
)

class MeowMicViewModel : ViewModel() {

    companion object {
        private const val TAG = "MeowMic/VM"
        private const val PREFS_NAME = "meowmic_client_prefs"
        private const val KEY_HISTORY = "history_addr"
        private const val KEY_LAST_ADDR = "last_addr"
        private const val MAX_HISTORY = 5

        // 音频面板
        private const val KEY_AUDIO_HISTORY = "audio_history"
        private const val KEY_QUICK_SLOTS = "quick_slots"
        private const val MAX_AUDIO_HISTORY = 10
        const val QUICK_SLOT_COUNT = 8
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _stats = MutableStateFlow("暂无数据")
    val stats: StateFlow<String> = _stats.asStateFlow()

    private val _historyAddresses = MutableStateFlow<List<String>>(emptyList())
    val historyAddresses: StateFlow<List<String>> = _historyAddresses.asStateFlow()

    private val _lastAddr = MutableStateFlow("")
    val lastAddr: StateFlow<String> = _lastAddr.asStateFlow()

    private var audioCapture: AudioCapture? = null
    private var touchHandler: TouchHandler? = null
    private var context: Context? = null

    private val _micEnabled = MutableStateFlow(false)
    val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    private val _muteSpeaker = MutableStateFlow(false)
    val muteSpeaker: StateFlow<Boolean> = _muteSpeaker.asStateFlow()

    private val audioInputManager = AudioInputManager()
    val currentAudioMode: StateFlow<AudioInputManager.InputMode> = audioInputManager.currentMode

    // 音频面板:历史记录 + 快捷 slot
    private val _audioHistory = MutableStateFlow<List<QuickAudioSlot>>(emptyList())
    val audioHistory: StateFlow<List<QuickAudioSlot>> = _audioHistory.asStateFlow()

    private val _quickSlots = MutableStateFlow<List<QuickAudioSlot?>>(List(QUICK_SLOT_COUNT) { null })
    val quickSlots: StateFlow<List<QuickAudioSlot?>> = _quickSlots.asStateFlow()

    // 当前正在编辑的 slot 索引(等待文件选择)
    private var pendingSlotIndex: Int = -1

    // ============ 自动更新 ============
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private var updateChecker: UpdateChecker? = null
    private var pendingApkPath: String? = null

    fun init(context: Context) {
        this.context = context.applicationContext
        audioInputManager.init(context.applicationContext)
        updateChecker = UpdateChecker(context.applicationContext)
        loadHistory()
        loadAudioPanel()
    }

    private fun loadHistory() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs?.getStringSet(KEY_HISTORY, emptySet()) ?: emptySet()
        _historyAddresses.value = saved.toList().take(MAX_HISTORY)
        _lastAddr.value = prefs?.getString(KEY_LAST_ADDR, "") ?: ""
    }

    private fun saveHistory(address: String) {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = _historyAddresses.value.toMutableList()
        current.remove(address)
        current.add(0, address)
        val limited = current.take(MAX_HISTORY)
        _historyAddresses.value = limited
        _lastAddr.value = address
        prefs?.edit()?.apply {
            putStringSet(KEY_HISTORY, limited.toSet())
            putString(KEY_LAST_ADDR, address)
            apply()
        }
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
                // 仅初始化,不主动 start。由 PTT/实时模式按需启动。
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
        // 必须在 IO 线程:nativeSetMuteSpeaker 内部 block_on 走 TCP 控制流,
        // 在 UI 主线程调用会与后台 control_recv task 争用 control_stream 锁导致 ANR/闪退
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NativeBridge.nativeSetMuteSpeaker(mute)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "nativeSetMuteSpeaker 失败: ${e.message}")
            }
        }
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
        return NativeBridge.sendButtonClick(button)
    }

    fun sendButtonDown(button: Int): Boolean {
        return NativeBridge.sendButtonDown(button)
    }

    fun sendButtonUp(button: Int): Boolean {
        return NativeBridge.sendButtonUp(button)
    }

    // ============ 键盘事件转发 ============

    fun sendKeyDown(keyCode: Int): Boolean {
        return NativeBridge.sendKeyDown(keyCode)
    }

    fun sendKeyUp(keyCode: Int): Boolean {
        return NativeBridge.sendKeyUp(keyCode)
    }

    fun sendKeyPress(keyCode: Int): Boolean {
        return NativeBridge.sendKeyPress(keyCode)
    }

    fun sendKeyCombo(vararg keyCodes: Int): Boolean {
        return NativeBridge.sendKeyCombo(*keyCodes)
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

    // ==================== 音频面板 ====================

    /**
     * 从 Uri 解析出可持久化的文件路径 + 显示名称
     * 返回 (path, name) 或 null
     */
    private fun resolveAudioUri(uri: Uri): Pair<String, String>? {
        val ctx = context ?: return null
        val resolver = ctx.contentResolver
        var name: String? = null
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment ?: "audio"
        }
        // 尝试将 uri 转为可重复打开的路径(失败则保留 uri 字符串)
        val path = uri.toString()
        val finalName: String = name ?: "audio"
        return path to finalName
    }

    private fun loadAudioPanel() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        // 历史
        val histSet = prefs.getStringSet(KEY_AUDIO_HISTORY, emptySet()) ?: emptySet()
        _audioHistory.value = histSet.mapNotNull { entry ->
            val parts = entry.split("\u0001", limit = 2)
            if (parts.size == 2) QuickAudioSlot(0, parts[0], parts[1]) else null
        }.take(MAX_AUDIO_HISTORY)

        // 快捷 slot
        val slotList = mutableListOf<QuickAudioSlot?>()
        for (i in 0 until QUICK_SLOT_COUNT) {
            val raw = prefs.getString("${KEY_QUICK_SLOTS}_$i", null)
            if (raw.isNullOrBlank()) {
                slotList.add(null)
            } else {
                val parts = raw.split("\u0001", limit = 2)
                if (parts.size == 2) {
                    slotList.add(QuickAudioSlot(i, parts[0], parts[1]))
                } else {
                    slotList.add(null)
                }
            }
        }
        _quickSlots.value = slotList
    }

    private fun persistAudioPanel() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit().apply {
            putStringSet(
                KEY_AUDIO_HISTORY,
                _audioHistory.value.map { "${it.name}\u0001${it.path}" }.toSet()
            )
            for (i in 0 until QUICK_SLOT_COUNT) {
                val slot = _quickSlots.value.getOrNull(i)
                if (slot == null) {
                    remove("${KEY_QUICK_SLOTS}_$i")
                } else {
                    putString("${KEY_QUICK_SLOTS}_$i", "${slot.name}\u0001${slot.path}")
                }
            }
            apply()
        }
    }

    /**
     * 处理音频文件选择结果。
     * @param uri 选择的文件 Uri
     * @return true 表示已消费(绑定到 slot),false 表示加入历史
     */
    fun onAudioFilePicked(uri: Uri): Boolean {
        val resolved = resolveAudioUri(uri) ?: return false
        val (path, name) = resolved
        return if (pendingSlotIndex in 0 until QUICK_SLOT_COUNT) {
            assignQuickSlot(pendingSlotIndex, name, path)
            pendingSlotIndex = -1
            true
        } else {
            addAudioHistory(name, path)
            false
        }
    }

    /**
     * 触发 slot 编辑流程(等待下次文件选择)
     */
    fun startEditQuickSlot(index: Int) {
        pendingSlotIndex = index
    }

    /**
     * 取消 slot 编辑
     */
    fun cancelEditQuickSlot() {
        pendingSlotIndex = -1
    }

    fun addAudioHistory(name: String, path: String) {
        val current = _audioHistory.value.toMutableList()
        current.removeAll { it.path == path }
        current.add(0, QuickAudioSlot(0, name, path))
        _audioHistory.value = current.take(MAX_AUDIO_HISTORY)
        persistAudioPanel()
    }

    fun assignQuickSlot(index: Int, name: String, path: String) {
        if (index !in 0 until QUICK_SLOT_COUNT) return
        val list = _quickSlots.value.toMutableList()
        // 同一文件已在其它 slot,先移除
        for (i in list.indices) {
            if (list[i]?.path == path && i != index) list[i] = null
        }
        list[index] = QuickAudioSlot(index, name, path)
        _quickSlots.value = list
        persistAudioPanel()
    }

    fun clearQuickSlot(index: Int) {
        if (index !in 0 until QUICK_SLOT_COUNT) return
        val list = _quickSlots.value.toMutableList()
        list[index] = null
        _quickSlots.value = list
        persistAudioPanel()
    }

    /**
     * 移动 slot 内容(交换两个 slot)
     */
    fun moveQuickSlot(from: Int, to: Int) {
        if (from !in 0 until QUICK_SLOT_COUNT) return
        if (to !in 0 until QUICK_SLOT_COUNT) return
        if (from == to) return
        val list = _quickSlots.value.toMutableList()
        val a = list[from]
        val b = list[to]
        list[from] = b?.copy(index = from)
        list[to] = a?.copy(index = to)
        _quickSlots.value = list
        persistAudioPanel()
    }

    /**
     * 播放快捷 slot 的音频
     */
    suspend fun playQuickSlot(index: Int): Boolean {
        val slot = _quickSlots.value.getOrNull(index) ?: return false
        return playMusicFile(slot.path)
    }

    // ============ 自动更新 ============

    /** 当前应用版本名(来自 PackageInfo) */
    fun currentVersion(): String = updateChecker?.getCurrentVersion() ?: "0.0.0"

    /** 检查 GitHub 最新 Release */
    fun checkForUpdate() {
        val checker = updateChecker ?: run {
            _updateState.value = UpdateState.Error("更新检查器未初始化")
            return
        }
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            _updateState.value = checker.checkLatest()
        }
    }

    /** 下载最新 APK。仅当状态为 Available 时可调用。 */
    fun downloadUpdate() {
        val checker = updateChecker ?: return
        val state = _updateState.value
        val url = (state as? UpdateState.Available)?.downloadUrl ?: run {
            _updateState.value = UpdateState.Error("无可下载的更新")
            return
        }
        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(0)
            try {
                val path = checker.downloadApk(url) { progress ->
                    _updateState.value = UpdateState.Downloading(progress)
                }
                pendingApkPath = path
                _updateState.value = UpdateState.ReadyToInstall(path)
            } catch (e: Exception) {
                Log.w(TAG, "下载更新失败", e)
                _updateState.value = UpdateState.Error(e.message ?: "下载失败")
            }
        }
    }

    /** 调起系统安装器。仅当状态为 ReadyToInstall 时可调用。 */
    fun installUpdate() {
        val checker = updateChecker ?: return
        val path = pendingApkPath ?: return
        try {
            checker.installApk(path)
        } catch (e: Exception) {
            Log.w(TAG, "调起安装器失败", e)
            _updateState.value = UpdateState.Error(e.message ?: "无法启动安装器")
        }
    }

    /** 重置更新状态(从 Error 返回 Idle) */
    fun resetUpdateState() {
        _updateState.value = UpdateState.Idle
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
