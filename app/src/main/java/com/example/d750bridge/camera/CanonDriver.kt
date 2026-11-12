package com.example.d750bridge.camera

import android.net.Network
import com.example.d750bridge.AppLog
import com.example.d750bridge.ptp.PtpDeviceInfo
import com.example.d750bridge.ptp.PtpIpClient
import com.example.d750bridge.ptp.PtpNames
import com.example.d750bridge.ptp.PtpResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Canon EOS（EOS Utility接続）。PTP/IP + Canon EOS拡張。
 * オペレーション・プロパティ番号は libgphoto2 の公開定義に基づく。実機での動作は未検証。
 *
 * EOS拡張の特徴:
 *  - プロパティは GetDevicePropDesc ではなく GetEvent(0x9116) のポーリングで「現在値」と「選択肢」を受け取る
 *  - 書き込みは SetDevicePropValueEx(0x9110)
 *  - ライブビューは EVF出力先(0xD1B0)=PC にして GetViewFinderData(0x9153) を繰り返す
 *  - 撮影は RemoteReleaseOn/Off(0x9128/0x9129)（半押し→全押し→離す）
 */
class CanonDriver(private val network: Network, override val host: String, private val guid: ByteArray, connectTimeoutMs: Int = 10_000) : CameraDriver {
    companion object {
        const val OP_REMOTE_RELEASE = 0x910F
        const val OP_SET_PROP_EX = 0x9110
        const val OP_SET_REMOTE_MODE = 0x9114
        const val OP_SET_EVENT_MODE = 0x9115
        const val OP_GET_EVENT = 0x9116
        const val OP_KEEP_DEVICE_ON = 0x911D
        const val OP_RELEASE_ON = 0x9128
        const val OP_RELEASE_OFF = 0x9129
        const val OP_INITIATE_VIEWFINDER = 0x9151
        const val OP_TERMINATE_VIEWFINDER = 0x9152
        const val OP_GET_VIEWFINDER = 0x9153
        const val OP_DO_AF = 0x9154
        const val OP_DRIVE_LENS = 0x9155
        const val OP_ZOOM_POSITION = 0x9159
        const val OP_AF_CANCEL = 0x9160

        const val P_APERTURE = 0xD101
        const val P_SHUTTER = 0xD102
        const val P_ISO = 0xD103
        const val P_BIAS = 0xD104
        const val P_AE_MODE = 0xD105
        const val P_FOCUS_MODE = 0xD108
        const val P_BATTERY = 0xD111
        const val P_CAPTURE_DEST = 0xD11C
        const val P_EVF_OUTPUT = 0xD1B0
        const val P_EVF_MODE = 0xD1B1
        const val P_EVF_RECORD = 0xD1B8   // EVFRecordStatus: 4=録画開始 / 0=停止（libgphoto2）

        const val EV_REQUEST_OBJECT_TRANSFER = 0xC186
        const val EV_REQUEST_OBJECT_TRANSFER64 = 0xC1A9
        const val OP_GET_OBJECT = 0x9104
        const val OP_TRANSFER_COMPLETE = 0x9117
        const val EV_OBJECT_ADDED_EX = 0xC181
        const val EV_OBJECT_ADDED_EX64 = 0xC1A7
        const val EV_PROP_CHANGED = 0xC189
        const val EV_AVAIL_CHANGED = 0xC18A

        private const val SHUTTER_BULB_CODE = 0x0CL
        private const val CAPTURE_DEST_PC = 4L
    }

    override val brand = CameraBrand.CANON
    private val c = PtpIpClient(network, host, connectTimeoutMs = connectTimeoutMs, readTimeoutMs = 15_000)
    override val ptp: PtpIpClient get() = c
    override var deviceInfo: PtpDeviceInfo? = null
        private set
    override var caps = CamCaps()
        private set
    override var identity: CameraIdentity? = null
        private set
    override var onEvent: ((Int, List<Int>) -> Unit)? = null
        set(v) { field = v; c.onEvent = v }

    private val values = HashMap<Int, Long>()
    private val avail = HashMap<Int, List<Long>>()
    private var lastPollNs = 0L

    override fun open(): CameraIdentity {
        try {
            // EOS Utility と同じく、接続の直前にカメラを検出する（これが無いと接続要求を無視する機種がある）
            CanonDiscovery.probe(network, host)
            val init = c.connect(guid, "CamBridge", initAckTimeoutMs = 90_000)   // カメラ画面での許可を待つ
            // EOS Utility の順序: DeviceInfo → OpenSession(0x41) → リモートモード
            val info = c.getDeviceInfo()
            deviceInfo = info
            c.resetTransactionId()
            c.openSession(0x41)
            var rm = c.transact(OP_SET_REMOTE_MODE, 5)
            if (rm.code != Rc.OK) rm = c.transact(OP_SET_REMOTE_MODE, 1)
            val em = c.transact(OP_SET_EVENT_MODE, 1)
            AppLog.i("Canon: SetRemoteMode ${PtpNames.describe(rm)} / SetEventMode ${PtpNames.describe(em)}")
            pollEvents(8)   // 初回は全プロパティの現在値・選択肢がまとめて届く
            // 以前の版がミラーレスの出力先を「なし(0)」にしたまま終了していた場合の復旧（DeviceBusyが続く原因）
            if (mirrorless && values[P_EVF_OUTPUT] == 0L) {
                val fix = setProp(P_EVF_OUTPUT, 1)
                AppLog.i("Canon: 映像出力先が0のままだったため、カメラの画面(1)に戻しました ${PtpNames.describe(fix)}")
                pollEvents(4)
            }
            ensureCardDestination()
            caps = CamCaps(
                liveView = info.declares(OP_GET_VIEWFINDER),
                capture = info.declares(OP_RELEASE_ON) || info.declares(OP_REMOTE_RELEASE),
                touchAf = info.declares(OP_DO_AF),
                afReadyPoll = false,
                mfDrive = info.declares(OP_DRIVE_LENS),
                media = info.declares(PtpIpClient.OP_GET_OBJECT_HANDLES) && info.declares(PtpIpClient.OP_GET_OBJECT_INFO),
                nativeBurst = false,
                exposure = info.declares(OP_SET_PROP_EX),
                focusModes = if (values.containsKey(P_FOCUS_MODE)) setOf(FocusKind.AFS, FocusKind.AFC, FocusKind.AFA) else emptySet(),
                focalLength = false,
                propSurvey = false,
                movie = info.declares(OP_SET_PROP_EX)
            )
            return CameraIdentity(brand, info.manufacturer, info.model, info.deviceVersion, "PTP/IP (Canon EOS)", host, caps).also { identity = it }
        } catch (e: com.example.d750bridge.ptp.PtpException) {
            c.close()
            if (e.message?.contains("Init_Fail") == true) throw com.example.d750bridge.ptp.PtpException(
                "カメラが接続を拒否しました（${e.message}）。" + when {
                    e.message?.contains("使用中") == true -> "カメラが他の機器（パソコン・スマホ）と接続中です。そちらを切断してから、もう一度接続してください"
                    else -> "EOS Utility（リモート操作）モードは、パソコンのEOS Utilityとペアリング済みの機器しか受け付けない場合があります。" +
                        "カメラのWi-Fi機能を「スマートフォンと接続」に切り替えて、カメラ画面に接続確認が出たら「OK」を選んでください（このモードは未検証です）"
                }
            )
            throw e
        } catch (e: Exception) {
            c.close(); throw e
        }
    }

    /** 撮影画像の保存先をカード側にする（PC側のままだと画像がカメラに残らない）。値はlibgphoto2と同様に「4(PC)以外の選択肢」を使う。 */
    private fun ensureCardDestination() {
        val cur = values[P_CAPTURE_DEST]
        AppLog.i("Canon: 保存先(0xD11C) 現在値=${cur ?: "未通知"} 選択肢=${avail[P_CAPTURE_DEST] ?: "未通知"}")
        if (cur == null || cur != CAPTURE_DEST_PC) return
        // 選択肢が届いていなくても、カードの候補値を順に試し、カメラの値を読み直して確認する
        val cands = (avail[P_CAPTURE_DEST]?.filter { it != CAPTURE_DEST_PC } ?: emptyList()) + listOf(1L, 2L)
        for (v in cands.distinct()) {
            val r = setProp(P_CAPTURE_DEST, v)
            pollEvents(2)
            AppLog.i("Canon: 保存先をカード候補($v)へ: ${PtpNames.describe(r)} → 現在値 ${values[P_CAPTURE_DEST]}")
            if (r.code == Rc.OK && values[P_CAPTURE_DEST] == v) return
        }
        AppLog.w("Canon: 保存先をカードに変更できませんでした。撮影した写真はアプリが受け取ってスマホに保存します")
    }

    /** 保存先がPCのとき、カメラが「取りに来て」と通知した画像。受け取ってからでないとカメラのバッファが埋まりBusyになる。 */
    private val pendingTransfers = ArrayDeque<Int>()
    private var draining = false
    /** 受け取った画像（ファイル名, データ）を保存する処理。ViewModel が設定する。 */
    var onObjectData: ((String, ByteArray) -> Unit)? = null

    private fun drainTransfers() {
        if (draining) return
        draining = true
        try {
            while (pendingTransfers.isNotEmpty()) {
                val h = pendingTransfers.removeFirst()
                try {
                    val r = retryBusy("GetObject", 5000) { c.transactQuiet(OP_GET_OBJECT, h) }
                    if (r.code == Rc.OK && r.data.isNotEmpty()) {
                        val d = r.data
                        val ext = when {
                            d.size > 2 && d[0] == 0xFF.toByte() && d[1] == 0xD8.toByte() -> "JPG"
                            d.size > 12 && String(d, 4, 8, Charsets.ISO_8859_1).startsWith("ftypcrx") -> "CR3"
                            d.size > 10 && d[0] == 'I'.code.toByte() && d[1] == 'I'.code.toByte() && d[8] == 'C'.code.toByte() && d[9] == 'R'.code.toByte() -> "CR2"
                            d.size > 12 && String(d, 4, 8, Charsets.ISO_8859_1).startsWith("ftyphei") -> "HIF"
                            else -> "BIN"
                        }
                        val name = "IMG_%08X.%s".format(h, ext)
                        AppLog.i("Canon: 転送要求の画像を受信 $name (${d.size} バイト)")
                        try { onObjectData?.invoke(name, d) } catch (e: Exception) { AppLog.e("Canon: 受信画像の保存に失敗", e) }
                    } else AppLog.w("Canon: 転送要求の画像を取得できません handle=${PtpNames.hex(h)} ${PtpNames.describe(r)}")
                } catch (e: Exception) { AppLog.e("Canon: 画像の受信に失敗", e) }
                // 受け取れなかった場合もカメラのバッファを空けないとBusyが続くため、完了を通知する
                val tc = c.transactQuiet(OP_TRANSFER_COMPLETE, h)
                AppLog.i("Canon: TransferComplete ${PtpNames.hex(h)} ${PtpNames.describe(tc)}")
            }
        } finally { draining = false }
    }

    // ------------------------------------------------------------------
    // GetEvent（プロパティ・撮影通知のポーリング）
    // ------------------------------------------------------------------
    private fun pollEvents(maxRounds: Int = 2) {
        lastPollNs = System.nanoTime()
        for (i in 0 until maxRounds) {
            val r = c.transactQuiet(OP_GET_EVENT)
            if (r.code != Rc.OK || parseEvents(r.data) == 0) break
        }
        if (pendingTransfers.isNotEmpty()) drainTransfers()
    }

    private fun parseEvents(data: ByteArray): Int {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var count = 0
        var propChanged = false
        while (b.remaining() >= 8) {
            val start = b.position()
            val size = b.int
            val type = b.int
            if (size < 8 || start + size > data.size) break
            if (type == 0) break
            val end = start + size
            when (type) {
                EV_PROP_CHANGED -> if (end - b.position() >= 8) {
                    val prop = b.int
                    val v = b.int.toLong() and 0xFFFFFFFFL
                    if (values.put(prop, v) != v) propChanged = true
                }
                EV_AVAIL_CHANGED -> if (end - b.position() >= 12) {
                    val prop = b.int
                    b.int // データ型
                    val n = b.int
                    // 型に関わらず1要素4バイト（libgphoto2の実装に倣う）
                    if (n in 0..512 && end - b.position() >= n * 4) avail[prop] = List(n) { b.int.toLong() and 0xFFFFFFFFL }
                }
                EV_REQUEST_OBJECT_TRANSFER, EV_REQUEST_OBJECT_TRANSFER64 -> if (end - b.position() >= 4) {
                    val handle = b.int
                    if (handle !in pendingTransfers) pendingTransfers.addLast(handle)
                    onEvent?.invoke(PtpIpClient.EV_OBJECT_ADDED, listOf(handle))
                }
                EV_OBJECT_ADDED_EX, EV_OBJECT_ADDED_EX64 -> if (end - b.position() >= 4) {
                    val handle = b.int
                    onEvent?.invoke(PtpIpClient.EV_OBJECT_ADDED, listOf(handle))
                }
            }
            b.position(end)
            count++
        }
        if (propChanged) onEvent?.invoke(0x4006, emptyList())
        return count
    }

    /**
     * SetDevicePropValueEx。Canonはライブビューの映像処理中などに一時的に DeviceBusy(0x2019) を返すため、
     * libgphoto2 と同様に、イベントを読み出しながら最大3秒やり直す。
     */
    private fun setProp(prop: Int, value: Long): PtpResult {
        val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(12).putInt(prop).putInt(value.toInt()).array()
        return retryBusy("SetPropEx ${PtpNames.hex(prop)}=$value") { c.transactWithDataQuiet(OP_SET_PROP_EX, data) }
    }

    private fun retryBusy(what: String, maxMs: Long = 3000, op: () -> PtpResult): PtpResult {
        val end = System.currentTimeMillis() + maxMs
        var r = op()
        var tries = 0
        while (Rc.isBusy(r.code) && System.currentTimeMillis() < end) {
            tries++
            Thread.sleep(if (tries < 5) 60L else 150L)
            // たまったイベントを読むとビジーが解けることがある（GetEvent 自体がビジーでも無視）
            try { val e = c.transactQuiet(OP_GET_EVENT); if (e.code == Rc.OK) parseEvents(e.data) } catch (_: Exception) {}
            r = op()
        }
        if (tries > 0) AppLog.i("Canon: $what ビジー再試行 $tries 回 → ${PtpNames.describe(r)}")
        return r
    }

    private var lastKeepOnMs = 0L

    override fun keepAlive() {
        pollEvents(2)
        // 自動電源オフ・スリープを防ぐ（libgphoto2 と同じく定期的に KeepDeviceOn）
        if (deviceInfo?.declares(OP_KEEP_DEVICE_ON) == true && System.currentTimeMillis() - lastKeepOnMs > 20_000) {
            lastKeepOnMs = System.currentTimeMillis()
            try { c.transactQuiet(OP_KEEP_DEVICE_ON) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------
    // ライブビュー
    // ------------------------------------------------------------------
    /** ライブビュー開始前の映像出力先（終了時に元へ戻す）。ビットの組み合わせ: 1=カメラの画面(TFT) / 2=PC。 */
    private var savedEvfOutput: Long? = null

    /** ミラーレス（EOS R / M）はEVFが常に動いているため、出力先を「なし(0)」にしてはいけない。 */
    private val mirrorless: Boolean get() = deviceInfo?.model?.let { Regex("(?i)EOS\\s*(R|M)\\d*\\b|EOS R").containsMatchIn(it) } == true

    override fun startLiveView(): PtpResult {
        pollEvents(2)
        savedEvfOutput = values[P_EVF_OUTPUT]
        // libgphoto2 と同じく、対応機種では InitiateViewfinder を先に送る
        if (deviceInfo?.declares(OP_INITIATE_VIEWFINDER) == true) {
            val iv = c.transact(OP_INITIATE_VIEWFINDER)
            if (iv.code != Rc.OK) AppLog.w("Canon: InitiateViewfinder ${PtpNames.describe(iv)}")
        }
        val m = setProp(P_EVF_MODE, 1)
        if (m.code != Rc.OK) AppLog.w("Canon: EVFMode=1 ${PtpNames.describe(m)}")
        // 2=PC。カメラ側の画面にも出していた場合（1）はそれも残す（1|2）
        val out = ((savedEvfOutput ?: 0L) and 1L) or 2L
        val r = setProp(P_EVF_OUTPUT, out)
        AppLog.i("Canon: ライブビュー開始 出力先 ${savedEvfOutput ?: "?"} → $out ${PtpNames.describe(r)}")
        pollEvents(1)
        return r
    }

    /** 出力先を元の値に戻す（不明ならミラーレスはカメラの画面(1)、一眼レフは0）。その後、たまったイベントを読み出す。 */
    override fun endLiveView(): PtpResult {
        val restore = savedEvfOutput ?: if (mirrorless) 1L else 0L
        var r = setProp(P_EVF_OUTPUT, restore)
        var tries = 0
        while (Rc.isBusy(r.code) && tries < 10) { Thread.sleep(150); tries++; pollEvents(1); r = setProp(P_EVF_OUTPUT, restore) }
        if (deviceInfo?.declares(OP_TERMINATE_VIEWFINDER) == true) {
            val tv = c.transact(OP_TERMINATE_VIEWFINDER)
            if (tv.code != Rc.OK) AppLog.w("Canon: TerminateViewfinder ${PtpNames.describe(tv)}")
        }
        AppLog.i("Canon: ライブビュー終了 出力先 → $restore ${PtpNames.describe(r)}")
        savedEvfOutput = null
        pollEvents(4)   // libgphoto2 と同じく、終了後にたまったイベントを読み出す
        return r
    }

    override fun liveViewFrame(): LvFrame {
        if (System.nanoTime() - lastPollNs > 500_000_000L) pollEvents(1)
        val r = c.transactQuiet(OP_GET_VIEWFINDER, 0x00100000)
        if (r.code != Rc.OK) return LvFrame(r.code, null)
        return LvFrame(Rc.OK, viewfinderJpeg(r.data) ?: Jpeg.extract(r.data))
    }

    /** GetViewFinderData の応答は [長さu32][種別u32][データ] の並び。種別1がJPEG。 */
    private fun viewfinderJpeg(d: ByteArray): ByteArray? {
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        while (b.remaining() >= 8) {
            val start = b.position()
            val len = b.int
            val type = b.int
            if (len < 8 || start + len > d.size) return null
            if (type == 1) return d.copyOfRange(start + 8, start + len)
            b.position(start + len)
        }
        return null
    }

    // ------------------------------------------------------------------
    // 撮影・AF
    // ------------------------------------------------------------------
    override fun capture(): PtpResult {
        val info = deviceInfo
        if (info != null && !info.declares(OP_RELEASE_ON)) return c.transactQuiet(OP_REMOTE_RELEASE)
        // 半押し(1) → 全押し(2) → 全押し解除 → 半押し解除
        val half = c.transactQuiet(OP_RELEASE_ON, 1, 0)
        if (half.code != Rc.OK) return half
        val full = c.transactQuiet(OP_RELEASE_ON, 2, 0)
        val offFull = c.transactQuiet(OP_RELEASE_OFF, 2)
        val offHalf = c.transactQuiet(OP_RELEASE_OFF, 1)
        if (full.code != Rc.OK) return full
        if (offFull.code != Rc.OK) AppLog.w("Canon: RemoteReleaseOff(2) ${PtpNames.describe(offFull)}")
        if (offHalf.code != Rc.OK) AppLog.w("Canon: RemoteReleaseOff(1) ${PtpNames.describe(offHalf)}")
        pollEvents(1)
        return full
    }

    /** ZoomPosition でAF枠（拡大枠）を移動してから DoAf。座標系はライブビュー画像のピクセル座標（未検証）。 */
    override fun afAt(nx: Float, ny: Float, lvW: Int, lvH: Int): PtpResult {
        val x = (nx.coerceIn(0f, 1f) * (lvW - 1)).toInt()
        val y = (ny.coerceIn(0f, 1f) * (lvH - 1)).toInt()
        val info = deviceInfo
        if (info == null || info.declares(OP_ZOOM_POSITION)) {
            val z = c.transactQuiet(OP_ZOOM_POSITION, x, y)
            if (z.code != Rc.OK) AppLog.w("Canon: ZoomPosition($x,$y) ${PtpNames.describe(z)}（AF枠は移動していない可能性）")
        }
        var r = c.transact(OP_DO_AF)
        var tries = 0
        while (Rc.isBusy(r.code) && tries < 10) { Thread.sleep(30); tries++; r = c.transact(OP_DO_AF) }
        return r
    }

    override fun cancelAf(): PtpResult = c.transact(OP_AF_CANCEL)

    /** DriveLens: Near=0x0001〜0x0003 / Far=0x8001〜0x8003（小・中・大）。ステップ数を大(100)・中(10)・小(1)の回数に分解する（量は概算）。 */
    override fun mfDrive(far: Boolean, steps: Int, nearFlag: Int): PtpResult {
        val towardFar = if (nearFlag == 1) far else !far
        var left = steps.coerceIn(1, 2000)
        var last: PtpResult = Rc.ok()
        var commands = 0
        for ((size, level) in listOf(100 to 3, 10 to 2, 1 to 1)) {
            while (left >= size && commands < 24) {
                val code = (if (towardFar) 0x8000 else 0) or level
                last = retryBusy("DriveLens", 1500) { c.transactQuiet(OP_DRIVE_LENS, code) }
                if (last.code != Rc.OK) return last
                left -= size; commands++
                Thread.sleep(40)
            }
        }
        return last
    }

    // ------------------------------------------------------------------
    // 露出（値はメーカー共通の単位へ変換して返す）
    // ------------------------------------------------------------------
    private fun isoOf(code: Long): Long? = when {
        code == 0L -> 0L
        code < 0x28 -> null
        else -> ExpFormat.snapIso(100.0 * 2.0.pow((code - 0x48) / 8.0))
    }
    private fun apOf(code: Long): Long? = if (code < 0x08 || code > 0x70) null else ExpFormat.snapApertureX100(2.0.pow((code - 8) / 16.0))
    private fun secOf(code: Long): Double? = if (code < 0x10 || code > 0xA0) null else 2.0.pow(-(code - 0x38) / 8.0)
    private fun biasOf(code: Long): Long {
        val s = code.toByte().toInt()               // 符号付き 1/8EV単位（1/3段は ±3,±5 で近似）
        val sign = if (s < 0) -1 else 1
        val a = kotlin.math.abs(s)
        val frac = when (a % 8) { 3 -> 333; 4 -> 500; 5 -> 667; else -> (a % 8) * 125 }
        return sign * ((a / 8) * 1000L + frac)
    }
    private fun shutterKey(code: Long): Long = if (code == SHUTTER_BULB_CODE) com.example.d750bridge.ptp.ShutterSpeed.BULB else secOf(code)?.let { ExpFormat.micros(it) } ?: -1L

    private fun aeLabel(v: Long): String = when (v) {
        0L -> "P"; 1L -> "Tv(シャッター優先)"; 2L -> "Av(絞り優先)"; 3L -> "M(マニュアル)"; 4L -> "バルブ"
        5L -> "A-DEP"; 9L -> "全自動"; 22L -> "シーンインテリジェントオート"; else -> "その他(0x%X)".format(v)
    }

    override fun readExposure(): ExposureInfo {
        pollEvents(3)
        val ss = values[P_SHUTTER]
        val ssOpts = (avail[P_SHUTTER] ?: emptyList()).mapNotNull { code ->
            if (code == SHUTTER_BULB_CODE) ShutterOption(com.example.d750bridge.ptp.ShutterSpeed.BULB, "BULB", 9999.0)
            else secOf(code)?.let { ShutterOption(ExpFormat.micros(it), ExpFormat.shutterLabel(it), it) }
        }.distinctBy { it.raw }.sortedBy { it.seconds }
        val ae = values[P_AE_MODE]
        return ExposureInfo(
            shutterOptions = ssOpts,
            shutterRaw = ss?.let { shutterKey(it) },
            shutterLabel = ss?.let { if (it == SHUTTER_BULB_CODE) "BULB" else secOf(it)?.let(ExpFormat::shutterLabel) },
            modeCode = ae?.toInt(),
            modeLabel = ae?.let(::aeLabel),
            iso = values[P_ISO]?.let(::isoOf),
            isoOptions = (avail[P_ISO] ?: emptyList()).mapNotNull(::isoOf).distinct().sorted(),
            apertureX100 = values[P_APERTURE]?.let(::apOf),
            apertureOptions = (avail[P_APERTURE] ?: emptyList()).mapNotNull(::apOf).distinct().sorted(),
            biasMilli = values[P_BIAS]?.let(::biasOf),
            biasOptions = (avail[P_BIAS] ?: emptyList()).map(::biasOf).distinct().sorted(),
            focus = focusMode(),
            battery = null
        )
    }

    /** 共通単位の値 → カメラのコード。選択肢に無ければ最寄りに丸める。 */
    private fun codeFor(kind: ExpKind, value: Long): Pair<Long, Boolean>? {
        val prop = propOf(kind)
        val list = avail[prop] ?: emptyList()
        val conv: (Long) -> Long? = when (kind) {
            ExpKind.ISO -> ::isoOf
            ExpKind.APERTURE -> ::apOf
            ExpKind.BIAS -> { c -> biasOf(c) }
            ExpKind.SHUTTER -> { c -> shutterKey(c).takeIf { it >= 0 } }
        }
        val pairs = list.mapNotNull { code -> conv(code)?.let { it to code } }
        if (pairs.isEmpty()) return null
        pairs.firstOrNull { it.first == value }?.let { return it.second to false }
        val near = if (kind == ExpKind.SHUTTER || kind == ExpKind.ISO || kind == ExpKind.APERTURE)
            pairs.filter { it.first > 0 }.minByOrNull { kotlin.math.abs(kotlin.math.ln(it.first.toDouble() / value.coerceAtLeast(1))) }
        else pairs.minByOrNull { kotlin.math.abs(it.first - value) }
        return near?.let { it.second to true }
    }

    private fun propOf(kind: ExpKind) = when (kind) { ExpKind.ISO -> P_ISO; ExpKind.APERTURE -> P_APERTURE; ExpKind.SHUTTER -> P_SHUTTER; ExpKind.BIAS -> P_BIAS }

    override fun setExposure(kind: ExpKind, value: Long): String {
        pollEvents(1)
        val (code, snapped) = codeFor(kind, value) ?: return "${kind.label}: カメラから選択肢が届いていません（露出モードによっては変更できません）"
        val prop = propOf(kind)
        val r = setProp(prop, code)
        if (r.code != Rc.OK) return "${kind.label} 書き込み失敗: ${PtpNames.describe(r)}"
        var actual: Long? = null
        for (wait in longArrayOf(120, 350)) {
            Thread.sleep(wait)
            pollEvents(2)
            actual = values[prop]
            if (actual == code) break
        }
        val label = when (kind) {
            ExpKind.ISO -> ExpFormat.isoLabel(isoOf(code))
            ExpKind.APERTURE -> ExpFormat.apertureLabel(apOf(code))
            ExpKind.SHUTTER -> if (code == SHUTTER_BULB_CODE) "BULB" else secOf(code)?.let(ExpFormat::shutterLabel) ?: "?"
            ExpKind.BIAS -> ExpFormat.biasLabel(biasOf(code)) + " EV"
        }
        val snapNote = if (snapped) "［最寄りの値に丸め］" else ""
        return if (actual == code) "OK: ${kind.label} $label $snapNote" else "不一致: ${kind.label} 要求 $label / カメラ値コード ${actual?.let { "0x%X".format(it) } ?: "不明"}"
    }

    override fun focusMode(): FocusKind? = when (values[P_FOCUS_MODE]) {
        0L -> FocusKind.AFS; 1L -> FocusKind.AFC; 2L -> FocusKind.AFA; 3L -> FocusKind.MF; else -> null
    }

    override fun setFocusMode(kind: FocusKind): SetResult {
        if (kind == FocusKind.MF) return SetResult(false, "Canonではレンズ側のAF/MFスイッチでMFに切り替えてください（通信では切替不可）。AFの追従は止めています")
        val v = when (kind) { FocusKind.AFS -> 0L; FocusKind.AFC -> 1L; else -> 2L }
        val r = setProp(P_FOCUS_MODE, v)
        if (r.code != Rc.OK) return SetResult(false, "${kind.label}への切替に失敗: ${PtpNames.describe(r)}")
        Thread.sleep(150); pollEvents(2)
        return if (values[P_FOCUS_MODE] == v) SetResult(true, "${kind.label}に切替えました") else SetResult(false, "${kind.label}への切替の反映を確認できません")
    }

    /** カメラが動画モード（ダイヤル/メニュー）である必要がある。 */
    override fun startMovie(): PtpResult { val r = setProp(P_EVF_RECORD, 4); pollEvents(1); return r }
    override fun stopMovie(): PtpResult { val r = setProp(P_EVF_RECORD, 0); pollEvents(1); return r }

    override fun abort() = c.abort()
    override fun closeGracefully() {
        try { if (savedEvfOutput != null) endLiveView() } catch (_: Exception) {}
        c.closeSession(); c.close()
    }
    override fun close() = c.close()
}
