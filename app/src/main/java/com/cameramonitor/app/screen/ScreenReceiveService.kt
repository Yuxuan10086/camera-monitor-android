package com.cameramonitor.app.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.cameramonitor.app.AppConstants
import com.cameramonitor.app.connection.MonitorTransportClient
import com.cameramonitor.app.ui.ScreenReceiveServiceRef

/**
 * 监看端：接收画面前台服务。
 *
 * 通过 MonitorTransportClient 连接拍摄端，接收 H.264 视频流，
 * 使用 MediaCodec 解码并渲染到 Activity 提供的 Surface。
 */
class ScreenReceiveService : Service() {

    companion object {
        private const val TAG = "ScreenReceive"
        const val EXTRA_SERVER_ADDRESS = "server_address"

        fun start(context: Context, serverAddress: String) {
            val intent = Intent(context, ScreenReceiveService::class.java).apply {
                putExtra(EXTRA_SERVER_ADDRESS, serverAddress)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var client: MonitorTransportClient? = null
    private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = null

    /** Activity 提供的渲染 Surface（等分辨率到场后才创建解码器） */
    private var pendingSurface: Surface? = null
    /** 拍摄端下发的真实视频分辨率 */
    @Volatile private var configW = 0
    @Volatile private var configH = 0

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 分辨率迟迟未到时用兜底尺寸启动解码，避免死等 */
    private val fallbackStart = Runnable { maybeStartDecoder() }

    private val videoQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private var decodeThread: Thread? = null

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onLatencyUpdate: ((Long) -> Unit)? = null
    /** 视频真实分辨率回调（首次解码到 keyframe 后触发，仅一次） */
    var onVideoSizeChanged: ((Int, Int) -> Unit)? = null

    /** 缓存的视频宽高 + 是否已上报 */
    private var lastVideoW = 0
    private var lastVideoH = 0
    private var videoSizeReported = false

    /** 外部查询上次已知的视频尺寸（回调注册前已解码的情况） */
    fun getLastVideoSize(): Pair<Int, Int>? =
        if (lastVideoW > 0 && lastVideoH > 0) lastVideoW to lastVideoH else null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ScreenReceiveServiceRef.service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverAddress = intent?.getStringExtra(EXTRA_SERVER_ADDRESS) ?: return START_NOT_STICKY
        startForegroundNotification()
        // 如果 Activity 已经提供了 surface，直接使用
        ScreenReceiveServiceRef.surface?.let { setOutputSurface(it) }
        startReceive(serverAddress)
        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConstants.NOTIFICATION_CHANNEL_RECEIVE,
                "画面接收",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, AppConstants.NOTIFICATION_CHANNEL_RECEIVE)
            .setContentTitle("双机监看自拍")
            .setContentText("正在接收拍摄端画面")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AppConstants.NOTIFICATION_ID_RECEIVE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(AppConstants.NOTIFICATION_ID_RECEIVE, notification)
        }
    }

    private fun startReceive(serverAddress: String) {
        client = MonitorTransportClient(serverAddress).apply {
            onConnected = {
                this@ScreenReceiveService.onConnected?.invoke()
                ScreenReceiveServiceRef.onConnected?.invoke()
                // 连接成功后，如果 Activity 已提供 surface，设置解码器并启动解码线程
                ScreenReceiveServiceRef.surface?.let { setOutputSurface(it) }
            }
            onDisconnected = {
                this@ScreenReceiveService.onDisconnected?.invoke()
                ScreenReceiveServiceRef.onDisconnected?.invoke()
            }
            onVideoConfig = { w, h -> setVideoConfig(w, h) }
            onVideoFrame = { _, _, data ->
                maybeStartDecoder()
                videoQueue.offer(data)
                // 丢弃最旧的帧而非清空全部，避免丢掉关键帧导致花屏
                while (videoQueue.size > 30) videoQueue.poll()
            }
            connect()
        }
    }

    /**
     * 设置渲染 Surface（由 Activity 的 SurfaceView 提供）。
     * 首次设置时创建解码器并启动解码线程；后续 surface 重建时切换输出 surface。
     * 必须从所有可能产生有效 surface 的时机调用（onStartCommand / onConnected /
     * surfaceCreated），不能只靠其中一两个 —— 热点局域网建连比首帧布局更快，
     * 只靠 onConnected 会出现 surface 尚未就绪导致解码器永远不创建的竞态。
     */
    @Synchronized
    fun setOutputSurface(surface: Surface) {
        Log.d(TAG, "setOutputSurface, valid=${surface.isValid}, decoder=${decoder != null}")
        if (!surface.isValid) return
        val d = decoder
        if (d == null) {
            pendingSurface = surface
            maybeStartDecoder()
            // 分辨率若迟迟不到（对端未下发），用兜底尺寸启动，避免一直黑屏
            if (decoder == null) {
                mainHandler.removeCallbacks(fallbackStart)
                mainHandler.postDelayed(fallbackStart, 2000)
            }
        } else if (outputSurface != surface) {
            // SurfaceView 尺寸变化 / setFixedSize 后 surface 可能重建，切换输出目标
            runCatching { d.setOutputSurface(surface) }
                .onSuccess {
                    outputSurface = surface
                    Log.d(TAG, "Decoder output surface switched")
                }
                .onFailure { Log.e(TAG, "setOutputSurface failed", it) }
        }
    }

    /** 收到拍摄端下发的真实编码分辨率（IO 线程） */
    private fun setVideoConfig(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (configW == w && configH == h && decoder != null) return
        configW = w
        configH = h
        lastVideoW = w
        lastVideoH = h
        videoSizeReported = true
        Log.d(TAG, "Video config applied: ${w}x$h")
        onVideoSizeChanged?.invoke(w, h)
        maybeStartDecoder()
    }

    /**
     * 只有同时具备 Surface 和真实分辨率才创建解码器。
     * 分辨率未知时退回兜底竖屏尺寸（横竖弄反会把画面压扁）。
     */
    @Synchronized
    private fun maybeStartDecoder() {
        if (decoder != null) return
        val surface = pendingSurface ?: return
        if (!surface.isValid) return
        val w = if (configW > 0) configW else AppConstants.FALLBACK_VIDEO_WIDTH
        val h = if (configH > 0) configH else AppConstants.FALLBACK_VIDEO_HEIGHT
        mainHandler.removeCallbacks(fallbackStart)
        Log.d(TAG, "Start decoder with ${w}x$h (config=${configW}x$configH)")
        outputSurface = surface
        setupDecoder(surface, w, h)
        startDecodeThread()
    }

    private fun setupDecoder(surface: Surface, width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        )
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, surface, null, 0)
            start()
        }
        Log.d(TAG, "Decoder configured $width x $height and started")
    }

    private fun startDecodeThread() {
        decodeThread = Thread({ decodeLoop() }, "DecodeLoop").apply { start() }
    }

    private fun decodeLoop() {
        val codec = decoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var decodedFrames = 0L
        try {
            while (!Thread.currentThread().isInterrupted) {
                val frame = videoQueue.poll()
                if (frame == null) {
                    Thread.sleep(1)
                    continue
                }
                // 送入解码器输入缓冲区
                val inputIndex = codec.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                    inputBuffer.clear()
                    inputBuffer.put(frame)
                    codec.queueInputBuffer(inputIndex, 0, frame.size, System.nanoTime() / 1000, 0)
                }
                // 取出解码输出
                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                while (outputIndex >= 0) {
                    codec.releaseOutputBuffer(outputIndex, true) // 渲染到 surface
                    decodedFrames++
                    if (decodedFrames % 150 == 1L) Log.d(TAG, "Decoded $decodedFrames frames total")
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
                // 检测分辨率变化（MediaCodec 在 SPS/PPS 就绪后触发 OUTPUT_FORMAT_CHANGED）
                if (!videoSizeReported) {
                    val fmt = codec.outputFormat
                    // 单参数 getInteger 自 API 16 可用；字段缺失会抛异常，用 runCatching 兜底
                    val w = runCatching { fmt.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
                    val h = runCatching { fmt.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
                    if (w > 0 && h > 0) {
                        videoSizeReported = true
                        lastVideoW = w
                        lastVideoH = h
                        Log.d(TAG, "Video size detected: ${w}x${h}")
                        onVideoSizeChanged?.invoke(w, h)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode loop error", e)
        }
    }

    /** 发送触摸事件到拍摄端 */
    fun sendTouch(action: Int, x: Float, y: Float) {
        client?.sendTouch(action, x, y)
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenReceiveServiceRef.service = null
        decodeThread?.interrupt()
        client?.disconnect()
        client = null
        runCatching {
            decoder?.stop()
            decoder?.release()
        }
        decoder = null
        mainHandler.removeCallbacks(fallbackStart)
        // 注意：不能 release() —— 这个 Surface 属于 Activity 的 SurfaceView
        outputSurface = null
        pendingSurface = null
    }
}
