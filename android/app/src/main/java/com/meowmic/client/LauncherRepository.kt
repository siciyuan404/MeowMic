package com.meowmic.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

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
 * Quick-launch tile type (v2 free-layout)
 *
 * - APP:      PC app from /applist (launch via /launch?id=<app_id>)
 * - SCRIPT:   local script (.bat/.cmd/.ps1, wrapped with cmd or powershell)
 * - WEBSITE:  URL (opened via explorer.exe with default browser)
 * - OBSIDIAN: obsidian:// URI (opened via explorer.exe with Obsidian)
 */
enum class QuickItemType(val label: String) {
    APP("\u5E94\u7528"),
    SCRIPT("\u811A\u672C"),
    WEBSITE("\u7F51\u7AD9"),
    OBSIDIAN("Obsidian");
}

/**
 * Quick-launch tile (free layout)
 *
 * @param id     client-side unique id (for persistence + drag, "qi_" prefix)
 * @param type   tile type, drives icon and badge dot color
 * @param name   display name
 * @param appId  PC app library id (all types launch via /launch?id=<appId>)
 * @param page   page index (0-based)
 * @param col    column (1-based, matches design grid-column)
 * @param row    row (1-based, matches design grid-row)
 */
data class QuickItem(
    val id: String,
    val type: QuickItemType,
    val name: String,
    val appId: String,
    val page: Int,
    val col: Int,
    val row: Int,
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

/**
 * 快捷启动应用库拉取结果(自定义 sealed class)。
 *
 * 不用 Kotlin 内置的 `kotlin.Result<T>` 作为 suspend 函数返回值:
 * 它在 Kotlin 1.9 中仍是 value class,作为公开函数返回类型时可能触发编译错误
 * ("Result is not allowed as return type"). 因此自定义显式 sealed class,更清晰也更兼容。
 */
sealed class AppListResult {
    /** 成功:返回已解析的应用列表 */
    data class Success(val list: List<AppEntry>) : AppListResult()
    /** 失败:包装结构化异常,其 [AppListFetchException.kind] 给出错误分类 */
    data class Failure(val exception: AppListFetchException) : AppListResult()
}

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
     * 计算 ByteArray 的 SHA-256(hex 小写),用于文件传输完整性校验。
     * 与 PC 端 files::sha256_file 算法一致,两端 hash 可直接比对。
     */
    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 网络重试包装:仅对 [IOException](超时/连接重置等临时故障)重试,
     * HTTP 4xx/5xx 业务错误不重试(确定性错误,重试无意义)。
     * 默认 3 次,退避 500ms 起步每次加倍。
     */
    private suspend fun <T> retryIO(
        maxAttempts: Int = 3,
        baseBackoffMs: Long = 500L,
        block: suspend () -> T,
    ): T {
        var lastErr: IOException? = null
        var backoff = baseBackoffMs
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: IOException) {
                lastErr = e
                Log.w(TAG, "retryIO 第 ${attempt + 1}/$maxAttempts 次失败: ${e.message}")
                if (attempt < maxAttempts - 1) {
                    delay(backoff)
                    backoff *= 2
                }
            }
        }
        throw lastErr ?: IOException("retryIO exhausted")
    }

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
    ): AppListResult =
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
                            AppListResult.Success(parsed)
                        } else {
                            AppListResult.Failure(
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
                                val hasNotPaired = body.contains("not paired")
                                    || body.contains("\"not paired\"")
                                if (hasNotPaired)
                                    AppListFetchKind.NotPaired403(body)
                                else
                                    AppListFetchKind.Forbidden403(body)
                            }
                            else -> AppListFetchKind.HttpError(code, body)
                        }
                        AppListResult.Failure(AppListFetchException(kind))
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: java.net.ConnectException) {
                Log.w(TAG, "fetchAppList connect: ${e.message}")
                AppListResult.Failure(
                    AppListFetchException(
                        AppListFetchKind.Network("连接失败:${e.message ?: ""}"),
                        cause = e,
                    ),
                )
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "fetchAppList timeout: ${e.message}")
                AppListResult.Failure(
                    AppListFetchException(
                        AppListFetchKind.Network("连接超时:${e.message ?: ""}"),
                        cause = e,
                    ),
                )
            } catch (e: java.io.IOException) {
                Log.w(TAG, "fetchAppList IO: ${e.message}")
                AppListResult.Failure(
                    AppListFetchException(
                        AppListFetchKind.Network("网络错误:${e.message ?: ""}"),
                        cause = e,
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "fetchAppList 失败: ${e.message}")
                AppListResult.Failure(
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
    suspend fun addApp(
        serverAddr: String,
        name: String,
        command: String,
        pubkey: String,
        args: List<String> = emptyList(),
        workingDir: String = "",
    ): AddAppResult =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/add_app?pubkey=${encodeParam(pubkey)}"
            try {
                val payload = JSONObject().apply {
                    put("name", name)
                    put("command", command)
                    put("args", JSONArray(args))
                    put("working_dir", workingDir)
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

    // ════════════════════════════════════════════════════════════════
    // 远程显示器(screen)端点
    // ════════════════════════════════════════════════════════════════

    /** 屏幕分辨率 */
    data class ScreenInfo(val width: Int, val height: Int)

    /** 获取屏幕分辨率 */
    suspend fun fetchScreenInfo(serverAddr: String, pubkey: String): ScreenInfo? =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/screen/info?pubkey=${encodeParam(pubkey)}"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }
                try {
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        ScreenInfo(json.optInt("width"), json.optInt("height"))
                    } else null
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "fetchScreenInfo 失败: ${e.message}")
                null
            }
        }

    // ════════════════════════════════════════════════════════════════
    // 文件传输(file)端点
    // ════════════════════════════════════════════════════════════════

    /**
     * 文件传输失败原因分类。UI 据此决定是否提供"重试"按钮:
     * NETWORK_ERROR / HASH_MISMATCH / SERVER_ERROR 可重试;
     * NOT_PAIRED / PATH_INVALID / TOO_LARGE 不可重试。
     */
    enum class FailureReason {
        NOT_PAIRED,
        PATH_INVALID,
        HASH_MISMATCH,
        SERVER_ERROR,
        NETWORK_ERROR,
        TOO_LARGE,
        UNKNOWN,
    }

    /**
     * 文件传输结果。
     * - [UploadSuccess]: 上传成功,serverHash 为服务端回写的 SHA-256(若有)
     * - [DownloadSuccess]: 下载成功,verified=true 表示已通过 hash 比对
     * - [Failed]: 失败,reason 给出结构化分类,message 用于 UI 显示
     */
    sealed class FileTransferResult {
        data class UploadSuccess(val serverHash: String?) : FileTransferResult()
        data class DownloadSuccess(val data: ByteArray, val verified: Boolean) : FileTransferResult()
        data class Failed(val reason: FailureReason, val message: String) : FileTransferResult()
    }

    /** 在 retryIO 内抛出的"非网络"中止异常(4xx/5xx),让 retryIO 不重试直接结束。 */
    private class HashVerifyAbortException(
        val reason: FailureReason,
        message: String,
    ) : RuntimeException(message)

    /** 读取 HttpURLConnection 响应体(200 走 inputStream,否则走 errorStream;失败返回空串)。 */
    private fun HttpURLConnection.readRespBody(): String =
        try {
            (if (responseCode == 200) inputStream else errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            ""
        }

    /** 文件条目(用于文件传输页列表) */
    data class FileEntry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val modified: Long,
        val readonly: Boolean,
    )

    /** 目录浏览结果(用于文件传输页) */
    data class FileListing(
        val current: String,
        val parent: String?,
        val items: List<FileEntry>,
    )

    /** 流式播放 URL(PC 视频文件,配合 ExoPlayer;服务端 /file/stream 支持 HTTP Range,可拖动进度) */
    fun streamUrl(serverAddr: String, path: String, pubkey: String): String =
        "${httpBaseUrl(serverAddr)}/file/stream?path=${encodeParam(path)}&pubkey=${encodeParam(pubkey)}"

    /** 列出目录下所有文件 */
    suspend fun listFiles(serverAddr: String, path: String, pubkey: String): FileListing? =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/file/list?path=${encodeParam(path)}&pubkey=${encodeParam(pubkey)}"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }
                try {
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val arr = json.optJSONArray("items") ?: return@withContext FileListing(
                            json.optString("current"),
                            json.optString("parent").takeIf { it.isNotBlank() },
                            emptyList(),
                        )
                        val items = (0 until arr.length()).mapNotNull { i ->
                            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                            FileEntry(
                                name = obj.optString("name"),
                                path = obj.optString("path"),
                                isDir = obj.optBoolean("is_dir"),
                                size = obj.optLong("size"),
                                modified = obj.optLong("modified"),
                                readonly = obj.optBoolean("readonly"),
                            )
                        }
                        FileListing(
                            json.optString("current"),
                            json.optString("parent").takeIf { it.isNotBlank() },
                            items,
                        )
                    } else null
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "listFiles 失败: ${e.message}")
                null
            }
        }

    /**
     * 拉取 PC 端文件 SHA-256(用于上传后/下载前比对完整性)。
     * @return hex 字符串;失败返回 null(不阻断主流程)
     */
    suspend fun fetchFileHash(serverAddr: String, path: String, pubkey: String): String? =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/file/hash?path=${encodeParam(path)}&pubkey=${encodeParam(pubkey)}"
            try {
                retryIO {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        useCaches = false
                    }
                    try {
                        if (conn.responseCode == 200) {
                            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                                .optString("hash").takeIf { it.isNotBlank() }
                        } else {
                            Log.w(TAG, "fetchFileHash HTTP ${conn.responseCode}")
                            null
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "fetchFileHash 重试用尽: ${e.message}")
                null
            }
        }

    /** 下载文件字节流(向后兼容入口,无 hash 校验)。新代码应优先 [downloadFileVerified]。 */
    suspend fun downloadFile(serverAddr: String, path: String, pubkey: String): ByteArray? =
        when (val r = downloadFileVerified(serverAddr, path, pubkey)) {
            is FileTransferResult.DownloadSuccess -> r.data
            else -> null
        }

    /**
     * 下载文件并做完整性校验:
     * 1. /file/hash 拉取服务端 SHA-256(retryIO 抗网络抖动)
     * 2. 下载字节流(retryIO)
     * 3. 本地计算 SHA-256 比对,不一致返回 HASH_MISMATCH(可重试)
     */
    suspend fun downloadFileVerified(
        serverAddr: String,
        path: String,
        pubkey: String,
    ): FileTransferResult = withContext(Dispatchers.IO) {
        val expectedHash = fetchFileHash(serverAddr, path, pubkey)
        val data: ByteArray = try {
            retryIO {
                val url = "${httpBaseUrl(serverAddr)}/file/download?path=${encodeParam(path)}&pubkey=${encodeParam(pubkey)}"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = FILE_READ_TIMEOUT_MS
                }
                try {
                    val code = conn.responseCode
                    when {
                        code == 200 -> conn.inputStream.use { it.readBytes() }
                        code == 403 -> throw HashVerifyAbortException(
                            FailureReason.NOT_PAIRED, "未配对,无法访问文件",
                        )
                        code == 400 -> throw HashVerifyAbortException(
                            FailureReason.PATH_INVALID, "路径无效: ${conn.readRespBody()}",
                        )
                        code in 500..599 -> throw HashVerifyAbortException(
                            FailureReason.SERVER_ERROR, "服务端错误 $code",
                        )
                        else -> throw HashVerifyAbortException(
                            FailureReason.UNKNOWN, "下载失败 HTTP $code",
                        )
                    }
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: HashVerifyAbortException) {
            return@withContext FileTransferResult.Failed(e.reason, e.message ?: "")
        } catch (e: IOException) {
            Log.w(TAG, "downloadFileVerified 重试用尽: ${e.message}")
            return@withContext FileTransferResult.Failed(
                FailureReason.NETWORK_ERROR, "网络错误: ${e.message ?: "下载失败"}",
            )
        }

        if (expectedHash != null) {
            val actual = sha256Hex(data)
            if (actual != expectedHash) {
                Log.w(TAG, "download hash mismatch: expected=$expectedHash actual=$actual")
                return@withContext FileTransferResult.Failed(
                    FailureReason.HASH_MISMATCH, "完整性校验失败:本地与 PC 端 hash 不一致",
                )
            }
            FileTransferResult.DownloadSuccess(data, verified = true)
        } else {
            FileTransferResult.DownloadSuccess(data, verified = false)
        }
    }

    /** 上传文件(向后兼容入口,无 hash 校验)。新代码应优先 [uploadFileVerified]。 */
    suspend fun uploadFile(
        serverAddr: String,
        path: String,
        data: ByteArray,
        pubkey: String,
    ): Boolean = uploadFileVerified(serverAddr, path, data, pubkey) is FileTransferResult.UploadSuccess

    /**
     * 上传文件并做完整性校验:
     * 1. 本地计算 SHA-256,随 query 参数 sha256=<hex> 发送
     * 2. 服务端写入后重算文件 hash 与之比对:一致 200;不一致删除损坏文件并返回 460
     * 3. 网络层错误经 retryIO 重试;HTTP 业务错误(403/400/413/460/5xx)不重试
     */
    suspend fun uploadFileVerified(
        serverAddr: String,
        path: String,
        data: ByteArray,
        pubkey: String,
    ): FileTransferResult = withContext(Dispatchers.IO) {
        val localHash = sha256Hex(data)
        val url = "${httpBaseUrl(serverAddr)}/file/upload?path=${encodeParam(path)}" +
            "&pubkey=${encodeParam(pubkey)}&sha256=${encodeParam(localHash)}"

        val (code, respBody) = try {
            retryIO<Pair<Int, String>> {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = FILE_READ_TIMEOUT_MS
                    requestMethod = "POST"
                    doOutput = true
                    useCaches = false
                    setFixedLengthStreamingMode(data.size)
                }
                try {
                    conn.outputStream.use { it.write(data) }
                    Pair(conn.responseCode, conn.readRespBody())
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: HashVerifyAbortException) {
            return@withContext FileTransferResult.Failed(e.reason, e.message ?: "")
        } catch (e: IOException) {
            Log.w(TAG, "uploadFileVerified 重试用尽: ${e.message}")
            return@withContext FileTransferResult.Failed(
                FailureReason.NETWORK_ERROR, "网络错误: ${e.message ?: "上传失败"}",
            )
        }

        when {
            code == 200 -> {
                val hash = try {
                    JSONObject(respBody).optString("hash")
                } catch (e: Exception) {
                    ""
                }
                FileTransferResult.UploadSuccess(hash.takeIf { it.isNotBlank() })
            }
            code == 403 -> FileTransferResult.Failed(FailureReason.NOT_PAIRED, "未配对,无法写入文件")
            code == 460 -> FileTransferResult.Failed(
                FailureReason.HASH_MISMATCH,
                "完整性校验失败:PC 端写入后 hash 与上传前不一致(可能传输损坏)",
            )
            code == 413 -> FileTransferResult.Failed(FailureReason.TOO_LARGE, "文件过大,超出服务端上传上限")
            code == 400 -> FileTransferResult.Failed(FailureReason.PATH_INVALID, "路径无效: $respBody")
            code in 500..599 -> FileTransferResult.Failed(FailureReason.SERVER_ERROR, "服务端错误 $code")
            else -> FileTransferResult.Failed(FailureReason.UNKNOWN, "上传失败 HTTP $code")
        }
    }

    /** 新建目录 */
    suspend fun mkdir(serverAddr: String, path: String, pubkey: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/file/mkdir?path=${encodeParam(path)}&pubkey=${encodeParam(pubkey)}"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "POST"
                    setFixedLengthStreamingMode(0)
                }
                try {
                    conn.responseCode == 200
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "mkdir 失败: ${e.message}")
                false
            }
        }

    /** 删除文件或目录 */
    suspend fun deleteFile(serverAddr: String, path: String, pubkey: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/file/delete?path=${encodeParam(path)}&pubkey=${encodeParam(pubkey)}"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "POST"
                    setFixedLengthStreamingMode(0)
                }
                try {
                    conn.responseCode == 200
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "deleteFile 失败: ${e.message}")
                false
            }
        }

    /** 重命名/移动 */
    suspend fun renameFile(serverAddr: String, from: String, to: String, pubkey: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/file/rename?from=${encodeParam(from)}&to=${encodeParam(to)}&pubkey=${encodeParam(pubkey)}"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "POST"
                    setFixedLengthStreamingMode(0)
                }
                try {
                    conn.responseCode == 200
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "renameFile 失败: ${e.message}")
                false
            }
        }

    // ================================================================
    // 剪贴板同步(clipboard)端点
    // ================================================================

    /** 剪贴板历史条目(PC 端维护,新的在前) */
    data class ClipboardEntry(
        val id: Long,
        val text: String,
        val updatedAt: Long,
    )

    /** 拉取 PC 剪贴板历史列表 */
    suspend fun listClipboard(serverAddr: String, pubkey: String): List<ClipboardEntry>? =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/clipboard/list?pubkey=${encodeParam(pubkey)}"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    useCaches = false
                }
                try {
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val arr = JSONObject(body).optJSONArray("entries") ?: return@withContext emptyList()
                        List(arr.length()) { i ->
                            val o = arr.getJSONObject(i)
                            ClipboardEntry(
                                id = o.optLong("id"),
                                text = o.optString("text"),
                                updatedAt = o.optLong("updated_at"),
                            )
                        }
                    } else {
                        Log.w(TAG, "listClipboard HTTP ${conn.responseCode}")
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "listClipboard 失败: ${e.message}")
                null
            }
        }

    /** 把文本设为 PC 当前剪贴板(body = 纯文本) */
    suspend fun setClipboard(serverAddr: String, text: String, pubkey: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/clipboard/set?pubkey=${encodeParam(pubkey)}"
            try {
                val body = text.toByteArray(Charsets.UTF_8)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "POST"
                    doOutput = true
                    useCaches = false
                    setFixedLengthStreamingMode(body.size)
                }
                try {
                    conn.outputStream.use { it.write(body) }
                    conn.responseCode == 200
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "setClipboard 失败: ${e.message}")
                false
            }
        }

    /** 编辑 PC 剪贴板历史条目(编辑后该条目置顶并成为 PC 当前剪贴板) */
    suspend fun updateClipboardEntry(serverAddr: String, id: Long, text: String, pubkey: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/clipboard/update?pubkey=${encodeParam(pubkey)}"
            try {
                val body = JSONObject().put("id", id).put("text", text).toString()
                    .toByteArray(Charsets.UTF_8)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "POST"
                    doOutput = true
                    useCaches = false
                    setFixedLengthStreamingMode(body.size)
                }
                try {
                    conn.outputStream.use { it.write(body) }
                    conn.responseCode == 200
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "updateClipboardEntry 失败: ${e.message}")
                false
            }
        }

    /** 删除 PC 剪贴板历史条目 */
    suspend fun deleteClipboardEntry(serverAddr: String, id: Long, pubkey: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/clipboard/delete?id=$id&pubkey=${encodeParam(pubkey)}"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "POST"
                    setFixedLengthStreamingMode(0)
                }
                try {
                    conn.responseCode == 200
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "deleteClipboardEntry 失败: ${e.message}")
                false
            }
        }

    /** 清空 PC 剪贴板历史 */
    suspend fun clearClipboard(serverAddr: String, pubkey: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${httpBaseUrl(serverAddr)}/clipboard/clear?pubkey=${encodeParam(pubkey)}"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "POST"
                    setFixedLengthStreamingMode(0)
                }
                try {
                    conn.responseCode == 200
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "clearClipboard 失败: ${e.message}")
                false
            }
        }

    private const val FILE_READ_TIMEOUT_MS = 30000
}
