package com.cameramonitor.app.connection

/**
 * 传输层单例：持有拍摄端的 VideoServer 和 ControlServer。
 *
 * 这样 CameraActivity 在 WiFi Direct 建群成功后即可启动 TCP 服务端，
 * 不必等待用户授权屏幕录制；ScreenCaptureService 仅负责编码并把帧送入此单例。
 */
object TransportHub {
    @Volatile var videoServer: VideoServer? = null
        private set
    @Volatile var controlServer: ControlServer? = null
        private set

    @Volatile var onTouchEvent: ((TouchEvent) -> Unit)? = null

    /** 监看端断开（控制通道 EOF / 收到 DISCONNECT）时回调，用于拍摄端自动停止采集 */
    @Volatile var onClientDisconnected: (() -> Unit)? = null

    fun start() {
        if (videoServer == null) {
            val vs = VideoServer()
            vs.start()
            videoServer = vs
        }
        if (controlServer == null) {
            val cs = ControlServer()
            // 注意：不能用 apply 块内未限定的 onTouchEvent，会遮蔽成 ControlServer
            // 自身的属性造成无限递归（StackOverflowError）。这里显式引用单例属性。
            cs.onTouchEvent = { event -> TransportHub.onTouchEvent?.invoke(event) }
            cs.onClientDisconnected = { TransportHub.onClientDisconnected?.invoke() }
            cs.start()
            controlServer = cs
        }
    }

    fun stop() {
        videoServer?.stop()
        videoServer = null
        controlServer?.stop()
        controlServer = null
        onClientDisconnected = null
        // 注意：不要清空 onTouchEvent —— 它由无障碍服务生命周期管理，
        // 清空后若无障碍服务不重启，触摸转发将永久失效。
    }

    /** 拍摄端主动断开当前监看端（关闭两侧客户端 socket，服务端继续监听） */
    fun disconnectClients() {
        videoServer?.disconnectClient()
        controlServer?.disconnectClient()
    }

    fun sendFrame(data: ByteArray) {
        videoServer?.sendFrame(data)
    }

    /** 下发编码分辨率给监看端（在编码器启动后调用一次） */
    fun sendVideoConfig(width: Int, height: Int) {
        videoServer?.sendVideoConfig(MessageProtocol.encodeVideoConfig(width, height))
    }
}
