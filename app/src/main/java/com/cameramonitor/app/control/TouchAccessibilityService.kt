package com.cameramonitor.app.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.cameramonitor.app.connection.TouchEvent
import com.cameramonitor.app.connection.TransportHub

/**
 * 拍摄端：触摸注入无障碍服务。
 *
 * 接收监看端发来的触摸事件（相对坐标 0~1），
 * 通过 dispatchGesture 注入到系统，实现远程控制。
 *
 * ControlServer 统一由 TransportHub 管理，本服务只负责注入手势，
 * 避免与 TransportHub 的 ControlServer 产生端口绑定冲突。
 */
class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchService"
        var instance: TouchAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
        // 监听来自监看端的触摸事件
        TransportHub.onTouchEvent = { event -> injectTouch(event) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理事件
    }

    override fun onInterrupt() {
        // 服务中断
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        TransportHub.onTouchEvent = null
    }

    /**
     * 将相对坐标的触摸事件注入系统。
     */
    fun injectTouch(event: TouchEvent) {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val x = event.x * metrics.widthPixels
        val y = event.y * metrics.heightPixels

        when (event.action) {
            TouchEvent.ACTION_DOWN -> {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                    .build()
                dispatchGesture(gesture, null, null)
            }
            TouchEvent.ACTION_MOVE -> {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 16))
                    .build()
                dispatchGesture(gesture, null, null)
            }
            TouchEvent.ACTION_UP -> {
                val path = Path().apply {
                    moveTo(x, y)
                    lineTo(x, y)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 16))
                    .build()
                dispatchGesture(gesture, null, null)
            }
        }
    }
}
