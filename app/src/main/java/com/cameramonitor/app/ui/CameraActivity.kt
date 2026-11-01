package com.cameramonitor.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cameramonitor.app.AppConstants
import com.cameramonitor.app.SettingsActivity
import com.cameramonitor.app.connection.ConnectionState
import com.cameramonitor.app.connection.TransportHub
import com.cameramonitor.app.connection.WifiDirectManager
import com.cameramonitor.app.databinding.ActivityCameraBinding
import com.cameramonitor.app.screen.ScreenCaptureService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraActivity"
    }

    private lateinit var binding: ActivityCameraBinding
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var wifiManager: WifiDirectManager

    /** true = 热点模式；false = WiFi Direct 模式。值从设置偏好读取 */
    private var hotspotMode = true

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
            Toast.makeText(this, "屏幕采集已开启", Toast.LENGTH_SHORT).show()
            binding.btnStartCapture.isEnabled = false
            binding.btnStartCapture.text = "采集中…"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        wifiManager = WifiDirectManager(this)

        setupAccessibilityCard()
        setupWaitingSection()

        // 读偏好自动选连接方式，不再弹对话框
        hotspotMode = SettingsActivity.getConnectionMode(this) == SettingsActivity.MODE_HOTSPOT
        if (hotspotMode) startHotspotMode() else startWifiDirectMode()
    }

    /** 热点模式：不走 WiFi Direct，直接启动 TCP 服务等待监看端 */
    private fun startHotspotMode() {
        TransportHub.start()
        // 不弹窗，页面文字常驻提醒（去开热点的入口保留在状态文字里）
        binding.tvStatus.text = "热点模式 · 等待监看端连接\n请确保：① 本机热点已开启 ② 监看端已连接此热点"
        Toast.makeText(this, "TCP 服务已就绪", Toast.LENGTH_SHORT).show()
    }

    /** WiFi Direct 模式：原有逻辑 */
    private fun startWifiDirectMode() {

        // 检查定位服务
        if (!wifiManager.isLocationEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要开启定位服务")
                .setMessage("WiFi Direct 设备发现需要开启手机的定位服务。请在系统设置中开启定位后重试。")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
        }

        observeWifiState()
        wifiManager.start()
        wifiManager.createGroup()
    }

    private fun observeWifiState() {
        lifecycleScope.launch {
            wifiManager.state.collectLatest { state ->
                when (state) {
                    is ConnectionState.CreatingGroup -> {
                        binding.tvStatus.text = "正在创建连接…"
                    }
                    is ConnectionState.GroupOwnerReady -> {
                        binding.tvStatus.text = "等待监看端连接…"
                        // 建群成功后立即启动 TCP 传输层，确保监看端连上后能立即建立视频/控制通道
                        TransportHub.start()
                        Toast.makeText(this@CameraActivity, "连接已就绪，等待监看端", Toast.LENGTH_SHORT).show()
                    }
                    is ConnectionState.Error -> {
                        binding.tvStatus.text = state.message
                        Toast.makeText(this@CameraActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun setupAccessibilityCard() {
        val enabled = isAccessibilityEnabled()
        binding.cardAccessibility.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun setupWaitingSection() {
        binding.tvStatus.text = "正在初始化…"

        binding.btnStartCapture.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 请求屏幕录制权限
            val intent = projectionManager.createScreenCaptureIntent()
            projectionLauncher.launch(intent)
        }

        // 拍摄端主动断开：停止采集 + 踢掉当前监看端（服务端继续监听等下次连接）
        binding.btnDisconnect.setOnClickListener { disconnectMonitor() }

        // 监看端断开时自动停止屏幕共享（统一双边退出机制）
        TransportHub.onClientDisconnected = {
            runOnUiThread { onMonitorDisconnected() }
        }
    }

    /** 监看端断开（对方主动断开或断网）：自动停止屏幕采集 */
    private fun onMonitorDisconnected() {
        Log.d(TAG, "Monitor disconnected, stopping capture")
        stopService(Intent(this, ScreenCaptureService::class.java))
        // 同时踢掉视频通道残留 socket，让对端立刻感知
        TransportHub.videoServer?.disconnectClient()
        binding.btnStartCapture.isEnabled = true
        binding.btnStartCapture.text = "开启屏幕采集"
        binding.tvStatus.text = if (hotspotMode) {
            "监看端已断开\n热点模式 · 等待重新连接"
        } else {
            "监看端已断开，等待重新连接…"
        }
    }

    /** 拍摄端主动断开监看端 */
    private fun disconnectMonitor() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        TransportHub.disconnectClients()
        binding.btnStartCapture.isEnabled = true
        binding.btnStartCapture.text = "开启屏幕采集"
        binding.tvStatus.text = "已断开监看端，等待重新连接…"
        Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    override fun onResume() {
        super.onResume()
        // 从设置返回时刷新无障碍状态
        setupAccessibilityCard()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!hotspotMode) {
            wifiManager.stop()
        }
        stopService(Intent(this, ScreenCaptureService::class.java))
        TransportHub.stop()
    }
}
