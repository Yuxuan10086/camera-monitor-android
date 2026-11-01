package com.cameramonitor.app.connection

import android.util.Log
import com.cameramonitor.app.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Socket

/**
 * 监看端：TCP 客户端。
 * 连接拍摄端的 VIDEO_PORT（收流）和 CONTROL_PORT（发触摸指令）。
 */
class MonitorTransportClient(private val serverAddress: String) {

    companion object {
        private const val TAG = "MonitorClient"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var videoSocket: Socket? = null
    private var controlSocket: Socket? = null
    private var videoInput: BufferedInputStream? = null
    private var controlOutput: OutputStream? = null

    var onVideoFrame: ((timestamp: Long, flags: Int, data: ByteArray) -> Unit)? = null
    /** 拍摄端下发的真实视频分辨率 */
    var onVideoConfig: ((width: Int, height: Int) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun connect() {
        scope.launch {
            var lastError: Exception? = null
            // 重试连接，应对拍摄端 TCP 服务启动的微小延迟
            for (attempt in 1..5) {
                try {
                    videoSocket = Socket(serverAddress, AppConstants.VIDEO_PORT)
                    videoInput = BufferedInputStream(videoSocket!!.getInputStream())
                    Log.d(TAG, "Video connected (attempt $attempt) to $serverAddress:${AppConstants.VIDEO_PORT}")

                    controlSocket = Socket(serverAddress, AppConstants.CONTROL_PORT)
                    controlOutput = controlSocket!!.getOutputStream()
                    Log.d(TAG, "Control connected (attempt $attempt) to $serverAddress:${AppConstants.CONTROL_PORT}")

                    onConnected?.invoke()
                    receiveVideoLoop()
                    return@launch
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Connect attempt $attempt failed: ${e.message}")
                    runCatching { videoSocket?.close() }
                    runCatching { controlSocket?.close() }
                    videoSocket = null
                    controlSocket = null
                    if (attempt < 5) kotlinx.coroutines.delay(500)
                }
            }
            Log.e(TAG, "All connect attempts failed", lastError)
            onDisconnected?.invoke()
        }
    }

    fun disconnect() {
        scope.cancel()
        runCatching { videoSocket?.close() }
        runCatching { controlSocket?.close() }
        videoSocket = null
        controlSocket = null
    }

    private fun receiveVideoLoop() {
        var receivedFrames = 0L
        try {
            while (scope.isActive) {
                val msg = MessageProtocol.readMessage(videoInput!!) ?: break
                val (type, payload) = msg
                when (type) {
                    MessageProtocol.MSG_VIDEO_CONFIG -> {
                        MessageProtocol.parseVideoConfig(payload)?.let { (w, h) ->
                            Log.d(TAG, "Video config from camera: ${w}x$h")
                            onVideoConfig?.invoke(w, h)
                        }
                    }
                    MessageProtocol.MSG_VIDEO -> {
                        val (ts, flags, data) = MessageProtocol.parseVideo(payload)
                        receivedFrames++
                        if (receivedFrames % 150 == 1L) Log.d(TAG, "Received $receivedFrames frames total")
                        onVideoFrame?.invoke(ts, flags, data)
                    }
                    MessageProtocol.MSG_DISCONNECT -> {
                        Log.d(TAG, "Received DISCONNECT from camera")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video receive error", e)
        }
        Log.d(TAG, "Video receive loop ended, received $receivedFrames frames total")
        onDisconnected?.invoke()
    }

    /** 发送触摸事件 */
    fun sendTouch(action: Int, x: Float, y: Float) {
        scope.launch {
            try {
                val msg = MessageProtocol.encodeTouch(action, x, y, System.currentTimeMillis())
                controlOutput?.write(msg)
                controlOutput?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Send touch error", e)
            }
        }
    }
}
