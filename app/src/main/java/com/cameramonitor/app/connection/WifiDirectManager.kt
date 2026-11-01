package com.cameramonitor.app.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.MacAddress
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * WiFi Direct 连接管理器。
 *
 * 拍摄端作为 Group Owner（服务端），监听 VIDEO_PORT 和 CONTROL_PORT；
 * 监看端作为客户端连接到拍摄端。
 */
class WifiDirectManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiDirectManager"
    }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, context.mainLooper, null)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state

    private val _peerList = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peerList: StateFlow<List<WifiP2pDevice>> = _peerList

    private var groupOwnerAddress: String? = null

    /** 标记是否处于连接失败状态，避免被 CONNECTION_CHANGED 广播覆盖 */
    @Volatile private var connectionFailed = false

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        _state.value = ConnectionState.Error("WiFi Direct 未开启")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(channel) { peers ->
                        _peerList.value = peers.deviceList.toList()
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    }
                    val group = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    }
                    handleConnectionChanged(info, group)
                }
            }
        }
    }

    fun start() {
        context.registerReceiver(receiver, intentFilter)
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        manager.stopPeerDiscovery(channel, null)
        manager.removeGroup(channel, null)
    }

    /** 定位服务是否开启（WiFi Direct 设备发现的前提） */
    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    /** 发现附近的 WiFi Direct 设备 */
    fun discoverPeers() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "discoverPeers success")
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "discoverPeers failed: $reason")
                _state.value = ConnectionState.Error("设备发现失败 ($reason)")
            }
        })
    }

    /**
     * 拍摄端：先清理旧群组，再创建群组（成为 Group Owner）。
     */
    fun createGroup() {
        connectionFailed = false
        _state.value = ConnectionState.CreatingGroup
        // 先尝试清理可能残留的旧群，避免 BUSY 错误
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "removeGroup success, creating new group")
                doCreateGroup()
            }
            override fun onFailure(reason: Int) {
                // 没有旧群也正常，直接创建
                Log.d(TAG, "removeGroup failed (no existing group): $reason, creating new group")
                doCreateGroup()
            }
        })
    }

    private fun doCreateGroup() {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "createGroup success")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "createGroup failed: $reason, retrying once after delay")
                // 首次 BUSY（reason=2）时延迟重试一次
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.d(TAG, "createGroup retry success")
                        }
                        override fun onFailure(r: Int) {
                            Log.e(TAG, "createGroup retry failed: $r")
                            _state.value = ConnectionState.Error("创建群组失败 ($r)，请重启 WiFi 后重试")
                        }
                    })
                }, 1500)
            }
        })
    }

    /**
     * 监看端：连接到指定设备。
     */
    @Suppress("DEPRECATION")
    fun connectToDevice(device: WifiP2pDevice) {
        connectionFailed = false
        _state.value = ConnectionState.Connecting
        // 华为/荣耀等 ROM 上 WifiP2pConfig.Builder.build() 强制要求 networkName 非空，
        // 否则抛 IllegalStateException。直接用 deprecated 构造器更稳妥，全版本兼容。
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0
            wps.setup = android.net.wifi.WpsInfo.PBC
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "connect success")
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "connect failed: $reason")
                connectionFailed = true
                _state.value = ConnectionState.Error("连接失败 ($reason)")
            }
        })
    }

    fun removeGroup() {
        manager.removeGroup(channel, null)
        _state.value = ConnectionState.Idle
    }

    private fun handleConnectionChanged(info: WifiP2pInfo?, group: WifiP2pGroup?) {
        if (connectionFailed) return
        info ?: return
        if (info.groupFormed && info.isGroupOwner) {
            groupOwnerAddress = info.groupOwnerAddress?.hostAddress
            _state.value = ConnectionState.GroupOwnerReady(groupOwnerAddress ?: "")
            Log.d(TAG, "I am Group Owner at $groupOwnerAddress")
        } else if (info.groupFormed) {
            groupOwnerAddress = info.groupOwnerAddress?.hostAddress
            _state.value = ConnectionState.ClientReady(groupOwnerAddress ?: "")
            Log.d(TAG, "Connected to Group Owner at $groupOwnerAddress")
        } else {
            _state.value = ConnectionState.Idle
        }
    }
}

sealed class ConnectionState {
    object Idle : ConnectionState()
    object CreatingGroup : ConnectionState()
    data class GroupOwnerReady(val address: String) : ConnectionState()
    data class ClientReady(val serverAddress: String) : ConnectionState()
    object Connecting : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
