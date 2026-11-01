package com.cameramonitor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cameramonitor.app.databinding.ActivitySettingsBinding
import com.cameramonitor.app.util.LogExporter
import com.cameramonitor.app.util.UpdateChecker
import kotlinx.coroutines.launch

/**
 * 设置页：检查更新、发送日志、导出日志等调试选项。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text =
            "v${packageManager.getPackageInfo(packageName, 0).versionName}"

        // 连接模式偏好（默认热点）
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentMode = prefs.getString(KEY_CONNECTION_MODE, MODE_HOTSPOT) ?: MODE_HOTSPOT
        binding.rbHotspot.isChecked = (currentMode == MODE_HOTSPOT)
        binding.rbWifiDirect.isChecked = (currentMode == MODE_WIFI_DIRECT)
        binding.rgConnectionMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbWifiDirect) MODE_WIFI_DIRECT else MODE_HOTSPOT
            prefs.edit().putString(KEY_CONNECTION_MODE, mode).apply()
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCheckUpdate.setOnClickListener { checkUpdate() }
        binding.btnSendLog.setOnClickListener { sendLogToServer() }
        binding.btnExportLog.setOnClickListener { exportLogAndShare() }
    }

    companion object {
        const val PREFS_NAME = "app_settings"
        const val KEY_CONNECTION_MODE = "connection_mode"
        const val MODE_HOTSPOT = "hotspot"
        const val MODE_WIFI_DIRECT = "wifidirect"

        /** 便捷方法：读取当前默认连接模式 */
        fun getConnectionMode(context: android.content.Context): String {
            return context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString(KEY_CONNECTION_MODE, MODE_HOTSPOT) ?: MODE_HOTSPOT
        }
    }

    /**
     * 一键发送日志给开发者：确认后 POST 上传到公网收集端点。
     * 不区分角色 —— 日志头里的设备型号（Xiaomi/HUAWEI）已足够区分两台手机。
     */
    private fun sendLogToServer() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("上传诊断日志")
            .setMessage("将把本机型号、APP 版本和运行日志上传给开发者用于排查问题，不含任何个人隐私数据。")
            .setPositiveButton("确认上传") { _, _ -> doSendLog() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doSendLog() {
        val progress = android.app.ProgressDialog(this).apply {
            setMessage("正在上传日志到云端…")
            setCancelable(false)
            show()
        }
        lifecycleScope.launch {
            val ok = LogExporter.uploadToServer(this@SettingsActivity)
            progress.dismiss()
            if (ok) {
                Toast.makeText(this@SettingsActivity, "日志已发送，可以通知开发者查看", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    this@SettingsActivity,
                    "云端上传失败，可改用「导出连接日志」通过微信/QQ发送",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 云端热更新：从腾讯云开发静态托管拉取版本信息，对比后下载安装。
     */
    private fun checkUpdate() {
        val progress = android.app.ProgressDialog(this).apply {
            setMessage("正在连接云端更新服务器…")
            setCancelable(false)
            show()
        }
        lifecycleScope.launch {
            val server = UpdateChecker.findServer(this@SettingsActivity)
            if (server == null) {
                progress.dismiss()
                Toast.makeText(
                    this@SettingsActivity,
                    "云端更新服务器暂不可达\n请检查手机网络后重试",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            progress.setMessage("正在检查版本…")
            val info = UpdateChecker.checkVersion(server)
            if (info == null) {
                progress.dismiss()
                Toast.makeText(this@SettingsActivity, "获取版本信息失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val current = UpdateChecker.currentVersionCode(this@SettingsActivity)
            if (info.versionCode <= current) {
                progress.dismiss()
                Toast.makeText(
                    this@SettingsActivity,
                    "已是最新版本（v${info.versionName}）",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            progress.setMessage("发现新版本 v${info.versionName}，正在下载…")
            val apk = UpdateChecker.downloadApk(this@SettingsActivity, server) { percent ->
                runOnUiThread { progress.setMessage("正在下载新版本 v${info.versionName}…$percent%") }
            }
            progress.dismiss()
            if (apk == null) {
                Toast.makeText(this@SettingsActivity, "下载失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (UpdateChecker.needInstallPermission(this@SettingsActivity)) {
                Toast.makeText(
                    this@SettingsActivity,
                    "请允许「安装未知应用」权限后重试",
                    Toast.LENGTH_LONG
                ).show()
            }
            UpdateChecker.installApk(this@SettingsActivity, apk)
        }
    }

    /**
     * 抓取 logcat 并通过系统分享面板发送（可选微信/QQ 等）。
     */
    private fun exportLogAndShare() {
        val uri: Uri? = LogExporter.export(this)
        if (uri == null) {
            Toast.makeText(this, "日志导出失败", Toast.LENGTH_SHORT).show()
            return
        }
        LogExporter.cleanupOldLogs(this)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_SUBJECT, "双机监看自拍 - 连接日志")
        }
        try {
            startActivity(Intent.createChooser(shareIntent, "选择转发方式"))
        } catch (e: Exception) {
            Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
        }
    }
}
