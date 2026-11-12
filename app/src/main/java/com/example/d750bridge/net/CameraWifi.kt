package com.example.d750bridge.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import com.example.d750bridge.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address

enum class WifiPhase(val label: String) {
    IDLE("未接続"),
    REQUESTING("接続要求中（システムのダイアログを確認）"),
    CONNECTED("接続済み"),
    LOST("切断された"),
    UNAVAILABLE("接続できなかった")
}

data class WifiSnapshot(
    val phase: WifiPhase = WifiPhase.IDLE,
    val ssid: String? = null,
    val phoneIp: String? = null,     // スマホ自身のIP（カメラIPではない）
    val netmask: String? = null,
    val gateway: String? = null,     // カメラAPがDHCPで通知した場合のみ
    val dhcpServer: String? = null,  // カメラAPに接続している場合、多くはカメラ自身
    val message: String? = null,
    /** true = 端末のWi-Fi設定で接続済みのネットワークをそのまま使っている */
    val existing: Boolean = false
)

/**
 * カメラのアクセスポイントへ WifiNetworkSpecifier で接続する。
 * bindProcessToNetwork は使わない。カメラ通信のソケットだけを Network に束縛し、
 * それ以外の通信は携帯回線を使えるようにする。
 */
class CameraWifi(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wm = context.applicationContext.getSystemService(WifiManager::class.java)

    private val _state = MutableStateFlow(WifiSnapshot())
    val state: StateFlow<WifiSnapshot> = _state.asStateFlow()

    @Volatile
    var network: Network? = null
        private set

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun isWifiEnabled(): Boolean = wm.isWifiEnabled

    fun connect(ssidPrefix: String, passphrase: String?) {
        disconnect()
        if (!wm.isWifiEnabled) {
            AppLog.w("スマホのWi-Fiがオフです。オンにしてください")
        }
        if (ssidPrefix.isBlank()) {
            _state.value = WifiSnapshot(phase = WifiPhase.UNAVAILABLE, message = "SSIDの先頭を入力してください（または「接続済みのWi-Fiを使う」）")
            return
        }
        val sb = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(ssidPrefix, PatternMatcher.PATTERN_PREFIX))
        if (!passphrase.isNullOrEmpty()) sb.setWpa2Passphrase(passphrase)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(sb.build())
            .build()

        val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) {
                this@CameraWifi.network = network
                AppLog.i("カメラWi-Fi: onAvailable")
                _state.update { it.copy(phase = WifiPhase.CONNECTED, message = null) }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val wifiInfo = caps.transportInfo as? WifiInfo
                val ssid = wifiInfo?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
                _state.update { it.copy(ssid = ssid) }
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                applyLinkProperties(lp)
            }

            override fun onLost(network: Network) {
                AppLog.w("カメラWi-Fi: onLost（切断）")
                this@CameraWifi.network = null
                _state.update { it.copy(phase = WifiPhase.LOST, message = "接続が失われました") }
            }

            override fun onUnavailable() {
                AppLog.e("カメラWi-Fi: onUnavailable（接続失敗またはタイムアウト/拒否）")
                this@CameraWifi.network = null
                _state.update { it.copy(phase = WifiPhase.UNAVAILABLE, message = "接続できませんでした") }
            }
        }
        callback = cb
        _state.value = WifiSnapshot(phase = WifiPhase.REQUESTING)
        AppLog.i("カメラWi-Fi接続要求: SSID前方一致='$ssidPrefix' 暗号化=${if (passphrase.isNullOrEmpty()) "なし" else "WPA2"}")
        try {
            cm.requestNetwork(request, cb, 60_000)
        } catch (e: Exception) {
            AppLog.e("requestNetwork が例外", e)
            _state.value = WifiSnapshot(phase = WifiPhase.UNAVAILABLE, message = e.message)
        }
    }

    /**
     * 端末のWi-Fi設定ですでに接続しているネットワーク（カメラのAP）をそのまま使う。
     * 機種によってはパスワード入力をOSの設定画面で行うほうが確実なため。
     */
    fun useExisting() {
        disconnect()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) {
                this@CameraWifi.network = network
                AppLog.i("接続済みWi-Fiを使用: onAvailable")
                _state.update { it.copy(phase = WifiPhase.CONNECTED, message = null, existing = true) }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val wifiInfo = caps.transportInfo as? WifiInfo
                val ssid = wifiInfo?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
                _state.update { it.copy(ssid = ssid) }
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = applyLinkProperties(lp)
            override fun onLost(network: Network) {
                AppLog.w("接続済みWi-Fi: onLost（切断）")
                this@CameraWifi.network = null
                _state.update { it.copy(phase = WifiPhase.LOST, message = "Wi-Fiが切断されました") }
            }
        }
        callback = cb
        _state.value = WifiSnapshot(phase = WifiPhase.REQUESTING, existing = true, message = "接続済みのWi-Fiを確認中…")
        try {
            cm.registerNetworkCallback(request, cb)
        } catch (e: Exception) {
            AppLog.e("registerNetworkCallback が例外", e)
            _state.value = WifiSnapshot(phase = WifiPhase.UNAVAILABLE, message = e.message)
        }
    }

    fun disconnect() {
        callback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (e: Exception) { AppLog.w("unregister失敗: ${e.message}") }
        }
        callback = null
        network = null
        _state.value = WifiSnapshot()
    }

    private fun applyLinkProperties(lp: LinkProperties) {
        val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
        val ip = v4?.address?.hostAddress
        val mask = v4?.prefixLength?.let { prefixToMask(it) }
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val dhcp = (lp.dhcpServerAddress as? Inet4Address)?.hostAddress?.takeIf { it != "0.0.0.0" }
        AppLog.i("LinkProperties: phoneIp=$ip mask=$mask gateway=${gw ?: "なし"} dhcp=${dhcp ?: "なし"}")
        _state.update { it.copy(phoneIp = ip, netmask = mask, gateway = gw, dhcpServer = dhcp) }
    }

    private fun prefixToMask(prefix: Int): String {
        val m = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return "${(m ushr 24) and 255}.${(m ushr 16) and 255}.${(m ushr 8) and 255}.${m and 255}"
    }
}
