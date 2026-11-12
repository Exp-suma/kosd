package com.example.d750bridge.camera

import android.net.Network
import com.example.d750bridge.AppLog
import com.example.d750bridge.ptp.PtpIpClient
import com.example.d750bridge.ptp.PtpResult
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Panasonic LUMIX（スマートフォン接続のWi-Fi API）。
 *  - 制御: http://<カメラ>/cam.cgi?mode=...（応答は "ok,..." またはXML <result>ok</result>）
 *  - ライブビュー: mode=startstream&value=<UDPポート> → カメラがUDPでJPEG入りパケットを送ってくる
 *  - 新しい機種は最初に accctrl（接続許可）が必要。カメラ画面で許可するまで待つ。
 * コマンドは公開されている解析情報に基づく。露出値（絞り・シャッター・露出補正）の書式は機種差があり未検証。
 */
class LumixDriver(private val network: Network, override val host: String, private val idHex: String) : CameraDriver {
    override val brand = CameraBrand.LUMIX
    override var caps = CamCaps()
        private set
    override var identity: CameraIdentity? = null
        private set
    override var onEvent: ((Int, List<Int>) -> Unit)? = null

    @Volatile private var udp: DatagramSocket? = null
    private val latest = AtomicReference<ByteArray?>(null)
    private val seq = AtomicLong(0)
    private var taken = 0L
    @Volatile private var lastPacketMs = 0L
    private var lastKeepMs = 0L
    private var lastStreamReqMs = 0L
    private var lvPort = 0
    private val lock = Object()
    private var battery: Int? = null

    private fun cgi(query: String, readMs: Int = 8000): String =
        CamHttp.request(network, "http://$host/cam.cgi?$query", readMs = readMs).second.trim()

    private fun isOk(t: String) = t.startsWith("ok") || t.contains("<result>ok</result>")
    private fun errOf(t: String): String = Regex("<result>(.*?)</result>").find(t)?.groupValues?.get(1) ?: t.substringBefore(',').take(40)
    private fun toRc(t: String, what: String): PtpResult = when {
        isOk(t) -> Rc.ok()
        errOf(t).contains("busy") -> Rc.busy("(LUMIX ${errOf(t)})")
        errOf(t).contains("non_support") -> Rc.fail("(LUMIX $what: ${errOf(t)})", Rc.NOT_SUPPORTED)
        else -> Rc.fail("(LUMIX $what: ${errOf(t)})")
    }

    override fun open(): CameraIdentity {
        // 1) 接続許可（新しい機種）。カメラ画面で許可されるまで最大40秒待つ
        val uuid = "4D454930-0100-1000-8001-" + idHex.padEnd(12, '0').take(12).uppercase()
        val end = System.currentTimeMillis() + 40_000
        while (true) {
            val t = try { cgi("mode=accctrl&type=req_acc&value=$uuid&value2=CamBridge", readMs = 15_000) } catch (e: java.net.SocketTimeoutException) { "err_timeout" }
            AppLog.i("LUMIX: accctrl → ${t.take(80)}")
            if (isOk(t)) break
            val e = errOf(t)
            if (e.contains("reject")) throw IOException("カメラ側で接続が拒否されました（カメラの画面で許可してください）")
            if (e.contains("non_support") || e.contains("unsuitable")) break   // 古い機種は接続許可が不要
            if (System.currentTimeMillis() > end) throw IOException("カメラの画面で接続を許可してください（応答: $e）")
            Thread.sleep(1500)
        }
        // 2) 撮影モードへ
        val rec = cgi("mode=camcmd&value=recmode")
        if (!isOk(rec)) AppLog.w("LUMIX: recmode → ${errOf(rec)}")
        // 3) 機種名（取得できなければ「LUMIX」）
        val model = try {
            val cap = cgi("mode=getinfo&type=capability")
            Regex("<model>(.*?)</model>").find(cap)?.groupValues?.get(1)
                ?: Regex("model[a-z_]*=\"([^\"]+)\"").find(cap)?.groupValues?.get(1)
        } catch (_: Exception) { null }
        keepAlive()
        caps = CamCaps(
            liveView = true, capture = true, touchAf = true, afReadyPoll = false, mfDrive = true,
            media = false, nativeBurst = false, exposure = true, focusModes = emptySet(), movie = true
        )
        return CameraIdentity(brand, "Panasonic", model ?: "LUMIX", "", "LUMIX Wi-Fi (HTTP/UDP)", host, caps).also { identity = it }
    }

    override fun keepAlive() {
        lastKeepMs = System.currentTimeMillis()
        val st = cgi("mode=getstate", readMs = 5000)
        Regex("<batt>(\\d+)/(\\d+)</batt>").find(st)?.let { m ->
            val a = m.groupValues[1].toIntOrNull(); val b = m.groupValues[2].toIntOrNull()
            if (a != null && b != null && b > 0) battery = a * 100 / b
        }
    }

    // ------------------------------------------------------------------
    // ライブビュー（UDP）
    // ------------------------------------------------------------------
    override fun startLiveView(): PtpResult {
        stopUdp()
        val s = DatagramSocket(0)
        network.bindSocket(s)
        s.soTimeout = 1000
        s.receiveBufferSize = 512 * 1024
        udp = s
        lvPort = s.localPort
        lastPacketMs = System.currentTimeMillis()
        Thread({ udpLoop(s) }, "lumix-liveview").apply { isDaemon = true; start() }
        lastStreamReqMs = System.currentTimeMillis()
        return toRc(cgi("mode=startstream&value=$lvPort"), "startstream")
    }

    private fun udpLoop(s: DatagramSocket) {
        val buf = ByteArray(65536)
        while (!s.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                lastPacketMs = System.currentTimeMillis()
                val n = p.length
                var soi = -1
                for (i in 0 until minOf(n - 1, 1024)) if (buf[i] == 0xFF.toByte() && buf[i + 1] == 0xD8.toByte()) { soi = i; break }
                if (soi < 0) continue
                latest.set(buf.copyOfRange(soi, n))
                synchronized(lock) { seq.incrementAndGet(); lock.notifyAll() }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) { break }
        }
    }

    private fun stopUdp() { try { udp?.close() } catch (_: Exception) {}; udp = null }

    override fun endLiveView(): PtpResult {
        val r = try { toRc(cgi("mode=stopstream"), "stopstream") } catch (e: Exception) { Rc.fail("(${e.message})") }
        stopUdp()
        return r
    }

    override fun liveViewFrame(): LvFrame {
        if (udp == null) return LvFrame(Rc.GENERAL, null)
        val now = System.currentTimeMillis()
        // 接続維持（getstate）と、ストリームが途切れたときの再要求
        if (now - lastKeepMs > 2500) try { keepAlive() } catch (_: Exception) {}
        if (now - lastPacketMs > 2500 && now - lastStreamReqMs > 2000) {
            lastStreamReqMs = now
            try { cgi("mode=startstream&value=$lvPort") } catch (_: Exception) {}
        }
        synchronized(lock) {
            if (seq.get() == taken) lock.wait(80)
            if (seq.get() == taken) return LvFrame(Rc.BUSY, null)
            taken = seq.get()
        }
        return LvFrame(Rc.OK, latest.get())
    }

    // ------------------------------------------------------------------
    // 撮影・AF・MF
    // ------------------------------------------------------------------
    override fun capture(): PtpResult {
        val r = toRc(cgi("mode=camcmd&value=capture", readMs = 15_000), "capture")
        if (r.code == Rc.OK) {
            try { cgi("mode=camcmd&value=capture_cancel") } catch (_: Exception) {}
            onEvent?.invoke(PtpIpClient.EV_OBJECT_ADDED, emptyList())
        }
        return r
    }

    /** タッチAF（座標は0〜1000）。 */
    override fun afAt(nx: Float, ny: Float, lvW: Int, lvH: Int): PtpResult {
        val x = (nx.coerceIn(0f, 1f) * 1000).roundToInt()
        val y = (ny.coerceIn(0f, 1f) * 1000).roundToInt()
        val t = cgi("mode=camctrl&type=touch&value=$x/$y&value2=on", readMs = 10_000)
        if (isOk(t)) return Rc.ok()
        val one = cgi("mode=camcmd&value=oneshot_af", readMs = 10_000)
        return if (isOk(one)) Rc.ok("（位置指定は拒否: ${errOf(t)}・カメラのAFエリアで合焦）") else toRc(t, "touch")
    }

    /** MF駆動: wide=近く / tele=遠く（と仮定）。方向はアプリの「方向確認」で入れ替え可能。量は概算。 */
    override fun mfDrive(far: Boolean, steps: Int, nearFlag: Int): PtpResult {
        val towardFar = if (nearFlag == 1) far else !far
        val fast = steps >= 40
        val repeat = (steps / (if (fast) 40 else 8)).coerceIn(1, 8)
        val v = (if (towardFar) "tele" else "wide") + if (fast) "-fast" else "-normal"
        var last: PtpResult = Rc.ok()
        repeat(repeat) {
            last = toRc(cgi("mode=camctrl&type=focus&value=$v"), "focus")
            if (last.code != Rc.OK) return last
        }
        return last
    }

    // ------------------------------------------------------------------
    // 露出（選択肢はカメラから取得できないため標準の一覧を使う）
    // ------------------------------------------------------------------
    private fun setting(type: String): String? = try {
        val t = cgi("mode=getsetting&type=$type")
        if (!isOk(t)) null else Regex("<settingvalue[^>]*?\\s[a-z_]+=\"([^\"]*)\"").find(t)?.groupValues?.get(1)
    } catch (_: Exception) { null }

    /** "a/256" はAPEX値、それ以外の "a/b" は分数として読む。 */
    private fun frac(s: String): Pair<Double, Boolean>? {
        val p = s.split('/')
        if (p.size == 2) {
            val a = p[0].trim().toDoubleOrNull() ?: return null
            val b = p[1].trim().toDoubleOrNull() ?: return null
            if (b == 0.0) return null
            return a / b to (b == 256.0)
        }
        return s.trim().toDoubleOrNull()?.let { it to false }
    }

    private val stdIso = listOf(0L, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600)
    private val stdF = listOf(14L, 16, 18, 20, 22, 25, 28, 32, 35, 40, 45, 50, 56, 63, 71, 80, 90, 100, 110, 130, 140, 160, 180, 200, 220).map { it * 10 }
    private val stdBias = (-15..15).map { (it * 1000.0 / 3.0).roundToLong() }

    override fun readExposure(): ExposureInfo {
        val iso = setting("iso")?.let { if (it.equals("auto", true)) 0L else it.filter(Char::isDigit).toLongOrNull() }
        val ap = setting("focal")?.let { frac(it) }?.let { (v, apex) -> ExpFormat.snapApertureX100(if (apex) 2.0.pow(v / 2.0) else v) }
        val ssSec = setting("shtrspeed")?.let { frac(it) }?.let { (v, apex) -> if (apex) 2.0.pow(-v) else v }
        val ev = setting("exposure")?.let { frac(it) }?.let { (v, _) -> (v * 1000).roundToLong() }
        return ExposureInfo(
            shutterOptions = ExpFormat.stdShutterSeconds.map { ShutterOption(ExpFormat.micros(it), ExpFormat.shutterLabel(it), it) },
            shutterRaw = ssSec?.let { s -> ExpFormat.stdShutterSeconds.minByOrNull { kotlin.math.abs(ln(it / s)) }?.let(ExpFormat::micros) },
            shutterLabel = ssSec?.let(ExpFormat::shutterLabel),
            iso = iso,
            isoOptions = stdIso,
            apertureX100 = ap,
            apertureOptions = stdF,
            biasMilli = ev,
            biasOptions = stdBias,
            battery = battery,
            synthesizedOptions = true
        )
    }

    override fun setExposure(kind: ExpKind, value: Long): String {
        val (type, v, label) = when (kind) {
            ExpKind.ISO -> Triple("iso", if (value == 0L) "auto" else value.toString(), ExpFormat.isoLabel(value))
            ExpKind.APERTURE -> {
                val f = value / 100.0
                Triple("focal", "${(2 * ln(f) / ln(2.0) * 256).roundToInt()}/256", ExpFormat.apertureLabel(value))
            }
            ExpKind.SHUTTER -> {
                val sec = value / 1_000_000.0
                Triple("shtrspeed", "${(-ln(sec) / ln(2.0) * 256).roundToInt()}/256", ExpFormat.shutterLabel(sec))
            }
            ExpKind.BIAS -> Triple("exposure", "${(value * 3 / 1000.0).roundToInt()}/3", ExpFormat.biasLabel(value) + " EV")
        }
        val t = cgi("mode=setsetting&type=$type&value=$v")
        if (!isOk(t)) return "${kind.label} 書き込み失敗: LUMIX ${errOf(t)}（値 $v・書式は機種により異なる場合があります）"
        return "OK: ${kind.label} $label（LUMIXの応答ok。反映は画面の値で確認してください）"
    }

    override fun startMovie(): PtpResult = toRc(cgi("mode=camcmd&value=video_recstart", readMs = 10_000), "video_recstart")
    override fun stopMovie(): PtpResult = toRc(cgi("mode=camcmd&value=video_recstop", readMs = 15_000), "video_recstop")

    override fun abort() { stopUdp(); CamHttp.abort() }
    override fun closeGracefully() { try { endLiveView() } catch (_: Exception) {}; close() }
    override fun close() { stopUdp() }
}
