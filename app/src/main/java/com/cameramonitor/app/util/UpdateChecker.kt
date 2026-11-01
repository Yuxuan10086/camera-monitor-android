package com.cameramonitor.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端热更新检查器。
 *
 * 流程：请求云端 version.json（腾讯云开发静态托管，公网可达）→ 对比 versionCode →
 * 下载 app.apk → 通过 FileProvider 调起系统安装器。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val SERVER_PORT = 9999
    private const val HTTP_TIMEOUT_MS = 8000

    /** 云端更新服务器（腾讯云开发静态托管，公网可达，无需局域网） */
    private const val CLOUD_BASE_URL =
        "https://cv-yuxuanhue-d9gj1wlzmad8558f6-1259315002.ap-shanghai.app.tcloudbase.com/updates"

    data class UpdateInfo(val serverBase: String, val versionCode: Int, val versionName: String)

    /** 返回云端更新服务器地址（固定地址，无需扫描） */
    suspend fun findServer(context: Context): String? = withContext(Dispatchers.IO) {
        // 先探测云端是否可达
        if (httpGet("$CLOUD_BASE_URL/version.json") != null) {
            Log.d(TAG, "Cloud update server reachable")
            return@withContext CLOUD_BASE_URL
        }
        Log.e(TAG, "Cloud update server unreachable")
        null
    }

    /** 查询服务器上的最新版本信息 */
    suspend fun checkVersion(serverBase: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val body = httpGet("$serverBase/version.json") ?: return@withContext null
            val json = JSONObject(body)
            UpdateInfo(
                serverBase,
                json.getInt("versionCode"),
                json.optString("versionName", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkVersion failed", e)
            null
        }
    }

    /** 下载 APK 到 cacheDir/download/，返回文件 */
    suspend fun downloadApk(
        context: Context,
        serverBase: String,
        onProgress: (percent: Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$serverBase/app.apk").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 30000
            conn.connect()
            if (conn.responseCode != 200) return@withContext null
            val total = conn.contentLength
            val dir = File(context.cacheDir, "download").apply { mkdirs() }
            val file = File(dir, "update.apk")
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress((downloaded * 100 / total).toInt())
                        }
                    }
                }
            }
            Log.d(TAG, "APK downloaded: ${file.absolutePath} (${file.length()} bytes)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "downloadApk failed", e)
            null
        }
    }

    /** 调起系统安装器安装 APK */
    fun installApk(context: Context, file: File) {
        // Android 8+ 需要「安装未知应用」权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 当前 APP 的 versionCode */
    fun currentVersionCode(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") info.versionCode
        }
    }

    /** 是否需要请求「安装未知应用」权限 */
    fun needInstallPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
    }

    // ===== 内部实现 =====

    private fun httpGet(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.connect()
            val code = conn.responseCode
            val ctype = conn.contentType
            if (code != 200) {
                Log.e(TAG, "httpGet $url -> HTTP $code, type=$ctype")
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            Log.d(TAG, "httpGet $url -> 200, type=$ctype, body[0..300]=${body.take(300)}")
            body
        } catch (e: Exception) {
            Log.e(TAG, "httpGet $url exception", e)
            null
        }
    }
}
