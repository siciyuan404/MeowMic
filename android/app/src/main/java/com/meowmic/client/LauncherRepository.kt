package com.meowmic.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
 * PC 端目录浏览条目(用于 exe 路径选择)
 */
data class DirEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isExe: Boolean,
)

/**
 * 单个运行中窗口信息(任务栏功能)
 *
 * @param hwnd   窗口句柄(十进制字符串,作为 focus/close 的稳定标识;PC 端 u64)
 * @param title  窗口标题
 * @param isActive 是否为当前前台窗口
 */
data class WindowInfo(
    val hwnd: Long,
    val title: String,
    val isActive: Boolean,
)

/**
 * 运行中应用(按 exe 路径分组,任务栏功能)
 *
 * @param name     显示名(exe 文件名去后缀,首字母大写)
 * @param exePath  进程可执行文件绝对路径(用作客户端图标缓存键)
 * @param windows  该进程拥有的窗口列表
 */
data class RunningApp(
    val name: String,
    val exePath: String,
    val windows: List<WindowInfo>,
)

/**
 * 添加应用的结果(包含错误详情,便于 UI 提示)
 */
sealed class AddAppResult {
    data class Success(val appId: String) : AddAppResult()
    data class HttpError(val code: Int, val body: String) : AddAppResult()
    data class Exception(val message: String) : AddAppResult()
}

/**
 * 目录浏览结果
 */
data class DirListing(
    val current: String,
    val parent: String?,
    val items: List<DirEntry>,
)

/**
 * 拉取应用库失败的结构化错误分类(随 [AppListFetchException.kind] 抛出,便于 UI 给出人性化提示)。
 *
 * 分类:
 * - [NotPaired403]:HTTP 403 + body 含 "not paired" → 第二台手机尚未在 PC 配对弹窗点「同意」
 * - [Forbidden403]:其他 403(比如未带 pubkey,或服务端鉴权异常)
 * - [HttpError]:非 200/非 403 的 HTTP 错误(如 500)
 * - [Network]:IOException(连接失败/超时/DNS 解析失败等)
 * - [ParseError]:HTTP 200 但 JSON 解析失败/不是合法 List<AppEntry>
 */
sealed class AppListFetchKind {
    data class NotPaired403(val body: String) : AppListFetchKind()
    data class Forbidden403(val body: String) : AppListFetchKind()
    data class HttpError(val code: Int, val body: String) : AppListFetchKind()
    data class Network(val message: String) : AppListFetchKind()
    data class ParseError(val message: String, val raw: String) : AppListFetchKind()
}

/** 包装 [AppListFetchKind] 的异常,供 Kotlin Result.failure() 携带结构化错误。 */
class AppListFetchException(
    val kind: AppListFetchKind,
    message: String? = kind.toString(),
    cause: Throwable? = null,
) : Exception(message, cause)

/** 读取 HTTP 响应 body(错误优先, 兼容重定向/非 2xx 路径);读取失败返回空字符串。 */
private fun java.net.HttpURLConnection.readBodySafe(): String {
    val stream = try { this.errorStream ?: this.inputStream } catch (_: Exception) { null }
    return if (stream == null) ""
    else try { stream.bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
}

/**
 * 快捷启动 HTTP 客户端
 *
 * 端点(挂在 base_port+4,与 /serverinfo 同服务,复用配对鉴权):
 * - GET  /applist?pubkey=<b64>              返回应用库 JSON 数组
 * - GET  /app_icon?id=<app_id>&pubkey=<b64> 返回 exe 图标 PNG
 * - POST /launch?id=<app_id>&pubkey=<b64>   启动指定应用
 * - GET  /running_apps?pubkey=<b64>         返回运行中应用窗口列表(任务栏)
 * - GET  /exe_icon?path=<exe_path>&pubkey=<b64>  返回 exe 图标 PNG(任务栏,按路径提取)
 * - POST /focus_window?hwnd=<n>&pubkey=<b64>     前台激活指定窗口
 * - POST /close_window?hwnd=<n>&pubkey=<b64>     优雅关闭指定窗口(WM_CLOSE)
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

    private fun encodeParam(s: String): String =
        // URLEncoder.encode 把空格编码为 '+',但 PC 端 url_decode 不解码 '+'
        // (base64 的 '+' 应保留)。改用 %20 编码空格,与服务端兼容。
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * 拉取 PC 端应用库。
     * @param serverAddr "host:port"(control 端口)
     * @param pubkey 客户端公钥 base64(用于配对鉴权)
     * @return 应用列表;失败返回 null
     */
    /**
     * 拉取 PC 端应用库。
     * @param serverAddr "host:port"(control 端口)
     * @param pubkey 客户端公钥 base64(用于配对鉴权)
     * @return 成功: Result.success(应用列表);
     *         失败: Result.failure([AppListFetchException]),其 [AppListFetchException.kind] 给出结构化分类。
     */
    suspend fun fetchAppList(
        serverAddr: String,
        pubkey: String,
    ): Result<List<AppEntry>> =
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
                    val code = conn.responseCode
                    if (code == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val parsed = parseAppList(body)
                        if (parsed != null) {
                            Result.success(parsed)
                        } else {
                            Result.failure(
                                AppListFetchException(
                                    AppListFetchKind.ParseError("parseAppList returned null", body),
                                ),
                            )
                        }
                    } else {
                        val body = conn.readBodySafe()
                        Log.w(TAG, "fetchAppList HTTP $code body=$body")
                        val kind = when (code) {
                            403 -> {
                                val hasNotPaired = body.contains(""not paired"")
                                    || body.contains("not paired")
                                if (hasNotPaired)
                                    AppListFetchKind.NotPaired403(body)
                                else
                                    AppListFetchKind.Forbidden403(body)
                            }
                            else -> AppListFetchKind.HttpError(code, body)
                        }
                        Result.failure(AppListFetchException(kind))
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: java.net.ConnectException) {
                Log.w(TAG, "fetchAppList connect: ${e.message}")
                Result.failure(
                    AppListFetchException(
                        AppListFetchKind.Network("连接失败:${e.message ?: ""}"),
                        cause = e,
                    ),
                )
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "fetchAppList timeout: ${e.message}")
                Result.failure(
                    AppListFetchException(
                        AppListFetchKind.Network("连接超时:${e.message ?: ""}"),
                        cause = e,
                    ),
                )
            } catch (e: java.io.IOException) {
                Log.w(TAG, "fetchAppList IO: ${e.message}")
                Result.failure(
                    AppListFetchException(
                        AppListFetchKind.Network("网络错误:${e.message ?: ""}"),
                        cause = e,
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "fetchAppList 失败: ${e.message}")
                Result.failure(
                    AppListFetchException(
                        AppListFetchKind.Network("未知错误:${e.message ?: ""}"),
                        cause = e,
                    ),
                )
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

    /**
     * 添加自定义应用到 PC 端应用库。
     * @param name 应用显示名
     * @param command 可执行文件路径(支持 %APPDATA% 等环境变量)
     * @return AddAppResult 包含成功 appId 或具体错误信息
     */
    suspend fun addApp(serverAddr: String, name: String, command: String, pubkey: String): AddAppResult =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/add_app?pubkey=${encodeParam(pubkey)}"
            try {
                val payload = JSONObject().apply {
                    put("name", name)
                    put("command", command)
                    put("args", JSONArray())
                    put("working_dir", "")
                }.toString()
                val payloadBytes = payload.toByteArray(Charsets.UTF_8)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "POST"
                    useCaches = false
                    instanceFollowRedirects = false
                    doOutput = true
                    setFixedLengthStreamingMode(payloadBytes.size)
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    conn.outputStream.use { it.write(payloadBytes) }
                    val code = conn.responseCode
                    if (code == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val appId = JSONObject(body).optString("id").ifBlank { null }
                        if (appId != null) {
                            AddAppResult.Success(appId)
                        } else {
                            AddAppResult.HttpError(200, "响应缺少 id: $body")
                        }
                    } else {
                        val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        Log.w(TAG, "addApp HTTP $code: $errBody")
                        AddAppResult.HttpError(code, errBody)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "addApp 失败: ${e.message}")
                AddAppResult.Exception(e.message ?: "未知错误")
            }
        }

    /**
     * 浏览 PC 端目录(用于 exe 路径选择)。
     * @param path 目录路径(空字符串表示根/盘符列表)
     * @return 目录列表;失败返回 null
     */
    suspend fun listDir(serverAddr: String, path: String, pubkey: String): DirListing? =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/list_dir?path=${encodeParam(path)}&pubkey=${encodeParam(pubkey)}"
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
                        parseDirListing(body)
                    } else {
                        Log.w(TAG, "listDir HTTP ${conn.responseCode}")
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "listDir 失败: ${e.message}")
                null
            }
        }

    private fun parseDirListing(body: String): DirListing? {
        return try {
            val obj = JSONObject(body)
            val current = obj.optString("current")
            val parent = obj.opt("parent") as? String
            val itemsArr = obj.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsArr.length()).mapNotNull { i ->
                val item = itemsArr.optJSONObject(i) ?: return@mapNotNull null
                DirEntry(
                    name = item.optString("name"),
                    path = item.optString("path"),
                    isDir = item.optBoolean("is_dir"),
                    isExe = item.optBoolean("is_exe"),
                )
            }
            DirListing(current = current, parent = parent, items = items)
        } catch (e: Exception) {
            Log.w(TAG, "解析 dir listing 失败: ${e.message}")
            null
        }
    }

    // ============ 任务栏:运行中应用窗口管理 ============

    /**
     * 拉取 PC 端运行中应用窗口列表(按 exe 路径分组)。
     * 用于底部任务栏展示:每组对应一个应用图标,可能含多个窗口。
     *
     * @return 应用列表;失败返回 null
     */
    suspend fun fetchRunningApps(serverAddr: String, pubkey: String): List<RunningApp>? =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/running_apps?pubkey=${encodeParam(pubkey)}"
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
                        parseRunningApps(body)
                    } else {
                        Log.w(TAG, "fetchRunningApps HTTP ${conn.responseCode}")
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchRunningApps 失败: ${e.message}")
                null
            }
        }

    private fun parseRunningApps(body: String): List<RunningApp>? {
        return try {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("name", "")
                val exePath = obj.optString("exe_path", "")
                val winArr = obj.optJSONArray("windows") ?: JSONArray()
                val windows = (0 until winArr.length()).mapNotNull { j ->
                    val w = winArr.optJSONObject(j) ?: return@mapNotNull null
                    val hwnd = w.optLong("hwnd", 0)
                    if (hwnd == 0L) return@mapNotNull null
                    WindowInfo(
                        hwnd = hwnd,
                        title = w.optString("title", ""),
                        isActive = w.optBoolean("is_active", false),
                    )
                }
                if (windows.isEmpty()) return@mapNotNull null
                RunningApp(name = name, exePath = exePath, windows = windows)
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 running_apps 失败: ${e.message}")
            null
        }
    }

    /**
     * 拉取 exe 图标 PNG(按 exe 路径提取,用于任务栏应用图标)。
     * 客户端按 exePath 缓存,避免重复请求。
     *
     * @param exePath 进程可执行文件绝对路径(Windows 反斜杠路径)
     * @return 解码后的 Bitmap;失败返回 null
     */
    suspend fun fetchExeIcon(serverAddr: String, exePath: String, pubkey: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/exe_icon?path=${encodeParam(exePath)}&pubkey=${encodeParam(pubkey)}"
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
                            Log.d(TAG, "fetchExeIcon HTTP ${conn.responseCode}")
                            null
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "fetchExeIcon 失败: ${e.message}")
                null
            }
        }

    /**
     * 前台激活指定窗口(模拟任务栏点击)。
     * @return true 表示 PC 已接受请求
     */
    suspend fun focusWindow(serverAddr: String, hwnd: Long, pubkey: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/focus_window?hwnd=$hwnd&pubkey=${encodeParam(pubkey)}"
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
                Log.w(TAG, "focusWindow 失败: ${e.message}")
                false
            }
        }

    /**
     * 优雅关闭指定窗口(发送 WM_CLOSE)。
     * @return true 表示 PC 已接受请求
     */
    suspend fun closeWindow(serverAddr: String, hwnd: Long, pubkey: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/close_window?hwnd=$hwnd&pubkey=${encodeParam(pubkey)}"
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
                Log.w(TAG, "closeWindow 失败: ${e.message}")
                false
            }
        }
}
