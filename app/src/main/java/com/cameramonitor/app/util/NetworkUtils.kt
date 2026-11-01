package com.cameramonitor.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.util.Log

/**
 * 网络工具：热点模式下获取拍摄端（热点网关）的 IP 地址。
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * 获取当前 WiFi 网络的网关地址（拍摄端 IP）。
     * 优先级：LinkProperties 里过滤出 IPv4 网关 → 兜底尝试常见热点网关。
     * 只返回 IPv4（不返回 fe80:: 等 IPv6 link-local），
     * 因为 Java Socket 连接 IPv6 link-local 必须带接口 ID，直接用会 EINVAL。
     */
    fun getGatewayAddress(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: run {
                Log.w(TAG, "No active network, trying fallback")
                return fallbackGateway()
            }
            val lp: LinkProperties = cm.getLinkProperties(network) ?: run {
                Log.w(TAG, "LinkProperties null, trying fallback")
                return fallbackGateway()
            }

            // 优先从路由表里找 IPv4 网关
            val gateway = lp.routes
                .filter { it.isDefaultRoute && it.gateway != null }
                .map { it.gateway?.hostAddress }
                .firstOrNull { it != null && !it.contains(':') }  // 过滤 IPv6
            if (gateway != null) {
                Log.d(TAG, "Gateway from routes: $gateway")
                return gateway
            }

            // 兜底：从 LinkAddresses 里找私网段并推断网关（常见热点网关是 .1）
            val inferred = inferGatewayFromAddresses(lp)
            if (inferred != null) {
                Log.d(TAG, "Inferred gateway: $inferred")
                return inferred
            }

            // 最终兜底：硬编码常见 Android 热点网关
            val fb = fallbackGateway()
            Log.w(TAG, "Using hardcoded fallback gateway: $fb")
            return fb
        } catch (e: Exception) {
            Log.e(TAG, "Get gateway failed, using fallback", e)
            return fallbackGateway()
        }
    }

    /** 从本机 IP 推断同网段的 .1 网关 */
    private fun inferGatewayFromAddresses(lp: LinkProperties): String? {
        for (la in lp.linkAddresses) {
            val addr = la.address.hostAddress ?: continue
            if (addr.contains(':')) continue
            val parts = addr.split('.')
            if (parts.size == 4 && parts[0] == "192" && parts[1] == "168") {
                // 常见 192.168.43.x / 192.168.1.x / 192.168.31.x
                return "${parts[0]}.${parts[1]}.${parts[2]}.1"
            }
        }
        return null
    }

    /** 硬编码兜底网关列表 */
    private fun fallbackGateway(): String {
        val fallbacks = listOf("192.168.43.1", "192.168.1.1", "192.168.31.1")
        return fallbacks.first()
    }
}
