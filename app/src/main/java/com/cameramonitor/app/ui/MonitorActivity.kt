package com.cameramonitor.app.ui

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cameramonitor.app.SettingsActivity
import com.cameramonitor.app.connection.ConnectionState
import com.cameramonitor.app.connection.TouchEvent
import com.cameramonitor.app.connection.WifiDirectManager
import com.cameramonitor.app.databinding.ActivityMonitorBinding
import com.cameramonitor.app.screen.ScreenReceiveService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MonitorActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "MonitorActivity"
    }

    private lateinit var binding: ActivityMonitorBinding
    private lateinit var wifiManager: WifiDirectManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiManager = WifiDirectManager(this)

        binding.surfaceView.holder.addCallback(this)

        binding.btnDisconnect.setOnClickListener { finish() }
        binding.btnBack.setOnClickListener { finish() }

        // 读偏好自动选连接方式，不再弹对话框
        val mode = SettingsActivity.getConnectionMode(this)
        if (mode == SettingsActivity.MODE_HOTSPOT) {
            startHotspotMode()
        } else {
            hotspotMode = false
            observeConnection()
            showDevicePicker()
        }
    }

    /** true = 热点模式；false = WiFi Direct 模式 */
    private var hotspotMode = true

    /** 热点模式：拍摄端即网关，自动获取其 IP 并直连 */
    private fun startHotspotMode() {
        binding.loadingMask.visibility = View.VISIBLE
        binding.tvLoading.text = "正在连接拍摄端…"

        val gateway = com.cameramonitor.app.util.NetworkUtils.getGatewayAddress(this)
        if (gateway == null) {
            binding.loadingMask.visibility = View.GONE
            AlertDialog.Builder(this)
                .setTitle("未检测到热点连接")
                .setMessage("请先在系统 WiFi 设置中连接拍摄端手机的热点，然后重新进入本页面。")
                .setPositiveButton("重新检测") { _, _ -> startHotspotMode() }
                .setNegativeButton("返回") { _, _ -> finish() }
                .show()
            return
        }

        Log.d(TAG, "Hotspot mode, gateway (camera) IP: $gateway")
        binding.tvStatus.text = "热点模式 · $gateway"

        wireServiceCallbacks(gateway)

        ScreenReceiveService.start(this, gateway)
        // surface 由 surfaceCreated 回调通知服务，此处无需手动赋值
        // 超时提示
        binding.root.postDelayed({
            if (binding.loadingMask.visibility == View.VISIBLE) {
                binding.tvLoading.text = "连接中…请确认拍摄端已开启热点模式"
            }
        }, 4000)
    }

    /** 统一注册服务的连接/断开/尺寸回调（热点与 WiFi Direct 共用） */
    private fun wireServiceCallbacks(peerDesc: String) {
        ScreenReceiveServiceRef.onConnected = {
            runOnUiThread {
                binding.loadingMask.visibility = View.GONE
                binding.tvStatus.text = "已连接 · $peerDesc"
                setupVideoSizeCallback()
            }
        }
        ScreenReceiveServiceRef.onDisconnected = {
            runOnUiThread {
                binding.loadingMask.visibility = View.GONE
                binding.tvStatus.text = "连接已断开"
                Toast.makeText(this, "与拍摄端的连接已断开", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 监听视频真实分辨率，用 SurfaceHolder.setFixedSize 保持原比例（letterbox 黑边） */
    private fun setupVideoSizeCallback() {
        val sv = binding.surfaceView
        // 注册回调
        ScreenReceiveServiceRef.service?.onVideoSizeChanged = { w, h ->
            runOnUiThread { applyVideoSize(sv, w, h) }
        }
        // 如果解码早已开始（回调注册太晚），立即用缓存尺寸
        val cached = ScreenReceiveServiceRef.service?.getLastVideoSize()
        if (cached != null) {
            runOnUiThread { applyVideoSize(sv, cached.first, cached.second) }
        }
    }

    /**
     * 按视频真实比例设置画面区域（letterbox，居中，两边黑边均分）。
     * 交给 AspectRatioSurfaceView 自己按比例测量，父容器 gravity=center 负责居中，
     * 不再手动改 LayoutParams（那样容易只在一侧留边）。
     */
    private fun applyVideoSize(sv: AspectRatioSurfaceView, videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        // surface 缓冲区按视频原生尺寸 1:1 解码，不做缩放
        sv.holder.setFixedSize(videoW, videoH)
        sv.setAspectRatio(videoW, videoH)
        Log.d(TAG, "Applied video ratio ${videoW}x${videoH}, view ${sv.width}x${sv.height}")
    }

    private fun observeConnection() {
        lifecycleScope.launch {
            wifiManager.state.collectLatest { state ->
                when (state) {
                    is ConnectionState.ClientReady -> {
                        Log.d(TAG, "Connected to GO at ${state.serverAddress}")
                        binding.tvStatus.text = "已连接"
                        binding.tvLoading.text = "正在接收画面…"
                        wireServiceCallbacks(state.serverAddress)
                        // 启动接收服务（surface 由 surfaceCreated 回调通知服务）
                        ScreenReceiveService.start(this@MonitorActivity, state.serverAddress)
                        binding.loadingMask.visibility = View.GONE
                    }
                    is ConnectionState.Error -> {
                        binding.loadingMask.visibility = View.GONE
                        Toast.makeText(this@MonitorActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                    is ConnectionState.Connecting -> {
                        binding.loadingMask.visibility = View.VISIBLE
                        binding.tvLoading.text = "连接中…"
                    }
                    else -> {}
                }
            }
        }
    }

    private var deviceDialogShown = false

    private fun showDevicePicker() {
        wifiManager.start()
        wifiManager.discoverPeers()
        binding.loadingMask.visibility = View.VISIBLE
        binding.tvLoading.text = "正在发现设备…"

        // 持续观察设备列表，发现设备后弹出选择框
        lifecycleScope.launch {
            wifiManager.peerList.collectLatest { devices ->
                if (devices.isEmpty() || deviceDialogShown) return@collectLatest
                deviceDialogShown = true
                binding.loadingMask.visibility = View.GONE
                showDeviceListDialog(devices)
            }
        }
    }

    private fun showDeviceListDialog(devices: List<WifiP2pDevice>) {
        val items = devices.map { it.deviceName ?: it.deviceAddress }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择拍摄端设备")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, items)) { _, which ->
                binding.loadingMask.visibility = View.VISIBLE
                binding.tvLoading.text = "连接中…"
                wifiManager.connectToDevice(devices[which])
            }
            .setNegativeButton("取消") { _, _ ->
                // 允许再次发现设备
                deviceDialogShown = false
                binding.loadingMask.visibility = View.VISIBLE
                binding.tvLoading.text = "正在发现设备…"
            }
            .show()
    }

    // ===== SurfaceHolder.Callback =====
    override fun surfaceCreated(holder: SurfaceHolder) {
        // 关键：必须在这里直接通知服务。热点局域网内 TCP 建连只需几毫秒，
        // 可能早于 SurfaceView 首次布局 —— 如果只在服务启动/建连时读一次共享引用，
        // 会读到 null 导致解码器永远不创建（表现为"已连接但黑屏无画面"）。
        ScreenReceiveServiceRef.surface = holder.surface
        ScreenReceiveServiceRef.service?.setOutputSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ScreenReceiveServiceRef.surface = null
    }

    // ===== 触摸事件转发 =====
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // loading 遮罩可见时不转发触摸
        if (binding.loadingMask.visibility == View.VISIBLE) {
            return super.dispatchTouchEvent(ev)
        }
        // 判断触摸点是否落在 SurfaceView 内（视频画面区域）
        val sv = binding.surfaceView
        val loc = IntArray(2)
        sv.getLocationOnScreen(loc)
        val leftF = loc[0].toFloat(); val topF = loc[1].toFloat()
        val rightF = leftF + sv.width.toFloat(); val bottomF = topF + sv.height.toFloat()
        val x = ev.x; val y = ev.y
        if (x >= leftF && x <= rightF && y >= topF && y <= bottomF) {
            // 归一化到 SurfaceView 内部坐标（0~1）
            val nx = (x - leftF) / sv.width.toFloat()
            val ny = (y - topF) / sv.height.toFloat()
            val action = when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> TouchEvent.ACTION_DOWN
                MotionEvent.ACTION_MOVE -> TouchEvent.ACTION_MOVE
                MotionEvent.ACTION_UP -> TouchEvent.ACTION_UP
                else -> TouchEvent.ACTION_CANCEL
            }
            ScreenReceiveServiceRef.sendTouch(action, nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenReceiveServiceRef.onConnected = null
        ScreenReceiveServiceRef.onDisconnected = null
        if (!hotspotMode) {
            wifiManager.stop()
        }
        stopService(Intent(this, ScreenReceiveService::class.java))
    }
}

/**
 * 按视频宽高比测量自身的 SurfaceView。
 *
 * 原生 SurfaceView 的 wrap_content 在 match_parent 容器里会被测量成满屏，
 * 只靠 setFixedSize 改不了 View 尺寸；这里在 onMeasure 里按比例算尺寸，
 * 配合父容器 layout_gravity=center，画面必然居中、两侧黑边等宽。
 */
class AspectRatioSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    /** 宽 / 高，0 表示未知（退化为默认测量） */
    private var aspectRatio = 0f

    fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val ratio = width.toFloat() / height.toFloat()
        if (ratio != aspectRatio) {
            aspectRatio = ratio
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        if (aspectRatio <= 0f || maxW <= 0 || maxH <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val w: Int
        val h: Int
        if (maxW.toFloat() / maxH > aspectRatio) {
            // 容器偏宽，高度受限：图片高度撑满，两侧留黑边
            h = maxH
            w = (h * aspectRatio).toInt().coerceAtMost(maxW)
        } else {
            // 容器偏窄，宽度受限：宽度撑满，上下留黑边
            w = maxW
            h = (w / aspectRatio).toInt().coerceAtMost(maxH)
        }
        setMeasuredDimension(w.coerceAtLeast(1), h.coerceAtLeast(1))
    }
}

/**
 * 简化的服务引用（用于 Activity 与 ScreenReceiveService 通信）。
 * 实际生产中应使用 Binder 或 LocalBroadcast。
 */
object ScreenReceiveServiceRef {
    @Volatile var surface: android.view.Surface? = null
    @Volatile var service: ScreenReceiveService? = null
    /** 视频通道连接成功回调（在 IO 线程触发，UI 操作需自行切主线程） */
    @Volatile var onConnected: (() -> Unit)? = null
    /** 连接断开回调（IO 线程触发），包括对端主动断开 */
    @Volatile var onDisconnected: (() -> Unit)? = null

    fun sendTouch(action: Int, x: Float, y: Float) {
        service?.sendTouch(action, x, y)
    }
}
