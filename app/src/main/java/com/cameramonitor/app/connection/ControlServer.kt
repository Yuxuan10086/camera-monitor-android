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
import java.net.ServerSocket

/**
 * 拍摄端：控制指令 TCP 服务端（监听 CONTROL_PORT）。
 * 接收监看端发来的触摸事件。
 */
class ControlServer {

    companion object {
        private const val TAG = "ControlServer"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var server: ServerSocket? = null
    private var currentClient: java.net.Socket? = null

    var onTouchEvent: ((TouchEvent) -> Unit)? = null
    /** 监看端控制通道断开时回调（EOF 或收到 MSG_DISCONNECT） */
    var onClientDisconnected: (() -> Unit)? = null

    fun start() {
        scope.launch { runLoop() }
    }

    fun stop() {
        scope.cancel()
        runCatching { server?.close() }
        runCatching { currentClient?.close() }
        server = null
        currentClient = null
    }

    /** 主动断开当前客户端（服务端继续监听） */
    fun disconnectClient() {
        runCatching { currentClient?.close() }
        currentClient = null
    }

    private fun runLoop() {
        try {
            server = ServerSocket(AppConstants.CONTROL_PORT)
            Log.d(TAG, "Control server listening on ${AppConstants.CONTROL_PORT}")
            while (scope.isActive) {
                val client = server?.accept() ?: break
                Log.d(TAG, "Control client connected")
                currentClient = client
                val input = BufferedInputStream(client.getInputStream())
                while (scope.isActive) {
                    val msg = MessageProtocol.readMessage(input) ?: break
                    val (type, payload) = msg
                    when (type) {
                        MessageProtocol.MSG_TOUCH -> {
                            onTouchEvent?.invoke(MessageProtocol.parseTouch(payload))
                        }
                        MessageProtocol.MSG_DISCONNECT -> break
                    }
                }
                Log.d(TAG, "Control client disconnected")
                runCatching { client.close() }
                if (currentClient == client) currentClient = null
                onClientDisconnected?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Control server error", e)
        }
    }
}
