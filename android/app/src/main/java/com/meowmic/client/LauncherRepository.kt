package com.meowmic.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 快捷启动应用库条目(从 PC 端 /applist 拉取)
 *
 * 仅保留 UI 所需字段;command/args/working_dir 由 PC 端持有,客户端不感知。
 */
data class AppEntry(
    val id: String,
    val name: String,
)

/**
 * 快捷启动 HTTP 客户端
 *
 * 端点(挂在 base_port+4,与 /serverinfo 同服务,复用配对鉴权):
 * - GET  /applist?pubkey=<b64>              返回应用库 JSON 数组
 * - GET  /app_icon?id=<app_id>&pubkey=<b64> 返回 exe 图标 PNG
 * - POST /launch?id=<app_id>&pubkey=<b64>   启动指定应用
 *
 * 鉴权:query 必须带 pubkey=<客户端公钥b64>,且该 pubkey 必须已配对,否则 403。
 * 参考 Sunshine/Moonlight 的 applist + launch API 形态。
 */
object LauncherRepository {
    private const val TAG = "LauncherRepo"
    private const val CONNECT_TIMEOUT_MS = 2000
    private const val READ_TIMEOUT_MS = 5000

    /**
     * 从 serverAddr("host:port") 解析出 base_port+4 的完整 URL。
     * port 是 control 端口(base_port),+4 得到 serverinfo HTTP 端口。
     */
    private fun httpBaseUrl(serverAddr: String): String {
        val host = serverAddr.substringBeforeLast(':')
        val port = serverAddr.substringAfterLast(':').toIntOrNull() ?: 28900
        return "http://$host:${port + 4}"
    }

    private fun encodeParam(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * 拉取 PC 端应用库。
     * @param serverAddr "host:port"(control 端口)
     * @param pubkey 客户端公钥 base64(用于配对鉴权)
     * @return 应用列表;失败返回 null
     */
    suspend fun fetchAppList(serverAddr: String, pubkey: String): List<AppEntry>? =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/applist?pubkey=${encodeParam(pubkey)}"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    useCaches = false
                    instanceFollowRedirects = false
                }
                try {
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        parseAppList(body)
                    } else {
                        Log.w(TAG, "fetchAppList HTTP ${conn.responseCode}")
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchAppList 失败: ${e.message}")
                null
            }
        }

    private fun parseAppList(body: String): List<AppEntry>? {
        return try {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id", "")
                val name = obj.optString("name", "")
                if (id.isNotBlank()) AppEntry(id, name) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 applist 失败: ${e.message}")
            null
        }
    }

    /**
     * 拉取应用图标(PNG)。
     * @return 解码后的 Bitmap;失败返回 null
     */
    suspend fun fetchIcon(serverAddr: String, appId: String, pubkey: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/app_icon?id=${encodeParam(appId)}&pubkey=${encodeParam(pubkey)}"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    useCaches = false
                    instanceFollowRedirects = false
                }
                try {
                    when (conn.responseCode) {
                        200 -> {
                            val bytes = conn.inputStream.use { it.readBytes() }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        else -> {
                            Log.d(TAG, "fetchIcon HTTP ${conn.responseCode} for $appId")
                            null
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "fetchIcon 失败 $appId: ${e.message}")
                null
            }
        }

    /**
     * 触发 PC 端启动指定应用。
     * @return true 表示 PC 已接受启动请求;false 表示失败
     */
    suspend fun launchApp(serverAddr: String, appId: String, pubkey: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/launch?id=${encodeParam(appId)}&pubkey=${encodeParam(pubkey)}"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "POST"
                    useCaches = false
                    instanceFollowRedirects = false
                    setFixedLengthStreamingMode(0)
                }
                try {
                    conn.responseCode == 200
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "launchApp 失败 $appId: ${e.message}")
                false
            }
        }
}
