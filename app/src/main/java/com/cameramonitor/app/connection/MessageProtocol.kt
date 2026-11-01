package com.cameramonitor.app.connection

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 通信协议：在 TCP 流上传输视频帧与控制指令。
 *
 * 消息格式：
 *   [4 bytes] 消息类型 (MSG_VIDEO / MSG_TOUCH / MSG_HEARTBEAT / MSG_ACK)
 *   [4 bytes] payload 长度 (大端)
 *   [N bytes] payload
 *
 * 视频帧 payload:
 *   [8 bytes] 时间戳 (毫秒)
 *   [4 bytes] flags (0=普通帧, 1=关键帧)
 *   [N bytes] H.264 NALU 数据
 *
 * 触摸事件 payload:
 *   [1 byte]  动作 (0=DOWN, 1=MOVE, 2=UP, 3=CANCEL)
 *   [4 bytes] x (float, 0~1 相对坐标)
 *   [4 bytes] y (float, 0~1 相对坐标)
 *   [8 bytes] 时间戳
 */
object MessageProtocol {

    const val MSG_HEARTBEAT = 0x01
    const val MSG_HEARTBEAT_ACK = 0x02
    const val MSG_VIDEO = 0x10
    /** 视频参数：payload 为 width(4) + height(4)，连接建立后先于任何视频帧发送 */
    const val MSG_VIDEO_CONFIG = 0x11
    const val MSG_TOUCH = 0x20
    const val MSG_DISCONNECT = 0xFF

    const val HEADER_SIZE = 8 // type(4) + length(4)

    fun encodeVideo(timestamp: Long, flags: Int, data: ByteArray): ByteArray {
        val payload = ByteBuffer.allocate(12 + data.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(timestamp)
            .putInt(flags)
            .put(data)
            .array()
        return wrap(MSG_VIDEO, payload)
    }

    fun encodeTouch(action: Int, x: Float, y: Float, timestamp: Long): ByteArray {
        val payload = ByteBuffer.allocate(17)
            .order(ByteOrder.BIG_ENDIAN)
            .put(action.toByte())
            .putFloat(x)
            .putFloat(y)
            .putLong(timestamp)
            .array()
        return wrap(MSG_TOUCH, payload)
    }

    fun encodeVideoConfig(width: Int, height: Int): ByteArray {
        val payload = ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(width)
            .putInt(height)
            .array()
        return wrap(MSG_VIDEO_CONFIG, payload)
    }

    fun parseVideoConfig(payload: ByteArray): Pair<Int, Int>? {
        if (payload.size < 8) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return buf.int to buf.int
    }

    fun encodeHeartbeat(): ByteArray = wrap(MSG_HEARTBEAT, ByteArray(0))

    fun encodeDisconnect(): ByteArray = wrap(MSG_DISCONNECT, ByteArray(0))

    private fun wrap(type: Int, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(type)
            .putInt(payload.size)
            .put(payload)
        return buffer.array()
    }

    /**
     * 从输入流读取一条完整消息，返回 (type, payload)。
     * 阻塞读取，返回 null 表示流结束。
     */
    fun readMessage(input: java.io.InputStream): Pair<Int, ByteArray>? {
        val header = ByteArray(HEADER_SIZE)
        if (!readFully(input, header)) return null
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val type = buf.int
        val length = buf.int
        if (length < 0 || length > 10 * 1024 * 1024) return null // 防御性上限 10MB
        val payload = ByteArray(length)
        if (!readFully(input, payload)) return null
        return type to payload
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    fun parseVideo(payload: ByteArray): Triple<Long, Int, ByteArray> {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val timestamp = buf.long
        val flags = buf.int
        val data = ByteArray(payload.size - 12)
        buf.get(data)
        return Triple(timestamp, flags, data)
    }

    fun parseTouch(payload: ByteArray): TouchEvent {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val action = buf.get().toInt()
        val x = buf.float
        val y = buf.float
        val timestamp = buf.long
        return TouchEvent(action, x, y, timestamp)
    }
}

data class TouchEvent(
    val action: Int,
    val x: Float,   // 0~1 相对宽度
    val y: Float,   // 0~1 相对高度
    val timestamp: Long
) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_MOVE = 1
        const val ACTION_UP = 2
        const val ACTION_CANCEL = 3
    }
}
