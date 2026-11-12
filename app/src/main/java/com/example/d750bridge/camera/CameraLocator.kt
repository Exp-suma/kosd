package com.example.d750bridge.camera

import android.net.Network
import com.example.d750bridge.AppLog
import com.example.d750bridge.net.WifiSnapshot
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** SSDP（UPnP）の応答。 */
data class SsdpHit(val ip: String, val server: String, val st: String, val usn: String, val location: String) {
    val looksCanon: Boolean get() = listOf(server, st, usn, location).any { it.contains("canon", ignoreCase = true) }
    fun short(): String = "$ip ${server.take(60)} ${st.ifBlank { usn }.take(80)}"
}

/**
 * カメラのIPアドレスを自動で探す。
 *  1. 候補（DHCPサーバー＝カメラAPのことが多い / ゲートウェイ / メーカー既定値 / よく使う番号）へ並列にTCP接続
 *  2. 見つからなければ SSDP（UPnP）で機器を探す（Canon機は自分を広告する場合がある）
 *  3. それでも無ければ、同じ /24 の全アドレスへTCP接続（ルーター経由で接続しているカメラ向け）
 */
object CameraLocator {
    fun candidates(w: WifiSnapshot, brand: CameraBrand): List<String> = buildList {
        w.dhcpServer?.let(::add)
        w.gateway?.let(::add)
        addAll(brand.defaultHosts)
        w.phoneIp?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() }?.let { p ->
            listOf("1", "2", "10", "100", "254").forEach { add("$p.$it") }
        }
    }.distinct().filter { it != w.phoneIp && it.isNotBlank() }

    private fun tcpOpen(network: Network, host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { s ->
            network.bindSocket(s)
            s.connect(InetSocketAddress(host, port), timeoutMs)
        }
        true
    } catch (_: Exception) { false }

    fun firstReachable(network: Network, hosts: List<String>, port: Int, timeoutMs: Int = 900): String? {
        if (hosts.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(minOf(hosts.size, 8))
        try {
            val futures = hosts.map { h -> pool.submit(Callable { tcpOpen(network, h, port, timeoutMs) }) }
            for ((h, f) in hosts.zip(futures)) {
                val ok = try { f.get(timeoutMs + 500L, TimeUnit.MILLISECONDS) } catch (_: Exception) { false }
                if (ok) { AppLog.i("カメラ検出: $h:$port が応答"); return h }
            }
            AppLog.w("カメラ検出: ${hosts.joinToString()} のポート $port は応答なし")
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    /** 同じ /24 の全アドレスでポートが開いているものを返す（スマホ自身は除く）。 */
    fun scanSubnet(network: Network, phoneIp: String?, port: Int, timeoutMs: Int = 450): List<String> {
        val prefix = phoneIp?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val hosts = (1..254).map { "$prefix.$it" }.filter { it != phoneIp }
        val pool = Executors.newFixedThreadPool(48)
        try {
            val futures = hosts.map { h -> h to pool.submit(Callable { tcpOpen(network, h, port, timeoutMs) }) }
            val found = futures.filter { (_, f) -> try { f.get(timeoutMs * 8L + 2000L, TimeUnit.MILLISECONDS) } catch (_: Exception) { false } }.map { it.first }
            AppLog.i("サブネット探索 $prefix.0/24 ポート$port: ${if (found.isEmpty()) "応答なし" else found.joinToString()}")
            return found
        } finally {
            pool.shutdownNow()
        }
    }

    /** SSDP M-SEARCH を送り、応答した機器を集める（ログにすべて出す）。 */
    fun ssdpScan(network: Network, waitMs: Long = 3000): List<SsdpHit> {
        val targets = listOf(
            "ssdp:all", "upnp:rootdevice",
            "urn:schemas-canon-com:service:ICPO-WFTEOSSystemService:1",
            "urn:schemas-canon-com:service:ICPO-SmartPhoneEOSSystemService:1",
            "urn:schemas-canon-com:service:CameraConnectedMobileService:1"
        )
        val hits = LinkedHashMap<String, SsdpHit>()
        try {
            DatagramSocket().use { s ->
                network.bindSocket(s)
                s.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                for (st in targets) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                    s.send(DatagramPacket(msg, msg.size, group, 1900))
                }
                val end = System.currentTimeMillis() + waitMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val text = String(p.data, 0, p.length)
                        fun h(name: String) = Regex("(?im)^$name:\\s*(.*)$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
                        val ip = p.address.hostAddress ?: continue
                        val hit = SsdpHit(ip, h("SERVER"), h("ST"), h("USN"), h("LOCATION"))
                        if (hits.put("$ip|${hit.st}|${hit.usn}", hit) == null) AppLog.i("SSDP応答: ${hit.short()} LOCATION=${hit.location}")
                    } catch (_: SocketTimeoutException) { }
                }
            }
        } catch (e: Exception) {
            AppLog.w("SSDP探索に失敗: ${e.message}")
        }
        return hits.values.toList()
    }

    /** 15740 が開くまで待つ（カメラがペアリング待ちのあと待ち受けを始める場合）。 */
    fun waitOpen(network: Network, host: String, port: Int, totalMs: Long, onTick: (Int) -> Unit): Boolean {
        val end = System.currentTimeMillis() + totalMs
        while (System.currentTimeMillis() < end) {
            if (tcpOpen(network, host, port, 800)) return true
            onTick(((end - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0))
            Thread.sleep(1200)
        }
        return false
    }
}
