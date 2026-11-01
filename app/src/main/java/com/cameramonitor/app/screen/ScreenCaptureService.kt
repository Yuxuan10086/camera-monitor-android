package com.cameramonitor.app.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.cameramonitor.app.AppConstants
import com.cameramonitor.app.connection.MessageProtocol
import com.cameramonitor.app.connection.TransportHub
import java.nio.ByteBuffer

/**
 * 拍摄端：屏幕采集前台服务。
 *
 * 使用 MediaProjection 采集屏幕画面，MediaCodec 编码为 H.264，
 * 通过 CameraTransportServer 推送给监看端。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var encodeThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: return START_NOT_STICKY
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        } ?: return START_NOT_STICKY

        startForegroundNotification()
        startCapture(resultCode, resultData)
        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConstants.NOTIFICATION_CHANNEL_CAPTURE,
                "屏幕采集",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, AppConstants.NOTIFICATION_CHANNEL_CAPTURE)
            .setContentTitle("双机监看自拍")
            .setContentText("正在采集屏幕画面")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AppConstants.NOTIFICATION_ID_CAPTURE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(AppConstants.NOTIFICATION_ID_CAPTURE, notification)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        // Android 14+ 强制要求：createVirtualDisplay 之前必须注册 callback，
        // 否则抛 IllegalStateException("Must register a callback before starting capture")
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        // 确保 TCP 传输层已启动（正常情况下 CameraActivity 已在 GroupOwnerReady 时启动）
        TransportHub.start()

        setupEncoder()
    }

    private fun setupEncoder() {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        // 按最长边限制到 VIDEO_WIDTH，保持屏幕宽高比，避免超出编码器能力
        val maxDim = AppConstants.VIDEO_WIDTH.toFloat()
        val scale = maxDim / maxOf(metrics.widthPixels, metrics.heightPixels)
        val targetWidth = ((metrics.widthPixels * scale).toInt() / 2) * 2
        val targetHeight = ((metrics.heightPixels * scale).toInt() / 2) * 2
        Log.d(TAG, "Screen ${metrics.widthPixels}x${metrics.heightPixels} -> encode ${targetWidth}x$targetHeight")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, AppConstants.VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, AppConstants.VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, AppConstants.VIDEO_I_FRAME_INTERVAL)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        // 创建 VirtualDisplay，将屏幕渲染到 encoder 的 inputSurface
        // 把真实编码分辨率下发给监看端：解码器必须按这个尺寸配置，
        // 否则横竖比例不符会导致画面被压扁（曾用固定 1280x720 出现此问题）
        TransportHub.sendVideoConfig(targetWidth, targetHeight)
        Log.d(TAG, "Video config sent to monitor: ${targetWidth}x$targetHeight")

        val density = metrics.densityDpi
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            targetWidth,
            targetHeight,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )

        // 启动编码线程，从 encoder 取输出并发送
        encodeThread = Thread({ encodeLoop() }, "EncodeLoop").apply { start() }
    }

    private fun encodeLoop() {
        val codec = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var encodedFrames = 0L
        try {
            while (!Thread.currentThread().isInterrupted) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        // 提取 NALU 数据
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)

                        val flags = if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) 1 else 0
                        val packet = MessageProtocol.encodeVideo(bufferInfo.presentationTimeUs / 1000, flags, data)
                        TransportHub.sendFrame(packet)
                        encodedFrames++
                        if (encodedFrames % 150 == 1L) Log.d(TAG, "Encoded $encodedFrames frames total")
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encode loop error", e)
        }
        Log.d(TAG, "Encode loop ended, encoded $encodedFrames frames total")
    }

    override fun onDestroy() {
        super.onDestroy()
        encodeThread?.interrupt()
        virtualDisplay?.release()
        virtualDisplay = null
        runCatching {
            encoder?.stop()
            encoder?.release()
        }
        encoder = null
        inputSurface?.release()
        inputSurface = null
        mediaProjection?.stop()
        mediaProjection = null
    }
}
