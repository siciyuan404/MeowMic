package com.meowmic.client

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val serverAddr: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * 配对所需状态:nativeConnect 返回 2 时进入此状态,
 * UI 弹出 PIN 输入对话框,用户输入后调用 completePairing。
 */
data class PairingRequiredState(
    val serverAddr: String,
    val clientName: String,
)

/**
 * PC 列表条目(借鉴 Moonlight:手动添加与 mDNS 发现的 PC 完全同权)
 *
 * 以服务端公钥为身份(类 Sunshine uniqueid),IP 变化(DHCP)时按公钥合并地址,
 * 历史与配对状态不再失效。
 *
 * @param id        身份标识:"pk:<server_pubkey_b64>";身份未知时退化为 "addr:<host:port>"
 * @param name      显示名(主机名或服务实例名)
 * @param addresses 已知地址列表(有序,首个为当前最优;mDNS 解析地址优先)
 * @param status    在线状态(UNKNOWN/ONLINE/OFFLINE)
 * @param paired    本客户端是否已配对;null=未知
 * @param manual    true=用户手动添加(持久化);false=mDNS 发现
 */
data class PcEntry(
    val id: String,
    val name: String,
    val addresses: List<String>,
    val status: ServerStatus,
    val paired: Boolean?,
    val manual: Boolean,
) {
    /** 首选连接地址 */
    val primaryAddr: String get() = addresses.first()
}

/**
 * 地址规范化:去空格、去 scheme、裸 IP/主机名自动补默认端口。
 * 返回规范化后的 "host:port";无法解析时返回 null。
 */
fun normalizeAddress(input: String, defaultPort: Int = 28900): String? {
    var s = input.trim()
    if (s.isEmpty()) return null
    // 去掉误输入的 scheme 和结尾斜杠(如 "http://192.168.1.12/")
    s = s.removePrefix("http://").removePrefix("https://").trimEnd('/')
    if (s.isEmpty()) return null
    return if (s.contains(':')) {
        val host = s.substringBeforeLast(':')
        val port = s.substringAfterLast(':').toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) null else "$host:$port"
    } else {
        "$s:$defaultPort"
    }
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

        // PC 注册表:手动添加的 PC(JSON 持久化)
        private const val KEY_MANUAL_PCS = "manual_pcs"
        private const val MAX_MANUAL_PCS = 10
        /** 手动 PC 轮询间隔(与 mDNS 轮询一致) */
        private const val MANUAL_POLL_INTERVAL_MS = 3000L

        // 音频面板
        private const val KEY_AUDIO_HISTORY = "audio_history"
        private const val KEY_QUICK_SLOTS = "quick_slots"
        private const val MAX_AUDIO_HISTORY = 10
        const val QUICK_SLOT_COUNT = 8
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // ============ 配对状态 ============
    private val _pairingRequired = MutableStateFlow<PairingRequiredState?>(null)
    val pairingRequired: StateFlow<PairingRequiredState?> = _pairingRequired.asStateFlow()

    private val _pairingSubmitting = MutableStateFlow(false)
    val pairingSubmitting: StateFlow<Boolean> = _pairingSubmitting.asStateFlow()

    private val _stats = MutableStateFlow("暂无数据")
    val stats: StateFlow<String> = _stats.asStateFlow()

    private val _historyAddresses = MutableStateFlow<List<String>>(emptyList())
    val historyAddresses: StateFlow<List<String>> = _historyAddresses.asStateFlow()

    private val _lastAddr = MutableStateFlow("")
    val lastAddr: StateFlow<String> = _lastAddr.asStateFlow()

    // ============ mDNS 自动发现 ============
    private val _discoveredServers = MutableStateFlow<Set<DiscoveredServer>>(emptySet())
    val discoveredServers: StateFlow<Set<DiscoveredServer>> = _discoveredServers.asStateFlow()
    private var mdnsDiscovery: MdnsDiscovery? = null

    // ============ PC 注册表(手动 + 发现,按身份合并) ============
    /** 合并后的 PC 列表(UI 展示用) */
    private val _pcList = MutableStateFlow<List<PcEntry>>(emptyList())
    val pcList: StateFlow<List<PcEntry>> = _pcList.asStateFlow()

    /** 手动添加的 PC(持久化到 SharedPreferences) */
    private val _manualPcs = MutableStateFlow<List<PcEntry>>(emptyList())
    private var manualPollJob: Job? = null

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
        loadManualPcs()

        // 设置配对状态文件目录(必须在 nativeConnect 之前)
        try {
            val stateDir = context.applicationContext.filesDir.absolutePath
            NativeBridge.nativeSetStateDir(stateDir)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeSetStateDir 失败: ${e.message}")
        }

        // 初始化 mDNS 发现,并在内部订阅 servers Flow 推送到 _discoveredServers
        // clientPubkeyProvider 让探测 URL 带上客户端公钥,获取服务端侧 pair_status
        val ctx = context.applicationContext
        val discovery = MdnsDiscovery(ctx) { clientPubkeyB64() }
        mdnsDiscovery = discovery
        viewModelScope.launch {
            discovery.servers.collect { servers ->
                _discoveredServers.value = servers
                rebuildPcList()
            }
        }
        // 手动 PC 轮询(与 mDNS 三态轮询同权)
        startManualPolling()
    }

    /** 读取本客户端公钥(供 serverinfo pair_status 查询);失败返回空串 */
    private fun clientPubkeyB64(): String {
        return try {
            if (NativeBridge.isLoaded()) NativeBridge.nativeGetClientPubkeyB64() else ""
        } catch (e: UnsatisfiedLinkError) {
            ""
        }
    }

    /**
     * 启动 mDNS 自动发现(在 ConnectScreen 进入时调用)。
     * 必须在主线程调用(NsdManager 要求)。
     */
    fun startDiscovery() {
        mdnsDiscovery?.startDiscovery()
    }

    /**
     * 停止 mDNS 发现(在 ConnectScreen 离开时调用)。
     */
    fun stopDiscovery() {
        mdnsDiscovery?.stopDiscovery()
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

    /**
     * 在独立线程执行 nativeConnect 并带看门狗超时。
     * native 侧 TCP 连接超时 3s、握手超时 8s,正常情况下 12s 内必返回;
     * join 超时(15s)只是兜底,防止 native 线程意外卡死。
     *
     * @return native 返回码(0=失败,1=已连接,2=需配对,3=地址无效,4=不可达,5=被拒绝);null=看门狗超时
     */
    private suspend fun nativeConnectAttempt(addr: String, clientName: String): Int? =
        withContext(Dispatchers.Default) {
            val ref = java.util.concurrent.atomic.AtomicReference<Int?>(null)
            val thread = Thread {
                try {
                    ref.set(NativeBridge.nativeConnect(addr, clientName))
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Native 错误", e)
                    ref.set(0)
                }
            }
            thread.start()
            thread.join(15000)
            if (thread.isAlive) {
                thread.interrupt()
                null
            } else {
                ref.get()
            }
        }

    fun connect(serverAddr: String, clientName: String = "Android-Client") {
        if (_connectionState.value is ConnectionState.Connecting) return
        if (_pairingSubmitting.value) return

        // 地址规范化:裸 IP 自动补 :28900;无法解析则立即报错(不再盲目发起连接)
        val normalized = normalizeAddress(serverAddr)
        if (normalized == null) {
            _connectionState.value = ConnectionState.Error("地址格式无效,示例:192.168.1.12 或 192.168.1.12:28900")
            return
        }

        _connectionState.value = ConnectionState.Connecting
        _pairingRequired.value = null

        viewModelScope.launch(Dispatchers.IO) {
            if (!NativeBridge.isLoaded()) {
                _connectionState.value = ConnectionState.Error("libmeowmic.so 未加载")
                return@launch
            }

            val result = nativeConnectAttempt(normalized, clientName)
            handleConnectResult(result, normalized, clientName)
        }
    }

    /**
     * 连接 PC 列表条目:按地址优先级逐个尝试(借鉴 Moonlight 的多地址连接管线)
     */
    fun connectPc(entry: PcEntry, clientName: String = "Android-Client") {
        if (_connectionState.value is ConnectionState.Connecting) return
        if (_pairingSubmitting.value) return
        if (entry.addresses.isEmpty()) return

        _connectionState.value = ConnectionState.Connecting
        _pairingRequired.value = null

        viewModelScope.launch(Dispatchers.IO) {
            if (!NativeBridge.isLoaded()) {
                _connectionState.value = ConnectionState.Error("libmeowmic.so 未加载")
                return@launch
            }

            var lastResult: Int? = null
            for (addr in entry.addresses) {
                val result = nativeConnectAttempt(addr, clientName)
                // 成功 / 需要配对 / 地址无效:立即定案,不再尝试下一个地址
                if (result == 1 || result == 2 || result == 3 || result == null) {
                    handleConnectResult(result, addr, clientName)
                    return@launch
                }
                // 4=不可达 / 5=被拒绝 / 0=其它失败:尝试下一个地址
                lastResult = result
            }
            // 全部地址失败:有一个"被拒绝"就说明主机可达(服务未启动),否则都是不可达
            val finalAddr = entry.primaryAddr
            handleConnectResult(lastResult ?: 4, finalAddr, clientName)
        }
    }

    /** 统一处理 nativeConnect 结果(单地址与多地址管线共用) */
    private fun handleConnectResult(result: Int?, addr: String, clientName: String) {
        when (result) {
            null -> {
                _connectionState.value = ConnectionState.Error("连接超时,请检查地址或网络")
            }
            1 -> {
                // 已连接
                val audio = AudioCapture()
                if (!audio.init()) {
                    Log.w(TAG, "音频初始化失败,继续连接(仅触控)")
                } else {
                    audioCapture = audio
                }
                touchHandler = TouchHandler()
                _connectionState.value = ConnectionState.Connected(addr)
                saveHistory(addr)
                startStatsPolling()
            }
            2 -> {
                // 需要配对,暴露状态给 UI
                _connectionState.value = ConnectionState.Disconnected
                _pairingRequired.value = PairingRequiredState(addr, clientName)
            }
            3 -> {
                _connectionState.value = ConnectionState.Error("地址格式无效,示例:192.168.1.12 或 192.168.1.12:28900")
            }
            4 -> {
                _connectionState.value = ConnectionState.Error("无法连接到 PC(超时):请确认 PC 在线、与手机在同一网络")
            }
            5 -> {
                _connectionState.value = ConnectionState.Error("连接被拒绝:服务端未启动或端口错误(默认 28900)")
            }
            else -> {
                _connectionState.value = ConnectionState.Error("连接失败,检查地址或防火墙")
            }
        }
    }

    /**
     * 用户输入 PIN 后完成配对
     * @return true 表示配对成功并已连接
     */
    fun completePairing(pin: String) {
        val pending = _pairingRequired.value ?: return
        if (_pairingSubmitting.value) return

        _pairingSubmitting.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val result = withContext(Dispatchers.Default) {
                val pairResult = java.util.concurrent.atomic.AtomicReference<Int?>(null)
                val thread = Thread {
                    try {
                        pairResult.set(NativeBridge.nativeCompletePairing(pin))
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "Native 错误", e)
                        pairResult.set(0)
                    }
                }
                thread.start()
                thread.join(8000)
                if (thread.isAlive) {
                    thread.interrupt()
                    null
                } else {
                    pairResult.get()
                }
            }

            _pairingSubmitting.value = false

            if (result == null) {
                _connectionState.value = ConnectionState.Error("配对超时")
                _pairingRequired.value = null
                return@launch
            }

            if (result == 1) {
                // 配对成功并已连接
                _pairingRequired.value = null
                val audio = AudioCapture()
                if (!audio.init()) {
                    Log.w(TAG, "音频初始化失败,继续连接(仅触控)")
                } else {
                    audioCapture = audio
                }
                touchHandler = TouchHandler()
                _connectionState.value = ConnectionState.Connected(pending.serverAddr)
                saveHistory(pending.serverAddr)
                // 配对状态已变化,刷新列表徽标
                rebuildPcList()
                startStatsPolling()
            } else {
                // 配对失败:PIN 错误或其他原因,保持 pairingRequired 状态以便用户重试
                _connectionState.value = ConnectionState.Error("配对失败,PIN 错误或服务端拒绝")
            }
        }
    }

    /**
     * 取消配对(用户关闭 PIN 对话框)
     */
    fun cancelPairing() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NativeBridge.nativeCancelPairing()
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "nativeCancelPairing 失败: ${e.message}")
            }
            _pairingRequired.value = null
            _pairingSubmitting.value = false
            _connectionState.value = ConnectionState.Disconnected
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

    /** 连接到发现到的服务端(快捷方法) */
    fun connectDiscovered(server: DiscoveredServer, clientName: String = "Android-Client") {
        connect(server.addrString, clientName)
    }

    // ==================== PC 注册表 ====================

    /**
     * 手动添加 PC(地址经规范化;添加后立即进入轮询,无需连接成功)
     * @return true=已添加;false=地址无效
     */
    fun addManualPc(input: String): Boolean {
        val addr = normalizeAddress(input) ?: return false
        val current = _manualPcs.value
        // 已存在(按地址或身份)则只刷新,不重复添加
        if (current.any { addr in it.addresses }) {
            return true
        }
        val entry = PcEntry(
            id = "addr:$addr",
            name = addr,
            addresses = listOf(addr),
            status = ServerStatus.UNKNOWN,
            paired = null,
            manual = true,
        )
        _manualPcs.value = (current + entry).takeLast(MAX_MANUAL_PCS)
        persistManualPcs()
        rebuildPcList()
        return true
    }

    /** 移除手动添加的 PC(mDNS 发现的条目不可移除) */
    fun removeManualPc(id: String) {
        val current = _manualPcs.value
        if (current.none { it.id == id }) return
        _manualPcs.value = current.filterNot { it.id == id }
        persistManualPcs()
        rebuildPcList()
    }

    /**
     * 合并 mDNS 发现与手动添加,生成统一的 PC 列表。
     *
     * 合并键:优先服务端公钥(pk:),其次地址(addr:)——
     * 同一台 PC 换 IP 后,mDNS 解析出新地址,按公钥识别并合并,旧地址保留作后备。
     */
    private fun rebuildPcList() {
        val map = LinkedHashMap<String, PcEntry>()
        // 1. 手动 PC 打底
        for (e in _manualPcs.value) {
            map[e.id] = e
        }
        // 2. mDNS 发现合并
        for (s in _discoveredServers.value) {
            val pkId = s.pubkey.takeIf { it.isNotEmpty() }?.let { "pk:$it" }
            val matchKey = when {
                pkId != null && map.containsKey(pkId) -> pkId
                else -> map.entries.firstOrNull { s.addrString in it.value.addresses }?.key
            }
            if (matchKey == null) {
                val id = pkId ?: "addr:${s.addrString}"
                map[id] = PcEntry(
                    id = id,
                    name = s.name,
                    addresses = listOf(s.addrString),
                    status = s.status,
                    paired = s.paired ?: localPairedLookup(s.pubkey),
                    manual = false,
                )
            } else {
                val e = map.getValue(matchKey)
                // 身份升级:原条目按地址键控,现在拿到公钥 → 改按公钥键控
                val merged = e.copy(
                    name = s.name.ifBlank { e.name },
                    addresses = (listOf(s.addrString) + e.addresses).distinct(),
                    status = s.status,
                    paired = s.paired ?: e.paired,
                )
                if (pkId != null && matchKey != pkId) {
                    map.remove(matchKey)
                    map[pkId] = merged.copy(id = pkId)
                    // 手动条目的身份升级需要同步持久化
                    if (merged.manual) {
                        _manualPcs.value = _manualPcs.value.map {
                            if (it.id == matchKey) merged.copy(id = pkId) else it
                        }
                        persistManualPcs()
                    }
                } else {
                    map[matchKey] = merged
                }
            }
        }
        // 3. 排序:ONLINE > UNKNOWN > OFFLINE,同状态按名称
        _pcList.value = map.values.sortedWith(
            compareBy(
                {
                    when (it.status) {
                        ServerStatus.ONLINE -> 0
                        ServerStatus.UNKNOWN -> 1
                        ServerStatus.OFFLINE -> 2
                    }
                },
                { it.name.lowercase() },
            )
        )
    }

    /** 本地配对记录查询(服务端不支持 pair_status 时的兜底) */
    private fun localPairedLookup(serverPubkeyB64: String): Boolean? {
        if (serverPubkeyB64.isEmpty()) return null
        return try {
            if (NativeBridge.isLoaded()) NativeBridge.nativeIsServerPaired(serverPubkeyB64) else null
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    private fun loadManualPcs() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val raw = prefs.getString(KEY_MANUAL_PCS, null) ?: return
        try {
            val arr = JSONArray(raw)
            val list = mutableListOf<PcEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val addrArr = obj.getJSONArray("addresses")
                val addrs = mutableListOf<String>()
                for (j in 0 until addrArr.length()) addrs.add(addrArr.getString(j))
                if (addrs.isEmpty()) continue
                list.add(
                    PcEntry(
                        id = obj.getString("id"),
                        name = obj.optString("name", addrs.first()),
                        addresses = addrs,
                        status = ServerStatus.UNKNOWN, // 启动时未知,等轮询确认
                        paired = null,
                        manual = true,
                    )
                )
            }
            _manualPcs.value = list
            rebuildPcList()
        } catch (e: Exception) {
            Log.w(TAG, "解析手动 PC 列表失败: ${e.message}")
        }
    }

    private fun persistManualPcs() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        try {
            val arr = JSONArray()
            for (e in _manualPcs.value) {
                val obj = JSONObject()
                obj.put("id", e.id)
                obj.put("name", e.name)
                obj.put("addresses", JSONArray(e.addresses))
                arr.put(obj)
            }
            prefs.edit().putString(KEY_MANUAL_PCS, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "持久化手动 PC 列表失败: ${e.message}")
        }
    }

    /** 手动 PC 三态轮询(与 mDNS 轮询同权;地址按优先级逐个探测) */
    private fun startManualPolling() {
        if (manualPollJob?.isActive == true) return
        manualPollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val snapshot = _manualPcs.value
                for (entry in snapshot) {
                    var hitAddr: String? = null
                    var hitResult: ServerInfoProber.ServerInfoResult? = null
                    for (addr in entry.addresses) {
                        val r = ServerInfoProber.probe(serverInfoUrlOf(addr), clientPubkeyB64())
                        if (r != null) {
                            hitAddr = addr
                            hitResult = r
                            break
                        }
                    }
                    applyManualProbe(entry.id, hitAddr, hitResult)
                }
                delay(MANUAL_POLL_INTERVAL_MS)
            }
        }
    }

    /** 把一次手动 PC 探测结果写回注册表 */
    private fun applyManualProbe(
        entryId: String,
        hitAddr: String?,
        result: ServerInfoProber.ServerInfoResult?,
    ) {
        val current = _manualPcs.value
        val target = current.firstOrNull { it.id == entryId } ?: return
        val updated = if (result != null && hitAddr != null) {
            val newPkId = result.serverPubkeyB64.takeIf { it.isNotEmpty() }?.let { "pk:$it" }
            target.copy(
                id = newPkId ?: target.id,
                name = result.name.takeIf { it.isNotBlank() }
                    ?: result.hostname.takeIf { it.isNotBlank() }
                    ?: target.name,
                // 可达地址排到最前(下次连接/探测优先)
                addresses = (listOf(hitAddr) + target.addresses).distinct(),
                status = ServerStatus.ONLINE,
                paired = result.pairStatus ?: target.paired,
            )
        } else {
            target.copy(status = ServerStatus.OFFLINE)
        }
        if (updated == target) return
        if (updated.id != target.id) {
            // 身份升级(addr: → pk:)。若目标身份已存在(同一台 PC 的另一手动条目),
            // 合并地址并移除旧条目,避免列表出现重复身份
            val existing = current.firstOrNull { it.id == updated.id }
            _manualPcs.value = if (existing != null) {
                val merged = existing.copy(
                    addresses = (updated.addresses + existing.addresses).distinct(),
                    status = if (updated.status == ServerStatus.ONLINE) updated.status else existing.status,
                )
                current.map { if (it.id == updated.id) merged else it }
                    .filterNot { it.id == entryId }
            } else {
                current.map { if (it.id == entryId) updated else it }
            }
            persistManualPcs()
        } else {
            _manualPcs.value = current.map { if (it.id == entryId) updated else it }
        }
        rebuildPcList()
    }

    /** 由 "host:port"(control 端口)推导 serverinfo URL(port+4) */
    private fun serverInfoUrlOf(addr: String): String {
        val host = addr.substringBeforeLast(':')
        val port = addr.substringAfterLast(':').toIntOrNull() ?: 28900
        return "http://$host:${port + 4}/serverinfo"
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
        disconnect()
    }
}
