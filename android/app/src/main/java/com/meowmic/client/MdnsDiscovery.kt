package com.meowmic.client

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.jvm.Synchronized

/**
 * 服务端在线状态(三态轮询,参考 Moonlight)
 *
 * - [UNKNOWN]:刚发现,尚未确认可达(初始状态)
 * - [ONLINE]:HTTP /serverinfo 探测成功
 * - [OFFLINE]:连续探测失败次数达到阈值,或 mDNS onServiceLost
 */
enum class ServerStatus {
    UNKNOWN,
    ONLINE,
    OFFLINE,
}

/**
 * 发现到的 MeowMic 服务端
 *
 * @param serviceName mDNS 服务实例名(唯一,用于 onServiceLost 匹配)
 * @param name        显示名(来自 mDNS TXT 记录的 name 字段,用于 UI 展示)
 * @param host        服务端 IP(IPv4 优先)
 * @param port        control TCP 端口(touch/audio 自动推导为 port+1/port+2,
 *                    serverinfo HTTP 端口为 port+4)
 * @param status      当前在线状态(UNKNOWN/ONLINE/OFFLINE)
 * @param pubkey      服务端 Ed25519 公钥 base64(身份标识,类 Sunshine uniqueid;
 *                    来自 mDNS TXT 的 pk 字段或 /serverinfo 探测,可能为空=未知)
 * @param paired      本客户端是否已配对该服务端;null=未知(服务端未返回 pair_status)
 */
data class DiscoveredServer(
    val serviceName: String,
    val name: String,
    val host: String,
    val port: Int,
    val status: ServerStatus = ServerStatus.UNKNOWN,
    val pubkey: String = "",
    val paired: Boolean? = null,
) {
    /** 完整连接地址,形如 "192.168.1.100:28900",可直接传给 [MeowMicViewModel.connect] */
    val addrString: String get() = "$host:$port"

    /** serverinfo HTTP 探测 URL(control 端口 + 4) */
    val serverInfoUrl: String get() = "http://$host:${port + 4}/serverinfo"
}

/**
 * mDNS 自动发现 + 三态轮询(借鉴 Moonlight Android 的 NsdManager 方案)
 *
 * 监听局域网 `_meowmic._tcp.` 服务,自动发现运行 MeowMic 服务端的 PC。
 * 发现后周期性 HTTP 探测 `/serverinfo`,在 UNKNOWN/ONLINE/OFFLINE 间切换。
 * 发现结果通过 [servers] StateFlow 推送。
 *
 * 使用说明:
 * - 在 ConnectScreen 进入时调用 [startDiscovery]
 * - 离开时调用 [stopDiscovery]
 * - 监听 [servers] 获取实时列表(含状态)
 *
 * 线程:NsdManager 的 discoveryListener 回调在调用线程的 Looper 上触发,
 * 在主线程调用 [startDiscovery] 即可保证回调也在主线程。
 * 轮询协程在 IO 线程上执行 HTTP 请求。
 *
 * @param clientPubkeyProvider 提供本客户端公钥(base64)的回调,
 *        非空时探测 URL 附加 ?pubkey= 以获取服务端侧 pair_status
 */
class MdnsDiscovery(
    context: Context,
    private val clientPubkeyProvider: () -> String = { "" },
) {

    companion object {
        private const val TAG = "MeowMic/Mdns"
        /** 与 Rust crates/net/src/discovery.rs 的 SERVICE_TYPE 保持一致 */
        private const val SERVICE_TYPE = "_meowmic._tcp."
        /** 轮询间隔(参考 Moonlight,默认 3s) */
        private const val POLL_INTERVAL_MS = 3000L
        /** 连续失败次数达到此阈值则标记 OFFLINE */
        private const val OFFLINE_FAILURE_THRESHOLD = 2
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _servers = MutableStateFlow<Set<DiscoveredServer>>(emptySet())
    val servers: StateFlow<Set<DiscoveredServer>> = _servers.asStateFlow()

    /**
     * 已发现但尚未 resolve 完成的服务名集合,用于去重 resolve 请求
     * key = serviceName + serviceType
     */
    private val pendingResolve = ConcurrentHashMap<String, Boolean>()
    private var discovering = false

    /** 每个服务连续失败计数器,用于决定何时转 OFFLINE */
    private val failureCount = ConcurrentHashMap<String, AtomicInteger>()

    /** 轮询协程作用域,与 discovering 生命周期绑定 */
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.i(TAG, "mDNS 发现已启动: $serviceType")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "mDNS 发现已停止: $serviceType")
            _servers.value = emptySet()
            pendingResolve.clear()
            failureCount.clear()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "mDNS 启动失败 code=$errorCode")
            discovering = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "mDNS 停止失败 code=$errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            // 仅关心 _meowmic._tcp.(系统可能上报其它子类型)
            if (serviceInfo.serviceType != SERVICE_TYPE) return
            val key = serviceInfo.serviceName + serviceInfo.serviceType
            Log.d(TAG, "发现服务: ${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
            if (pendingResolve.putIfAbsent(key, true) != null) return
            resolveService(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "服务丢失: ${serviceInfo.serviceName}")
            // 用 serviceName 精确匹配移除(mDNS 丢失=确定离线)
            _servers.value = _servers.value.filterNot { it.serviceName == serviceInfo.serviceName }.toSet()
            pendingResolve.remove(serviceInfo.serviceName + serviceInfo.serviceType)
            failureCount.remove(serviceInfo.serviceName)
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                pendingResolve.remove(info.serviceName + info.serviceType)

                // IPv4 优先(与 Moonlight MdnsDiscoveryAgent 行为一致)
                val host = info.host ?: run {
                    Log.w(TAG, "resolve 成功但无 host: ${info.serviceName}")
                    return
                }
                // 如果有多个地址,NsdManager 旧版只返回第一个;够用
                val hostStr = host.hostAddress ?: return
                // 跳过回环和链路本地
                if (host.isLoopbackAddress) return

                // TXT 记录的 name 字段(优先),回退到 mDNS 实例名
                val name = info.attributes["name"]?.toString(Charsets.UTF_8) ?: info.serviceName
                // TXT 记录的 pk 字段:服务端身份公钥(发现即识别,无需等 HTTP 探测)
                val txtPubkey = info.attributes["pk"]?.toString(Charsets.UTF_8) ?: ""

                val server = DiscoveredServer(
                    serviceName = info.serviceName,
                    name = name,
                    host = hostStr,
                    port = info.port,
                    status = ServerStatus.UNKNOWN, // 初始未知,等轮询协程探测
                    pubkey = txtPubkey,
                )
                Log.i(TAG, "resolve 成功: $server")

                // 用 serviceName 作为唯一键去重(同一台机器的同一服务实例)
                val current = _servers.value.toMutableSet()
                current.removeAll { it.serviceName == server.serviceName }
                current.add(server)
                _servers.value = current
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                pendingResolve.remove(info.serviceName + info.serviceType)
                Log.w(TAG, "resolve 失败: ${info.serviceName} code=$errorCode")
            }
        }
        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: IllegalArgumentException) {
            // 同一 service 重复 resolve 会抛 IllegalArgumentException
            pendingResolve.remove(serviceInfo.serviceName + serviceInfo.serviceType)
            Log.d(TAG, "resolve 跳过(已在进行): ${serviceInfo.serviceName}")
        }
    }

    /**
     * 启动 mDNS 服务发现 + 状态轮询。
     * 可重复调用,内部有 discovering 标志防重入。
     */
    fun startDiscovery() {
        if (discovering) return
        discovering = true
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "启动 mDNS 发现异常", e)
            discovering = false
            return
        }
        // 启动轮询协程(lambda 内 this 是 CoroutineScope,可直接用 isActive / launch)
        if (pollJob == null || pollJob?.isActive != true) {
            pollJob = pollScope.launch { pollLoop() }
        }
    }

    /**
     * 停止发现并清空列表,同时取消轮询协程。
     */
    fun stopDiscovery() {
        if (discovering) {
            discovering = false
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: IllegalArgumentException) {
                // listener 未注册,忽略
                Log.d(TAG, "stopServiceDiscovery 未注册,忽略")
            }
        }
        pollJob?.cancel()
        pollJob = null
        _servers.value = emptySet()
        pendingResolve.clear()
        failureCount.clear()
    }

    /**
     * 轮询循环:周期性探测所有已发现服务(UNKNOWN/ONLINE)的 /serverinfo 端点
     *
     * - 探测成功 → status=ONLINE,失败计数清零
     * - 探测失败 → 失败计数+1,达到阈值则 status=OFFLINE
     * - OFFLINE 的服务仍然保留在列表中,以便 UI 显示"离线"
     *   (服务真正消失会由 onServiceLost 移除)
     *
     * 注意:本函数作为 [CoroutineScope] 的扩展,确保在 launch{} 中调用时
     * 能直接访问 `isActive` 和 `launch`。
     */
    private suspend fun CoroutineScope.pollLoop() {
        while (discovering && isActive) {
            val snapshot = _servers.value
            if (snapshot.isEmpty()) {
                delay(POLL_INTERVAL_MS)
                continue
            }
            // 并行探测所有 UNKNOWN/ONLINE 的服务(OFFLINE 不再轮询,等 mDNS 重新发现)
            val toProbe = snapshot.filter { it.status != ServerStatus.OFFLINE }
            for (server in toProbe) {
                launch {
                    probeOnce(server)
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun probeOnce(server: DiscoveredServer) {
        val result = withContext(Dispatchers.IO) {
            ServerInfoProber.probe(server.serverInfoUrl, clientPubkeyProvider())
        }

        // 更新状态(原子地与其它探测结果合并)
        // 成功时同步刷新 name/pubkey/paired(服务端身份与配对状态可能变化)
        updateStatus(server.serviceName) { current ->
            if (result != null) {
                failureCount.remove(server.serviceName)
                current.copy(
                    status = ServerStatus.ONLINE,
                    name = result.name.takeIf { it.isNotBlank() } ?: current.name,
                    pubkey = result.serverPubkeyB64.takeIf { it.isNotBlank() } ?: current.pubkey,
                    paired = result.pairStatus ?: current.paired,
                )
            } else {
                val count = failureCount
                    .computeIfAbsent(server.serviceName) { AtomicInteger(0) }
                    .incrementAndGet()
                if (count >= OFFLINE_FAILURE_THRESHOLD) {
                    current.copy(status = ServerStatus.OFFLINE)
                } else {
                    // 仍然保持原状态(若原本 ONLINE,短暂失败不立刻切走)
                    current
                }
            }
        }
    }

    /**
     * 原子更新某个 serviceName 对应的 server,并推送到 StateFlow
     *
     * @param transform 接收当前 server,返回更新后的 server(若返回值与原值相同则不会触发更新)
     * @return 更新后的 server,若服务已被移除则返回 null
     */
    @Synchronized private fun updateStatus(
        serviceName: String,
        transform: (DiscoveredServer) -> DiscoveredServer,
    ): DiscoveredServer? {
        val current = _servers.value
        val target = current.firstOrNull { it.serviceName == serviceName } ?: return null
        val newServer = transform(target)
        if (newServer == target) return target
        val updated = current.toMutableSet()
        updated.removeAll { it.serviceName == serviceName }
        updated.add(newServer)
        _servers.value = updated
        return newServer
    }
}
