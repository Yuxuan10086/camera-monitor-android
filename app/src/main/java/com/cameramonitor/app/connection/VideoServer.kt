package com.cameramonitor.app.connection

import android.util.Log
import com.cameramonitor.app.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 拍摄端：视频推流 TCP 服务端（监听 VIDEO_PORT）。
 */
class VideoServer {

    companion object {
        private const val TAG = "VideoServer"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var server: ServerSocket? = null
    private var client: Socket? = null
    private var output: OutputStream? = null

    private val frameQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var sending = false
    /** 视频参数消息（分辨率），新客户端连上后先发它，再发视频帧 */
    @Volatile private var videoConfig: ByteArray? = null

    var onClientConnected: (() -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null

    fun start() {
        scope.launch { runLoop() }
    }

    fun stop() {
        scope.cancel()
        runCatching { server?.close() }
        runCatching { client?.close() }
        server = null
        client = null
        sending = false
    }

    /** 主动断开当前客户端（服务端继续监听，等待下一次连接） */
    fun disconnectClient() {
        sending = false
        runCatching { client?.close() }
        client = null
        output = null
    }

    private var sentFrames = 0L

    private fun runLoop() {
        try {
            server = ServerSocket(AppConstants.VIDEO_PORT)
            Log.d(TAG, "Video server listening on ${AppConstants.VIDEO_PORT}")
            while (scope.isActive) {
                val socket = server?.accept() ?: break
                Log.d(TAG, "Video client connected")
                client = socket
                output = socket.getOutputStream()
                // 先下发分辨率，保证监看端创建解码器时知道真实宽高
                videoConfig?.let { cfg ->
                    runCatching { output?.write(cfg); output?.flush() }
                }
                sending = true
                onClientConnected?.invoke()

                while (scope.isActive && sending) {
                    val frame = frameQueue.poll()
                    if (frame != null) {
                        try {
                            output?.write(frame)
                            output?.flush()
                            sentFrames++
                            if (sentFrames % 150 == 1L) Log.d(TAG, "Sent $sentFrames frames total")
                        } catch (e: Exception) {
                            Log.e(TAG, "Video send error", e)
                            break
                        }
                    } else {
                        Thread.sleep(1)
                    }
                }
                sending = false
                onClientDisconnected?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video server error", e)
        }
    }

    /**
     * 设置视频分辨率。若当前已有客户端连接，清空积压帧并把它插到队首，
     * 确保监看端在收到任何视频帧之前先拿到分辨率。
     */
    fun sendVideoConfig(config: ByteArray) {
        videoConfig = config
        if (sending) {
            frameQueue.clear()
            frameQueue.offer(config)
        }
    }

    fun sendFrame(data: ByteArray) {
        if (sending) {
            frameQueue.offer(data)
            if (frameQueue.size > 60) frameQueue.clear()
        }
    }
}
