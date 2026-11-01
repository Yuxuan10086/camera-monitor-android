package com.cameramonitor.app.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志导出工具：抓取 logcat 写入文件，并通过 FileProvider 分享。
 *
 * 用法：
 *   val uri = LogExporter.export(context)
 *   // 用 Intent.ACTION_SEND 分享 uri
 */
object LogExporter {

    private const val TAG = "LogExporter"
    private const val LOG_DIR = "logs"

    /**
     * 抓取 logcat 并保存到 cache/logs/ 目录，返回 content:// Uri 供分享。
     */
    fun export(context: Context): Uri? {
        return try {
            val logDir = File(context.cacheDir, LOG_DIR).apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val logFile = File(logDir, "cameramonitor_$timestamp.log")

            // 抓取关键 tag 的日志
            val tags = listOf(
                "WifiDirectManager",
                "MonitorClient",
                "VideoServer",
                "ControlServer",
                "ScreenCapture",
                "ScreenReceive",
                "TouchService",
                "CameraActivity",
                "MonitorActivity",
                "AndroidRuntime"
            ).joinToString(":* ") + ":*"

            val process = Runtime.getRuntime().exec("logcat -d -v time -s $tags")
            val input: InputStream = process.inputStream
            FileOutputStream(logFile).use { out ->
                out.write("=== 双机监看自拍 日志导出 ${timestamp} ===\n\n".toByteArray())
                input.copyTo(out)
            }
            // 等待进程结束，避免被截断
            process.waitFor()

            Log.d(TAG, "Log exported to ${logFile.absolutePath} (${logFile.length()} bytes)")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logFile)
        } catch (e: Exception) {
            Log.e(TAG, "Export log failed", e)
            null
        }
    }

    /**
     * 抓取日志内容（含设备信息头），返回字符串。
     */
    fun collectLogText(context: Context): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val tags = listOf(
            "WifiDirectManager", "MonitorClient", "VideoServer", "ControlServer",
            "ScreenCapture", "ScreenReceive", "TouchService", "CameraActivity",
            "MonitorActivity", "UpdateChecker", "AndroidRuntime"
        ).joinToString(":* ") + ":*"

        val header = buildString {
            appendLine("=== 双机监看自拍 日志 $timestamp ===")
            appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("APP版本: ${UpdateChecker.currentVersionCode(context)}")
            appendLine()
        }

        return try {
            val process = Runtime.getRuntime().exec("logcat -d -v time -s $tags")
            val body = process.inputStream.bufferedReader().readText()
            process.waitFor()
            header + body
        } catch (e: Exception) {
            Log.e(TAG, "Collect log failed", e)
            header + "(logcat 抓取失败: ${e.message})"
        }
    }

    /**
     * 上传日志到 webhook.site 收集端点（公网可达，不依赖局域网，不消耗云函数资源点）。
     * 开发者通过 https://webhook.site/token/{id}/requests 读取。
     * 返回 true 表示上传成功。
     */
    suspend fun uploadToServer(context: Context): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                var text = collectLogText(context)
                // webhook.site 免费版对超大请求体可能截断
                // 策略：保头部（设备信息+版本号，约 5KB）+ 尾部（最新日志，145KB），中间丢
                val maxBytes = 150 * 1024
                val headBytes = 5 * 1024  // 头部设备信息+启动日志
                val raw = text.toByteArray(Charsets.UTF_8)
                if (raw.size > maxBytes) {
                    val head = String(raw.copyOfRange(0, headBytes), Charsets.UTF_8)
                    val tail = String(raw.copyOfRange(raw.size - (maxBytes - headBytes), raw.size), Charsets.UTF_8)
                    text = head + "\n...(中间日志已截断)...\n" + tail
                }
                val device = java.net.URLEncoder.encode(
                    "${android.os.Build.MANUFACTURER}-${android.os.Build.MODEL}", "UTF-8"
                )
                val appVersion = UpdateChecker.currentVersionCode(context)
                // 端点由 secrets.properties 注入（BuildConfig），仓库里不保存 token
                val endpoint = com.cameramonitor.app.BuildConfig.LOG_UPLOAD_URL
                if (endpoint.isBlank()) {
                    Log.e(TAG, "LOG_UPLOAD_URL not configured, skip upload")
                    return@withContext false
                }
                val url = java.net.URL(
                    "$endpoint?device=$device&appVersion=$appVersion"
                )
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                Log.d(TAG, "Upload log to webhook.site, response: $code")
                code == 200
            } catch (e: Exception) {
                Log.e(TAG, "Upload log failed", e)
                false
            }
        }

    /**
     * 清理旧的日志文件（保留最近 5 个）。
     */
    fun cleanupOldLogs(context: Context) {
        try {
            val logDir = File(context.cacheDir, LOG_DIR)
            if (!logDir.exists()) return
            val files = logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            files.drop(5).forEach { it.delete() }
        } catch (_: Exception) {
        }
    }
}
