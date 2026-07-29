package com.meowmic.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新状态
 */
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val version: String, val downloadUrl: String, val notes: String) : UpdateState()
    object UpToDate : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    data class ReadyToInstall(val apkPath: String) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

/**
 * 应用自更新检查器
 *
 * 通过 GitHub Releases API 检查最新版本,下载 APK 并调起系统安装器。
 * - GitHub API: GET /repos/{owner}/{repo}/releases/latest
 * - 找 tag_name + 名为 meowmic-{tag}.apk 的 asset
 */
class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "MeowMic/Update"
        private const val OWNER = "siciyuan404"
        private const val REPO = "MeowMic"
        // APK asset 命名规则:meowmic-v{tag}.apk
        private const val APK_PREFIX = "meowmic-"
        private const val APK_SUFFIX = ".apk"
    }

    /**
     * 获取当前应用版本名
     */
    fun getCurrentVersion(): String {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            info.versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    /**
     * 检查 GitHub 最新 Release
     * @return UpdateState.Available 或 UpToDate 或 Error
     */
    suspend fun checkLatest(): UpdateState = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 10000
                readTimeout = 10000
            }
            if (conn.responseCode != 200) {
                return@withContext UpdateState.Error("GitHub API 返回 ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")  // v0.5.1
            val notes = json.optString("body", "")
            if (tagName.isEmpty()) return@withContext UpdateState.Error("未找到 tag_name")

            val latestVer = tagName.removePrefix("v")
            val currentVer = getCurrentVersion()
            if (!isNewer(latestVer, currentVer)) {
                return@withContext UpdateState.UpToDate
            }

            // 在 assets 里找 APK
            val assets = json.optJSONArray("assets") ?: return@withContext UpdateState.Error("Release 无 assets")
            var downloadUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.startsWith(APK_PREFIX) && name.endsWith(APK_SUFFIX)) {
                    downloadUrl = asset.optString("browser_download_url", "")
                    break
                }
            }
            if (downloadUrl.isNullOrEmpty()) {
                return@withContext UpdateState.Error("Release 中未找到 APK")
            }
            UpdateState.Available(latestVer, downloadUrl, notes)
        } catch (e: Exception) {
            Log.w(TAG, "检查更新失败", e)
            UpdateState.Error(e.message ?: "网络错误")
        }
    }

    /**
     * 下载 APK 到缓存目录
     * @param url 下载地址
     * @param onProgress 进度回调(0-100)
     * @return APK 文件路径
     */
    suspend fun downloadApk(url: String, onProgress: (Int) -> Unit): String = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(dir, "meowmic-update.apk")
        if (apkFile.exists()) apkFile.delete()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
        }
        try {
            if (conn.responseCode != 200) {
                throw RuntimeException("下载失败: HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
                    var lastEmit = 0L
                    while (input.read(buf).also { n = it } > 0) {
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            val now = System.currentTimeMillis()
                            if (pct >= 100 || now - lastEmit > 100) {
                                onProgress(pct)
                                lastEmit = now
                            }
                        }
                    }
                }
            }
            apkFile.absolutePath
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 调起系统安装器安装 APK
     */
    fun installApk(apkPath: String) {
        val file = File(apkPath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 语义化版本比较:latest > current 返回 true
     * 支持 "0.5.1" / "v0.5.1" 格式
     */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}
