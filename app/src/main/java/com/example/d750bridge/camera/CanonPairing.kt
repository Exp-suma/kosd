package com.example.d750bridge.camera

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import com.example.d750bridge.AppLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Canon「EOS Utility（リモート操作）」モードのペアリング支援。
 *
 * このモードのカメラは、自分からネットワーク上の「パソコン（EOS Utility）」を SSDP で探し、
 * 見つかった相手だけを接続先として受け付ける（見つからないとカメラに「Err 11 接続先が見つかりません」）。
 * そこでアプリが Windows の UPnP 機器のふりをして、カメラの検索に応答し、機器の説明（XML）を返す。
 *  - 応答の形式は ptpip-canon-helpers（reyalp, MIT）の実装に合わせる（カメラは Windows の応答しか認識しない）。
 *  - UUID は PTP/IP の Init_Command_Request で送る GUID と同じ値にする（ペアリング後も毎回この応答が必要）。
 */
class CanonUpnpResponder(
    context: Context,
    private val network: Network,
    private val phoneIp: String,
    guid: ByteArray,
    private val name: String = "CamBridge"
) {
    val uuid: String = guid.joinToString("") { "%02x".format(it) }.let {
        "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}"
    }
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var udp: MulticastSocket? = null
    @Volatile private var http: ServerSocket? = null
    @Volatile var running = false
        private set
    /** カメラからの検索・説明取得の回数（画面の案内・ログ用）。 */
    @Volatile var searches = 0
        private set
    @Volatile var descFetches = 0
        private set
    private var httpPort = 0

    fun start() {
        if (running) return
        running = true
        try {
            lock = wifi.createMulticastLock("CamBridge-canon").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { AppLog.w("Canonペアリング: MulticastLock取得失敗 ${e.message}") }
        try {
            val srv = try { ServerSocket(8043, 8, InetAddress.getByName(phoneIp)) } catch (_: Exception) { ServerSocket(0, 8, InetAddress.getByName(phoneIp)) }
            http = srv; httpPort = srv.localPort
            Thread({ httpLoop(srv) }, "canon-upnp-http").apply { isDaemon = true; start() }
        } catch (e: Exception) { AppLog.e("Canonペアリング: 説明サーバーを開始できません", e) }
        try {
            val s = MulticastSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(1900))
            try { network.bindSocket(s) } catch (e: Exception) { AppLog.w("Canonペアリング: SSDPソケットをWi-Fiに束縛できません ${e.message}") }
            val nif = NetworkInterface.getByInetAddress(InetAddress.getByName(phoneIp))
            s.joinGroup(InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900), nif)
            s.soTimeout = 1000
            udp = s
            Thread({ udpLoop(s) }, "canon-upnp-ssdp").apply { isDaemon = true; start() }
        } catch (e: Exception) { AppLog.e("Canonペアリング: SSDP待ち受けを開始できません", e) }
        AppLog.i("Canonペアリング支援を開始: uuid=$uuid 説明=http://$phoneIp:$httpPort/desc.xml")
    }

    fun stop() {
        running = false
        try { udp?.close() } catch (_: Exception) {}
        try { http?.close() } catch (_: Exception) {}
        udp = null; http = null
        try { lock?.release() } catch (_: Exception) {}
        lock = null
    }

    private fun response(st: String): ByteArray = (
        "HTTP/1.1 200 OK\r\n" +
            "ST: $st\r\n" +
            "USN: uuid:$uuid::$st\r\n" +
            "LOCATION: http://$phoneIp:$httpPort/desc.xml\r\n" +
            "Cache-Control: max-age=900\r\n" +
            "Server:Microsoft-Windows/10.0 UPnP/1.0 UPnP-Device-Host/1.0\r\n" +
            "Ext: \r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

    private fun udpLoop(s: MulticastSocket) {
        val buf = ByteArray(4096)
        while (running && !s.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val text = String(p.data, 0, p.length, Charsets.UTF_8)
                if (!text.startsWith("M-SEARCH")) continue
                val st = Regex("(?im)^ST:\\s*(.*)$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
                val from = p.address.hostAddress ?: continue
                if (from == phoneIp) continue
                AppLog.i("Canonペアリング: $from からの検索 ST=$st")
                val answerSt = when {
                    st.equals("ssdp:all", true) || st.equals("upnp:rootdevice", true) -> "upnp:rootdevice"
                    st.contains("canon", true) || st.startsWith("urn:schemas-upnp-org:device", true) -> st
                    st.equals("uuid:$uuid", true) -> st
                    else -> null
                } ?: continue
                searches++
                val out = response(answerSt)
                s.send(DatagramPacket(out, out.size, p.socketAddress))
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) AppLog.w("Canonペアリング: SSDP受信エラー ${e.message}")
                if (s.isClosed) break
            }
        }
    }

    private fun descXml(): String =
        "<?xml version=\"1.0\"?>" +
            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<device>" +
            "<UDN>uuid:$uuid</UDN>" +
            // Windows の friendlyName は「コンピューター名: ユーザー名:」。カメラはコンピューター名の部分を表示する
            "<friendlyName>$name: $name:</friendlyName>" +
            "<deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>" +
            "<manufacturer>CamBridge</manufacturer>" +
            "<modelName>CamBridge PTP/IP</modelName>" +
            "<modelNumber>1.0</modelNumber>" +
            "</device></root>"

    private fun httpLoop(srv: ServerSocket) {
        while (running && !srv.isClosed) {
            val c: Socket = try { srv.accept() } catch (_: Exception) { break }
            Thread {
                c.use { sock ->
                    try {
                        sock.soTimeout = 3000
                        val rd = BufferedReader(InputStreamReader(sock.getInputStream()))
                        val req = rd.readLine().orEmpty()
                        while (true) { val l = rd.readLine() ?: break; if (l.isEmpty()) break }
                        descFetches++
                        AppLog.i("Canonペアリング: ${sock.inetAddress.hostAddress} が説明を取得 ($req)")
                        val body = descXml().toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Server: Microsoft-Windows/10.0 UPnP/1.0 UPnP-Device-Host/1.0\r\n" +
                            "Connection: close\r\n\r\n"
                        sock.getOutputStream().apply { write(head.toByteArray(Charsets.UTF_8)); write(body); flush() }
                    } catch (e: Exception) { AppLog.w("Canonペアリング: 説明の送信エラー ${e.message}") }
                }
            }.apply { isDaemon = true; start() }
        }
    }
}

/**
 * EOS Utility が接続の直前に行う検出の再現（EOS R6 Mark III で、これが無いと Init_Command_Request が無視されると報告あり）。
 * SSDP で ICPO-WFTEOSSystemService を検索し、カメラの /upnp/CameraDevDesc.xml を取得する。結果は記録用（失敗しても続行）。
 */
object CanonDiscovery {
    private const val ST = "urn:schemas-canon-com:service:ICPO-WFTEOSSystemService:1"

    fun probe(network: Network, host: String): String {
        val notes = ArrayList<String>()
        try {
            DatagramSocket().use { s ->
                network.bindSocket(s)
                s.soTimeout = 300
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $ST\r\n\r\n".toByteArray()
                s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900))
                s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(host), 1900))
                val end = System.currentTimeMillis() + 1500
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size); s.receive(p)
                        notes.add("SSDP応答 ${p.address.hostAddress}")
                    } catch (_: SocketTimeoutException) { }
                }
            }
        } catch (e: Exception) { notes.add("SSDP失敗 ${e.message}") }
        try {
            Socket().use { s ->
                network.bindSocket(s)
                s.connect(InetSocketAddress(host, 49152), 3000)
                s.soTimeout = 3000
                val req = "GET /upnp/CameraDevDesc.xml HTTP/1.1\r\nUser-Agent: wsCDeviceDetector\r\nHost: $host:49152\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
                s.getOutputStream().write(req.toByteArray())
                val sb = StringBuilder()
                val ins = s.getInputStream()
                val buf = ByteArray(4096)
                try {
                    while (sb.length < 64_000) {
                        val n = ins.read(buf); if (n < 0) break
                        sb.append(String(buf, 0, n, Charsets.UTF_8))
                        if (sb.contains("</root>")) break
                    }
                } catch (_: SocketTimeoutException) { }
                val x = sb.toString()
                val model = Regex("<modelName>(.*?)</modelName>").find(x)?.groupValues?.get(1)
                val mode = when {
                    x.contains("ICPO-WFTEOSSystemService") -> "EOS Utility(PTP/IP)モード"
                    x.contains("X_accessURL") -> "CCAPIモード"
                    x.isBlank() -> "応答なし"
                    else -> "不明なモード"
                }
                notes.add("カメラ説明: ${model ?: "?"} / $mode")
            }
        } catch (e: Exception) { notes.add("カメラ説明の取得失敗 ${e.javaClass.simpleName}") }
        val r = notes.joinToString(" / ")
        AppLog.i("Canon接続前の検出: $r")
        return r
    }
}
