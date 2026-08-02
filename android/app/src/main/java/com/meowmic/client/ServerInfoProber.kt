package com.meowmic.client

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * /serverinfo HTTP 探测器(MdnsDiscovery 与手动 PC 轮询共用)
 *
 * 借鉴 Moonlight 的 serverinfo 轮询:周期性 GET /serverinfo 判断 ONLINE/OFFLINE。
 * 扩展:URL 带 `?pubkey=<客户端公钥>` 时,服务端额外返回 `pair_status`
 * (该客户端是否已配对,参考 Sunshine 的 PairStatus)。
 */
object ServerInfoProber {

    private const val TAG = "MeowMic/Prober"

    const val DEFAULT_CONNECT_TIMEOUT_MS = 1500
    const val DEFAULT_READ_TIMEOUT_MS = 2000

    /**
     * @param name             服务实例名
     * @param hostname         主机名
     * @param version          协议版本
     * @param state            服务端状态(ONLINE)
     * @param connectedClients 当前连接数
     * @param maxClients       最大连接数
     * @param uptimeSecs       服务端启动时长
     * @param serverPubkeyB64  服务端 Ed25519 公钥(身份标识,类 Sunshine uniqueid)
     * @param pairStatus       客户端是否已配对;null=服务端未返回(旧版本或未带 pubkey 查询)
     * @param mac              服务端网卡 MAC 地址列表(供 Wake-on-LAN;旧版本服务端无此字段=空)
     */
    data class ServerInfoResult(
        val name: String,
        val hostname: String,
        val version: Int,
        val state: String,
        val connectedClients: Int,
        val maxClients: Int,
        val uptimeSecs: Long,
        val serverPubkeyB64: String,
        val pairStatus: Boolean?,
        val mac: List<String> = emptyList(),
    )

    /**
     * 探测一次 /serverinfo。
     *
     * @param serverInfoUrl   形如 "http://192.168.1.12:28904/serverinfo"
     * @param clientPubkeyB64 客户端公钥(base64);非空时附加 ?pubkey= 查询 pair_status
     * @return 成功返回 [ServerInfoResult],任何失败返回 null
     */
    fun probe(
        serverInfoUrl: String,
        clientPubkeyB64: String = "",
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    ): ServerInfoResult? {
        val url = if (clientPubkeyB64.isNotEmpty()) {
            "$serverInfoUrl?pubkey=${URLEncoder.encode(clientPubkeyB64, "UTF-8")}"
        } else {
            serverInfoUrl
        }
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                useCaches = false
                instanceFollowRedirects = false
            }
            conn.connect()
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parse(body)
                } else {
                    Log.w(TAG, "serverinfo HTTP ${conn.responseCode} for $serverInfoUrl")
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "serverinfo 探测失败 $serverInfoUrl: ${e.message}")
            null
        }
    }

    private fun parse(body: String): ServerInfoResult? {
        return try {
            val json = JSONObject(body)
            ServerInfoResult(
                name = json.optString("name", ""),
                hostname = json.optString("hostname", ""),
                version = json.optInt("version", 1),
                state = json.optString("state", "ONLINE"),
                connectedClients = json.optInt("connected_clients", 0),
                maxClients = json.optInt("max_clients", 1),
                uptimeSecs = json.optLong("uptime_secs", 0),
                serverPubkeyB64 = json.optString("server_pubkey_b64", ""),
                // 字段存在与否比 true/false 更重要:老版本服务端不返回该字段
                pairStatus = if (json.has("pair_status")) json.optBoolean("pair_status") else null,
                mac = json.optJSONArray("macs")?.let { arr ->
                    (0 until arr.length())
                        .map { arr.optString(it) }
                        .filter { it.isNotBlank() }
                } ?: emptyList(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析 serverinfo 失败: ${e.message}")
            null
        }
    }
}
