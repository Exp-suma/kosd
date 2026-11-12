package com.example.d750bridge.camera

import android.net.Network
import com.example.d750bridge.AppLog
import com.example.d750bridge.ptp.PtpIpClient
import com.example.d750bridge.ptp.PtpResult
import com.example.d750bridge.ptp.ShutterSpeed
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

/** カメラWi-Fiに束縛したHTTP通信（他の通信はモバイル回線のまま）。 */
internal object CamHttp {
    @Volatile var current: HttpURLConnection? = null

    fun request(network: Network, url: String, body: String? = null, connectMs: Int = 4000, readMs: Int = 10_000): Pair<Int, String> {
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        current = conn
        try {
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.useCaches = false
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return code to text
        } finally {
            conn.disconnect()
            if (current === conn) current = null
        }
    }

    fun abort() { try { current?.disconnect() } catch (_: Exception) {} }
}

/**
 * Sony Camera Remote API（JSON-RPC over HTTP）。
 * SSDP（urn:schemas-sony-com:service:ScalarWebAPI:1）でエンドポイントを探し、見つからなければ既定のURLを試す。
 * ライブビューは startLiveview が返すURLのパケットストリーム（共通ヘッダ8B＋ペイロードヘッダ128B＋JPEG）。
 */
class SonyDriver(private val network: Network, hostHint: String) : CameraDriver {
    override val brand = CameraBrand.SONY
    override var host: String = hostHint
        private set
    override var caps = CamCaps()
        private set
    override var identity: CameraIdentity? = null
        private set
    override var onEvent: ((Int, List<Int>) -> Unit)? = null

    private var endpoint = ""
    private var apis: Set<String> = emptySet()
    private var nextId = 1
    @Volatile private var lv: SonyLiveStream? = null

    // 共通単位 → カメラの文字列/値
    private val isoMap = HashMap<Long, String>()
    private val fMap = HashMap<Long, String>()
    private val ssMap = HashMap<Long, String>()
    private val evMap = HashMap<Long, Int>()

    private data class Reply(val result: JSONArray?, val err: Int, val msg: String) {
        val ok get() = err == 0
        fun toRc(): PtpResult = when {
            ok -> Rc.ok()
            err == 40401 || err == 40402 || err == 40403 -> Rc.busy("(Sony $err $msg)")
            err == 12 || err == 15 -> Rc.fail("(Sony $err $msg)", Rc.NOT_SUPPORTED)
            else -> Rc.fail("(Sony $err $msg)")
        }
    }

    private fun rpc(method: String, params: JSONArray = JSONArray(), readMs: Int = 10_000, version: String = "1.0"): Reply {
        val body = JSONObject().put("method", method).put("params", params).put("id", nextId++).put("version", version).toString()
        val (code, text) = CamHttp.request(network, endpoint, body, readMs = readMs)
        if (text.isBlank()) return Reply(null, -1, "HTTP $code")
        val o = JSONObject(text)
        o.optJSONArray("error")?.let { e -> return Reply(null, e.optInt(0, -1), e.optString(1, "")) }
        return Reply(o.optJSONArray("result") ?: JSONArray(), 0, "")
    }

    // ------------------------------------------------------------------
    // 検出・接続
    // ------------------------------------------------------------------
    private fun ssdpLocation(): String? {
        val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
            "ST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n\r\n").toByteArray()
        DatagramSocket().use { s ->
            network.bindSocket(s)
            s.soTimeout = 700
            val group = InetAddress.getByName("239.255.255.250")
            val end = System.nanoTime() + 3_000_000_000L
            var sent = 0
            val buf = ByteArray(2048)
            while (System.nanoTime() < end) {
                if (sent < 3) { s.send(DatagramPacket(msg, msg.size, group, 1900)); sent++ }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val text = String(p.data, 0, p.length)
                    Regex("(?im)^LOCATION:\\s*(\\S+)").find(text)?.let { return it.groupValues[1] }
                } catch (_: java.net.SocketTimeoutException) { }
            }
        }
        return null
    }

    private fun discover(): Pair<String, String?>? {
        val loc = try { ssdpLocation() } catch (e: Exception) { AppLog.w("Sony: SSDP失敗 ${e.message}"); null } ?: return null
        AppLog.i("Sony: SSDP応答 LOCATION=$loc")
        val xml = CamHttp.request(network, loc).second
        val model = Regex("<modelName>(.*?)</modelName>").find(xml)?.groupValues?.get(1)
            ?: Regex("<friendlyName>(.*?)</friendlyName>").find(xml)?.groupValues?.get(1)
        val services = Regex("<av:X_ScalarWebAPI_Service>(.*?)</av:X_ScalarWebAPI_Service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        for (m in services) {
            val block = m.groupValues[1]
            val type = Regex("<av:X_ScalarWebAPI_ServiceType>(.*?)</").find(block)?.groupValues?.get(1)?.trim()
            val url = Regex("<av:X_ScalarWebAPI_ActionList_URL>(.*?)</").find(block)?.groupValues?.get(1)?.trim()
            if (type == "camera" && url != null) return "${url.trimEnd('/')}/camera" to model
        }
        return null
    }

    private fun fetchApis(): Set<String> {
        val r = rpc("getAvailableApiList")
        if (!r.ok) throw IOException("getAvailableApiList 失敗 (Sony ${r.err} ${r.msg})")
        val arr = r.result?.optJSONArray(0) ?: return emptySet()
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    override fun open(): CameraIdentity {
        val d = discover()
        var model = d?.second
        val candidates = buildList {
            d?.first?.let(::add)
            add("http://$host:8080/sony/camera"); add("http://$host:10000/sony/camera")
        }.distinct()
        var lastErr: Exception? = null
        for (ep in candidates) {
            endpoint = ep
            try { apis = fetchApis(); lastErr = null; break } catch (e: Exception) { lastErr = e }
        }
        lastErr?.let { throw IOException("Sony Camera Remote API に接続できません（${it.message}）。カメラで「スマートフォン操作」を起動しているか確認してください", it) }
        host = URL(endpoint).host
        AppLog.i("Sony: エンドポイント $endpoint / API ${apis.size}件")
        if ("startRecMode" in apis) {
            val r = rpc("startRecMode")
            AppLog.i("Sony: startRecMode ${if (r.ok) "OK" else "${r.err} ${r.msg}"}")
            for (i in 0 until 8) {           // 撮影モードへの切替が終わると使えるAPIが増える
                Thread.sleep(500)
                val now = try { fetchApis() } catch (_: Exception) { apis }
                if (now.size > apis.size || "actTakePicture" in now) { apis = now; if ("actTakePicture" in now) break }
            }
        }
        if (model == null) model = try { rpc("getApplicationInfo").result?.optString(0) } catch (_: Exception) { null }
        caps = CamCaps(
            liveView = "startLiveview" in apis,
            capture = "actTakePicture" in apis,
            touchAf = "setTouchAFPosition" in apis || "actHalfPressShutter" in apis,
            afReadyPoll = false,
            mfDrive = false,
            media = false,
            nativeBurst = false,
            exposure = apis.any { it.startsWith("set") && (it.contains("IsoSpeed") || it.contains("FNumber") || it.contains("ShutterSpeed") || it.contains("ExposureCompensation")) },
            focusModes = if ("setFocusMode" in apis) setOf(FocusKind.AFS, FocusKind.AFC, FocusKind.MF) else emptySet(),
            movie = "startMovieRec" in apis || "setShootMode" in apis
        )
        return CameraIdentity(brand, "Sony", model ?: "Camera Remote API", "", "Camera Remote API", host, caps).also { identity = it }
    }

    // ------------------------------------------------------------------
    // ライブビュー
    // ------------------------------------------------------------------
    override fun startLiveView(): PtpResult {
        if ("startLiveview" !in apis) return Rc.unsupported("ライブビュー")
        val r = rpc("startLiveview")
        if (!r.ok) return r.toRc()
        val url = r.result?.optString(0).orEmpty()
        if (url.isEmpty()) return Rc.fail("(ライブビューURLなし)")
        lv?.stop()
        lv = SonyLiveStream(network, url).also { it.start() }
        return Rc.ok()
    }

    override fun endLiveView(): PtpResult {
        lv?.stop(); lv = null
        return if ("stopLiveview" in apis) rpc("stopLiveview").toRc() else Rc.ok()
    }

    override fun liveViewFrame(): LvFrame {
        val s = lv ?: return LvFrame(Rc.GENERAL, null)
        s.error?.let { throw IOException("ライブビュー受信エラー: $it") }
        return s.awaitNew(80)?.let { LvFrame(Rc.OK, it) } ?: LvFrame(Rc.BUSY, null)
    }

    // ------------------------------------------------------------------
    // 撮影・AF
    // ------------------------------------------------------------------
    override fun capture(): PtpResult {
        var r = rpc("actTakePicture", readMs = 30_000)
        if (r.err == 40403) r = rpc("awaitTakePicture", readMs = 30_000)
        if (r.ok) onEvent?.invoke(PtpIpClient.EV_OBJECT_ADDED, emptyList())
        return r.toRc()
    }

    override fun afAt(nx: Float, ny: Float, lvW: Int, lvH: Int): PtpResult {
        if ("setTouchAFPosition" in apis) {
            val p = JSONArray().put((nx.coerceIn(0f, 1f) * 100.0)).put((ny.coerceIn(0f, 1f) * 100.0))
            val r = rpc("setTouchAFPosition", p, readMs = 15_000)
            if (!r.ok) return r.toRc()
            val ok = r.result?.optJSONObject(1)?.optBoolean("AFResult", true) ?: r.result?.optJSONObject(0)?.optBoolean("AFResult", true) ?: true
            return if (ok) Rc.ok() else Rc.fail("(合焦せず)")
        }
        if ("actHalfPressShutter" in apis) {
            return rpc("actHalfPressShutter", readMs = 15_000).toRc().let { if (it.code == Rc.OK) it.copy(note = "（AF位置の指定は非対応・カメラのAFエリアで合焦）") else it }
        }
        return Rc.unsupported("タッチAF")
    }

    override fun cancelAf(): PtpResult = when {
        "cancelTouchAFPosition" in apis -> rpc("cancelTouchAFPosition").toRc()
        "cancelHalfPressShutter" in apis -> rpc("cancelHalfPressShutter").toRc()
        else -> Rc.ok()
    }

    // ------------------------------------------------------------------
    // 露出
    // ------------------------------------------------------------------
    private fun availStrings(method: String): Pair<String?, List<String>>? {
        if (method !in apis) return null
        val r = try { rpc(method) } catch (_: Exception) { return null }
        if (!r.ok) return null
        val res = r.result ?: return null
        val cur = res.optString(0, "").ifEmpty { null }
        val arr = res.optJSONArray(1) ?: JSONArray()
        return cur to (0 until arr.length()).map { arr.getString(it) }
    }

    private fun isoOf(s: String): Long? = if (s.equals("AUTO", true) || s.equals("ISO AUTO", true)) 0L else s.filter(Char::isDigit).toLongOrNull()
    private fun fOf(s: String): Long? = s.toDoubleOrNull()?.let { (it * 100).roundToLong() }
    private fun secOf(s: String): Double? {
        if (s.equals("BULB", true)) return null
        return ShutterSpeed.parseSeconds(s)
    }
    private fun ssKey(s: String): Long? = if (s.equals("BULB", true)) ShutterSpeed.BULB else secOf(s)?.let(ExpFormat::micros)

    override fun readExposure(): ExposureInfo {
        isoMap.clear(); fMap.clear(); ssMap.clear(); evMap.clear()
        val iso = availStrings("getAvailableIsoSpeedRate")
        iso?.second?.forEach { s -> isoOf(s)?.let { isoMap[it] = s } }
        val f = availStrings("getAvailableFNumber")
        f?.second?.forEach { s -> fOf(s)?.let { fMap[it] = s } }
        val ss = availStrings("getAvailableShutterSpeed")
        ss?.second?.forEach { s -> ssKey(s)?.let { ssMap[it] = s } }
        var biasCur: Long? = null
        val biasOpts = ArrayList<Long>()
        if ("getAvailableExposureCompensation" in apis) {
            try {
                val r = rpc("getAvailableExposureCompensation")
                val a = r.result
                if (r.ok && a != null && a.length() >= 4) {
                    val cur = a.getInt(0); val max = a.getInt(1); val min = a.getInt(2); val step = a.getInt(3)
                    val unit = if (step == 2) 500.0 else 1000.0 / 3.0
                    for (i in min..max) { val m = (i * unit).roundToLong(); evMap[m] = i; biasOpts.add(m) }
                    biasCur = (cur * unit).roundToLong()
                }
            } catch (_: Exception) { }
        }
        val mode = if ("getExposureMode" in apis) try { rpc("getExposureMode").result?.optString(0) } catch (_: Exception) { null } else null
        val ssOpts = ssMap.entries.map { (k, s) ->
            ShutterOption(k, s, if (k == ShutterSpeed.BULB) 9999.0 else secOf(s) ?: 0.0)
        }.sortedBy { it.seconds }
        return ExposureInfo(
            shutterOptions = ssOpts,
            shutterRaw = ss?.first?.let(::ssKey),
            shutterLabel = ss?.first,
            modeLabel = mode,
            iso = iso?.first?.let(::isoOf),
            isoOptions = isoMap.keys.sorted(),
            apertureX100 = f?.first?.let(::fOf),
            apertureOptions = fMap.keys.sorted(),
            biasMilli = biasCur,
            biasOptions = biasOpts,
            focus = focusMode()
        )
    }

    override fun setExposure(kind: ExpKind, value: Long): String {
        if (isoMap.isEmpty() && fMap.isEmpty() && ssMap.isEmpty() && evMap.isEmpty()) readExposure()
        fun <V> pick(m: Map<Long, V>): Pair<V, Boolean>? = m[value]?.let { it to false }
            ?: ExpFormat.nearest(m.keys, value)?.let { m.getValue(it) to true }
        val (method, param, label, snapped) = when (kind) {
            ExpKind.ISO -> pick(isoMap)?.let { Quad("setIsoSpeedRate", JSONArray().put(it.first), "ISO ${it.first}", it.second) }
            ExpKind.APERTURE -> pick(fMap)?.let { Quad("setFNumber", JSONArray().put(it.first), "F${it.first}", it.second) }
            ExpKind.SHUTTER -> pick(ssMap)?.let { Quad("setShutterSpeed", JSONArray().put(it.first), it.first, it.second) }
            ExpKind.BIAS -> pick(evMap)?.let { Quad("setExposureCompensation", JSONArray().put(it.first), ExpFormat.biasLabel(value) + " EV", it.second) }
        } ?: return "${kind.label}: カメラから選択肢が届いていません（露出モードによっては変更できません）"
        if (method !in apis) return "${kind.label}: このカメラ/モードでは $method が使えません"
        val r = rpc(method, param)
        if (!r.ok) return "${kind.label} 書き込み失敗: Sony ${r.err} ${r.msg}"
        return "OK: ${kind.label} $label${if (snapped) "［最寄りの値に丸め］" else ""}"
    }

    private data class Quad(val method: String, val param: JSONArray, val label: String, val snapped: Boolean)

    override fun focusMode(): FocusKind? {
        if ("getFocusMode" !in apis) return null
        return when (try { rpc("getFocusMode").result?.optString(0) } catch (_: Exception) { null }) {
            "AF-S", "DMF" -> FocusKind.AFS; "AF-C" -> FocusKind.AFC; "AF-A" -> FocusKind.AFA; "MF" -> FocusKind.MF; else -> null
        }
    }

    override fun setFocusMode(kind: FocusKind): SetResult {
        if ("setFocusMode" !in apis) return SetResult(false, "このカメラはフォーカスモードの切替APIに未対応です")
        val r = rpc("setFocusMode", JSONArray().put(kind.label))
        return if (r.ok) SetResult(true, "${kind.label}に切替えました") else SetResult(false, "${kind.label}への切替に失敗: Sony ${r.err} ${r.msg}")
    }

    /** 撮影モードを動画にしてから録画開始。停止後は静止画モードへ戻す。 */
    override fun startMovie(): PtpResult {
        if ("setShootMode" in apis) {
            val m = rpc("setShootMode", JSONArray().put("movie"))
            if (!m.ok && m.err != 0) AppLog.w("Sony: setShootMode(movie) ${m.err} ${m.msg}")
            Thread.sleep(600)
            apis = try { fetchApis() } catch (_: Exception) { apis }
        }
        if ("startMovieRec" !in apis) return Rc.unsupported("動画撮影（startMovieRec）")
        return rpc("startMovieRec", readMs = 15_000).toRc()
    }
    override fun stopMovie(): PtpResult {
        val r = rpc("stopMovieRec", readMs = 20_000).toRc()
        if ("setShootMode" in apis) { try { rpc("setShootMode", JSONArray().put("still")); apis = fetchApis() } catch (_: Exception) {} }
        if (r.code == Rc.OK) onEvent?.invoke(PtpIpClient.EV_OBJECT_ADDED, emptyList())
        return r
    }

    override fun abort() { lv?.stop(); CamHttp.abort() }
    override fun closeGracefully() { try { endLiveView() } catch (_: Exception) {}; close() }
    override fun close() { lv?.stop(); lv = null }
}

/** Sony ライブビューのパケットストリームを別スレッドで読み、最新のJPEGだけを保持する。 */
internal class SonyLiveStream(private val network: Network, private val url: String) {
    private val latest = AtomicReference<ByteArray?>(null)
    private val seq = AtomicLong(0)
    private var taken = 0L
    @Volatile var error: String? = null
        private set
    @Volatile private var running = true
    @Volatile private var conn: HttpURLConnection? = null
    private val lock = Object()

    fun start() {
        Thread({ runLoop() }, "sony-liveview").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { conn?.disconnect() } catch (_: Exception) {}
    }

    fun awaitNew(timeoutMs: Long): ByteArray? {
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (seq.get() == taken && running && error == null) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return null
                lock.wait(left)
            }
            if (seq.get() == taken) return null
            taken = seq.get()
            return latest.get()
        }
    }

    private fun readFully(ins: InputStream, n: Int): ByteArray {
        val out = ByteArray(n); var off = 0
        while (off < n) { val r = ins.read(out, off, n - off); if (r < 0) throw IOException("ストリーム終了"); off += r }
        return out
    }

    private fun runLoop() {
        try {
            val c = network.openConnection(URL(url)) as HttpURLConnection
            conn = c
            c.connectTimeout = 4000; c.readTimeout = 6000
            val ins = BufferedInputStream(c.inputStream, 256 * 1024)
            while (running) {
                // 共通ヘッダ（先頭0xFF）。ずれた場合は0xFFまで読み飛ばす
                var first = ins.read()
                while (first != 0xFF) { if (first < 0) throw IOException("ストリーム終了"); first = ins.read() }
                val rest = readFully(ins, 7)
                val type = rest[0].toInt() and 0xFF
                val ph = readFully(ins, 128)
                if (ph[0] != 0x24.toByte() || ph[1] != 0x35.toByte() || ph[2] != 0x68.toByte() || ph[3] != 0x79.toByte()) continue
                val size = ((ph[4].toInt() and 0xFF) shl 16) or ((ph[5].toInt() and 0xFF) shl 8) or (ph[6].toInt() and 0xFF)
                val pad = ph[7].toInt() and 0xFF
                if (size <= 0 || size > 4 * 1024 * 1024) continue
                val data = readFully(ins, size)
                if (pad > 0) readFully(ins, pad)
                if (type == 0x01) {
                    latest.set(data)
                    synchronized(lock) { seq.incrementAndGet(); lock.notifyAll() }
                }
            }
        } catch (e: Exception) {
            if (running) error = e.message ?: e.javaClass.simpleName
        } finally {
            running = false
            synchronized(lock) { lock.notifyAll() }
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
