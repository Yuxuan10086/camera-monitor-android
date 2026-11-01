package com.cameramonitor.app

/**
 * 应用全局常量
 */
object AppConstants {
    // 配对码长度
    const val PAIRING_CODE_LENGTH = 6
    // 配对码有效期（毫秒）
    const val PAIRING_CODE_TTL = 5 * 60 * 1000L

    // 服务端口
    const val VIDEO_PORT = 8080
    const val CONTROL_PORT = 8081

    // 视频参数
    /** 编码最长边（不是画面宽度），实际宽高由屏幕比例缩放得到 */
    const val VIDEO_WIDTH = 1280
    /** 兜底分辨率：仅在未收到拍摄端下发的分辨率时使用（竖屏） */
    const val FALLBACK_VIDEO_WIDTH = 720
    const val FALLBACK_VIDEO_HEIGHT = 1600
    const val VIDEO_FPS = 30
    const val VIDEO_BITRATE = 4_000_000
    const val VIDEO_I_FRAME_INTERVAL = 2

    // 连接参数
    const val HEARTBEAT_INTERVAL = 3_000L
    const val HEARTBEAT_TIMEOUT = 7_000L
    const val RECONNECT_MAX_DURATION = 15_000L
    const val RECONNECT_INTERVAL = 2_000L

    // 通知
    const val NOTIFICATION_CHANNEL_CAPTURE = "screen_capture"
    const val NOTIFICATION_CHANNEL_RECEIVE = "screen_receive"
    const val NOTIFICATION_ID_CAPTURE = 1001
    const val NOTIFICATION_ID_RECEIVE = 1002
}
