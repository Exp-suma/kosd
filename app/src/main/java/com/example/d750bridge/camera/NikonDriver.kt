package com.example.d750bridge.camera

import android.net.Network
import com.example.d750bridge.AppLog
import com.example.d750bridge.ptp.PtpDeviceInfo
import com.example.d750bridge.ptp.PtpIpClient
import com.example.d750bridge.ptp.PtpNames
import com.example.d750bridge.ptp.PtpResult
import com.example.d750bridge.ptp.ShutterSpeed
import kotlin.math.abs

/** JPEGの切り出し（各社のライブビュー応答は先頭に独自ヘッダが付く）。 */
object Jpeg {
    fun extract(data: ByteArray, searchLimit: Int = 4096): ByteArray? {
        if (data.size < 4) return null
        val limit = minOf(data.size - 1, searchLimit)
        var start = -1
        for (i in 0 until limit) if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) { start = i; break }
        if (start < 0) return null
        for (i in data.size - 2 downTo start + 2) if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) {
            return if (start == 0 && i + 2 == data.size) data else data.copyOfRange(start, i + 2)
        }
        return null
    }
}

/**
 * Nikon（D750ほか Wi-Fi内蔵機）。PTP/IP + Nikon拡張オペレーション。
 * v0.9 までの ViewModel にあった処理をそのまま移した（挙動は同じ）。
 */
private const val OP_START_MOVIE = 0x920A   // Nikon StartMovieRecInCard
private const val OP_END_MOVIE = 0x920B     // Nikon EndMovieRec

class NikonDriver(network: Network, override val host: String, connectTimeoutMs: Int = 10_000) : CameraDriver {
    override val brand = CameraBrand.NIKON
    private val c = PtpIpClient(network, host, connectTimeoutMs = connectTimeoutMs, readTimeoutMs = 15_000)
    override val ptp: PtpIpClient get() = c
    override var deviceInfo: PtpDeviceInfo? = null
        private set
    override var caps = CamCaps()
        private set
    override var identity: CameraIdentity? = null
        private set
    override var onEvent: ((Int, List<Int>) -> Unit)?
        get() = c.onEvent
        set(v) { c.onEvent = v }

    override fun open(): CameraIdentity {
        try {
            val init = c.connect()
            c.openSession(init.connectionNumber)
            val info = c.getDeviceInfo()
            deviceInfo = info
            // ライブビュー/タッチAFは従来どおり「申告に関係なく試して応答コードを表示」する
            caps = CamCaps(
                liveView = true,
                capture = info.declares(PtpIpClient.OP_INITIATE_CAPTURE),
                touchAf = true,
                afReadyPoll = info.declares(PtpIpClient.NIKON_DEVICE_READY),
                mfDrive = info.declares(PtpIpClient.NIKON_MF_DRIVE),
                media = true,
                nativeBurst = info.declaresProp(0x5013) && info.declaresProp(0x5018),
                exposure = true,
                focusModes = if (info.declaresProp(0x500A)) FocusKind.values().toSet() else emptySet(),
                focalLength = info.declaresProp(0x5008),
                propSurvey = true,
                movie = info.declares(OP_START_MOVIE)
            )
            return CameraIdentity(brand, info.manufacturer, info.model, info.deviceVersion, "PTP/IP (Nikon)", host, caps).also { identity = it }
        } catch (e: Exception) {
            c.close(); throw e
        }
    }

    override fun startLiveView(): PtpResult = c.startLiveView()
    override fun endLiveView(): PtpResult = c.endLiveView()
    override fun liveViewFrame(): LvFrame {
        val r = c.getLiveViewImageRaw()
        if (r.code != Rc.OK) return LvFrame(r.code, null)
        return LvFrame(Rc.OK, Jpeg.extract(r.data))
    }

    override fun capture(): PtpResult = c.initiateCapture()

    /** 座標はライブビューJPEGのピクセル座標として送る（D750での座標系は実機で要確認・ログに送信値を出す）。 */
    override fun afAt(nx: Float, ny: Float, lvW: Int, lvH: Int): PtpResult {
        val x = (nx.coerceIn(0f, 1f) * (lvW - 1)).toInt()
        val y = (ny.coerceIn(0f, 1f) * (lvH - 1)).toInt()
        val a = c.changeAfArea(x, y)
        if (a.code != Rc.OK) return a.copy(note = "（AFエリア移動 $x,$y）")
        var r = c.autofocus()
        var tries = 0
        while (r.code == Rc.BUSY && tries < 10) { Thread.sleep(30); tries++; r = c.autofocus() }
        return r
    }
    override fun cancelAf(): PtpResult = c.cancelAutofocus()
    override fun afReady(): PtpResult = if (caps.afReadyPoll) c.deviceReady() else Rc.ok()

    override fun mfDrive(far: Boolean, steps: Int, nearFlag: Int): PtpResult {
        val farFlag = if (nearFlag == 1) 2 else 1
        return c.mfDrive(if (far) farFlag else nearFlag, steps)
    }

    override fun focalLengthMm(): Double? = try {
        Le.value(c.getPropertyDescriptor(0x5008).current) / 100.0   // FocalLength は 0.01mm 単位
    } catch (e: Exception) { AppLog.w("焦点距離の取得失敗: ${e.message}"); null }

    // ------------------------------------------------------------------
    // 露出
    // ------------------------------------------------------------------
    private fun desc(code: Int) = try { c.getPropertyDescriptor(code) } catch (e: Exception) {
        AppLog.w("DevicePropDesc ${PtpNames.hex(code)} 取得失敗: ${e.message}"); null
    }

    /** 露出補正の選択肢。列挙形式ならそのまま、範囲形式なら 最小〜最大 を刻みで展開（上限200件）。 */
    private fun biasOptionList(d: PtpIpClient.PropertyDescriptor): List<Long> {
        if (d.allowedValues.isNotEmpty()) return d.allowedValues.map { Le.signed(it) }.distinct().sorted()
        val r = d.range ?: return emptyList()
        val mn = Le.signed(r.first); val mx = Le.signed(r.second); val st = Le.value(r.third).coerceAtLeast(1L)
        if (mx <= mn || (mx - mn) / st > 200) return emptyList()
        return generateSequence(mn) { it + st }.takeWhile { it <= mx }.toList()
    }

    private fun focusKindOf(v: Long): FocusKind? = when (v) {
        0x0001L -> FocusKind.MF
        0x0002L, 0x8010L -> FocusKind.AFS
        0x8011L -> FocusKind.AFC
        0x8012L -> FocusKind.AFA
        else -> null
    }
    private fun focusCode(k: FocusKind): Long = when (k) {
        FocusKind.MF -> 0x0001L; FocusKind.AFS -> 0x8010L; FocusKind.AFC -> 0x8011L; FocusKind.AFA -> 0x8012L
    }

    override fun readExposure(): ExposureInfo {
        val sh = desc(0x500D)
        val mode = desc(0x500E)
        val iso = desc(0x500F)
        val ap = desc(0x5007)
        val bias = desc(0x5010)
        val fm = if (caps.focusModes.isNotEmpty()) desc(0x500A) else null
        val bat = try { c.getDevicePropValue(0x5001).let { if (it.code == Rc.OK && it.data.isNotEmpty()) (it.data[0].toInt() and 0xFF) else null } } catch (_: Exception) { null }
        val shRaw = sh?.current?.let { Le.value(it) }
        val modeCode = mode?.current?.let { Le.value(it).toInt() }
        return ExposureInfo(
            shutterOptions = sh?.allowedValues?.map { Le.value(it) }?.distinct()
                ?.filter { it in 1..0xFFFFFFFEL }?.sorted()
                ?.map { ShutterOption(it, ShutterSpeed.format(it), ShutterSpeed.rawToSeconds(it)) } ?: emptyList(),
            shutterRaw = shRaw,
            shutterLabel = shRaw?.let { ShutterSpeed.format(it) },
            modeCode = modeCode,
            modeLabel = modeCode?.let { PtpNames.exposureModeName(it) },
            iso = iso?.current?.let { Le.value(it) },
            isoOptions = iso?.allowedValues?.map { Le.value(it) }?.sorted() ?: emptyList(),
            apertureX100 = ap?.current?.let { Le.value(it) },
            apertureOptions = ap?.allowedValues?.map { Le.value(it) }?.filter { it > 0 }?.sorted() ?: emptyList(),
            biasMilli = bias?.current?.let { Le.signed(it) },
            biasOptions = bias?.let { biasOptionList(it) } ?: emptyList(),
            focus = fm?.current?.let { focusKindOf(Le.value(it)) },
            battery = bat
        )
    }

    override fun setExposure(kind: ExpKind, value: Long): String = when (kind) {
        ExpKind.SHUTTER -> setShutter(value)
        ExpKind.ISO -> setNumeric(0x500F, 2, value, "ISO", false) { "ISO $it" }
        ExpKind.APERTURE -> setNumeric(0x5007, 2, value, "絞り", false) { ExpFormat.apertureLabel(it) }
        ExpKind.BIAS -> setBias(value)
    }

    /** 許容値(raw)の最寄りに丸めて書き込み → 再読み取りで照合。 */
    private fun setShutter(rawReq: Long): String {
        val desc = c.getPropertyDescriptor(0x500D)
        if (!desc.writable) return "シャッター速度は現在読み取り専用です（カメラの露出モードがM/Sか確認）"
        val allowed = desc.allowedValues.map { Le.value(it) }
        val target = if (allowed.isEmpty() || rawReq in allowed) rawReq
        else ShutterSpeed.snap(ShutterSpeed.rawToSeconds(rawReq), allowed) ?: return "対応する選択肢がありません"
        val mode = try { Le.value(c.getPropertyDescriptor(0x500E).current).toInt() } catch (_: Exception) { -1 }
        val r = c.setDevicePropValue(0x500D, Le.bytes(target, 4))
        if (r.code != Rc.OK) return "書き込み失敗: ${PtpNames.describe(r)}"
        // カメラ側の反映は非同期のことがあるため、最大2回再読み取りして照合
        var actual = -1L
        for (wait in longArrayOf(120, 400)) {
            Thread.sleep(wait)
            actual = Le.value(c.getPropertyDescriptor(0x500D).current)
            if (actual == target) break
        }
        val modeNote = if (mode != -1 && mode != 1 && mode != 4) "（現在 ${PtpNames.exposureModeName(mode)} のため、M/Sでないと反映されない場合があります）" else ""
        val snapNote = if (target != rawReq) "［最寄りの ${ShutterSpeed.format(target)} に丸め］" else ""
        return if (actual == target) "OK: シャッター ${ShutterSpeed.label(actual)} $snapNote$modeNote"
        else "不一致: 要求 ${ShutterSpeed.label(target)} / カメラ現在値 ${if (actual >= 0) ShutterSpeed.label(actual) else "不明"} $modeNote"
    }

    private fun setNumeric(code: Int, size: Int, target: Long, name: String, strict: Boolean, fmt: (Long) -> String): String {
        val desc = c.getPropertyDescriptor(code)
        if (!desc.writable) return "$name は現在読み取り専用です"
        val allowed = desc.allowedValues.map { Le.value(it) }
        val chosen = when {
            allowed.isEmpty() || target in allowed -> target
            strict -> return "$name ${fmt(target)} はカメラが返した選択可能値に含まれていません"
            else -> allowed.minByOrNull { abs(it - target) } ?: target
        }
        val r = c.setDevicePropValue(code, Le.bytes(chosen, size))
        if (r.code != Rc.OK) return "$name 書き込み失敗: ${PtpNames.describe(r)}"
        var actual = -1L
        for (wait in longArrayOf(100, 300)) {
            Thread.sleep(wait)
            actual = Le.value(c.getPropertyDescriptor(code).current)
            if (actual == chosen) break
        }
        val note = if (chosen != target) "［入力 ${fmt(target)} → 最寄りの ${fmt(chosen)} に丸め］" else ""
        return if (actual == chosen) "OK: $name ${fmt(actual)} $note" else "不一致: $name 要求 ${fmt(chosen)} / カメラ現在値 ${if (actual >= 0) fmt(actual) else "不明"}"
    }

    /** 露出補正(0x5010, INT16, 0.001EV単位)。 */
    private fun setBias(target: Long): String {
        val desc = c.getPropertyDescriptor(0x5010)
        if (!desc.writable) return "露出補正は現在読み取り専用です（撮影モード/カメラ状態を確認）"
        val allowed = biasOptionList(desc)
        if (allowed.isNotEmpty() && target !in allowed) return "露出補正 %+.1f EV はカメラの選択肢にありません".format(target / 1000.0)
        val r = c.setDevicePropValue(0x5010, Le.bytes(target, 2))
        if (r.code != Rc.OK) return "露出補正 書き込み失敗: ${PtpNames.describe(r)}"
        var actual = Long.MIN_VALUE
        for (wait in longArrayOf(100, 300)) {
            Thread.sleep(wait)
            actual = Le.signed(c.getPropertyDescriptor(0x5010).current)
            if (actual == target) break
        }
        return if (actual == target) "OK: 露出補正 %+.1f EV".format(actual / 1000.0)
        else "不一致: 露出補正 要求 %+.1f EV / カメラ現在値 %s".format(target / 1000.0, if (actual == Long.MIN_VALUE) "不明" else "%+.1f EV".format(actual / 1000.0))
    }

    override fun focusMode(): FocusKind? = try { focusKindOf(Le.value(c.getPropertyDescriptor(0x500A).current)) } catch (_: Exception) { null }

    /** フォーカスモード(0x500A)の切替。選択可能値・書き込み可否・反映をカメラの応答で確認する。 */
    override fun setFocusMode(kind: FocusKind): SetResult {
        val want = focusCode(kind)
        val desc = c.getPropertyDescriptor(0x500A)
        val cur = Le.value(desc.current)
        val allowed = desc.allowedValues.map { Le.value(it) }
        if (cur == want) return SetResult(true, "カメラはすでに${kind.label}です")
        if (!desc.writable) return SetResult(false, "${kind.label}に切替できません: フォーカスモードが読み取り専用です（カメラのAF/Mレバーやレンズ側のスイッチを確認）")
        if (allowed.isNotEmpty() && want !in allowed) return SetResult(false, "${kind.label}はカメラの選択可能値にありません")
        val r = c.setDevicePropValue(0x500A, Le.bytes(want, 2))
        if (r.code != Rc.OK) return SetResult(false, "${kind.label}への切替に失敗: ${PtpNames.describe(r)}")
        Thread.sleep(150)
        val after = Le.value(c.getPropertyDescriptor(0x500A).current)
        return if (after == want) SetResult(true, "${kind.label}に切替えました")
        else SetResult(false, "${kind.label}への切替の反映を確認できません（カメラ現在値 ${PtpNames.hex(after.toInt())}）")
    }

    // ------------------------------------------------------------------
    // カメラ本体の連続撮影（StillCaptureMode 0x5013 / BurstNumber 0x5018）
    // ------------------------------------------------------------------
    private fun setPropVerified(code: Int, size: Int, value: Long): String? {
        try {
            val d = c.getPropertyDescriptor(code)
            if (!d.writable) return "${PtpNames.hex(code)} は読み取り専用"
            val allowed = d.allowedValues.map { Le.value(it) }
            val rng = d.range
            if (allowed.isNotEmpty() && value !in allowed) return "値 $value は ${PtpNames.hex(code)} の選択可能値にありません"
            if (rng != null && (value < Le.value(rng.first) || value > Le.value(rng.second))) return "値 $value は ${PtpNames.hex(code)} の範囲外"
            val r = c.setDevicePropValue(code, Le.bytes(value, size))
            if (r.code != Rc.OK) return "${PtpNames.hex(code)} 書き込み失敗 ${PtpNames.describe(r)}"
            Thread.sleep(100)
            if (Le.value(c.getPropertyDescriptor(code).current) != value) return "${PtpNames.hex(code)} の反映を確認できません"
            return null
        } catch (e: Exception) { return "${PtpNames.hex(code)} 設定中に例外: ${e.message}" }
    }

    override fun prepareNativeBurst(n: Int): Triple<Boolean, String, Long?> {
        if (!caps.nativeBurst) return Triple(false, "高速: カメラがStillCaptureMode(0x5013)/BurstNumber(0x5018)を申告していないため、通常方式で連写します", null)
        val saved = try { Le.value(c.getPropertyDescriptor(0x5013).current) } catch (_: Exception) { null }
        setPropVerified(0x5013, 2, 2L)?.let { return Triple(false, "高速: $it。通常方式で連写します", null) }
        val e = setPropVerified(0x5018, 2, n.toLong())
        if (e != null) {
            saved?.let { setPropVerified(0x5013, 2, it) }
            return Triple(false, "高速: $e。通常方式で連写します", null)
        }
        return Triple(true, "高速: カメラ連続撮影モードに設定（1回の撮影指示で最大${n}枚を要求。実枚数はカメラの通知で数えます・未検証）", saved)
    }

    override fun restoreNativeBurst(saved: Long) { setPropVerified(0x5013, 2, saved) }

    /** ライブビュー中（D750はライブビューセレクターが動画側）である必要がある。拒否された場合は応答コードを返す。 */
    override fun startMovie(): PtpResult {
        val info = deviceInfo
        // 診断: ライブビュー関連（0xD1A0〜0xD1AF）と録画先などの値をログに残す（原因の切り分け用）
        if (info != null) {
            val probe = info.properties.filter { it in 0xD1A0..0xD1AF || it == 0xD10B || it == 0xD1F0 }
            val vals = probe.joinToString { p ->
                val r = try { c.getDevicePropValue(p) } catch (_: Exception) { null }
                "${PtpNames.hex(p)}=" + if (r?.code == Rc.OK) PtpNames.rawHex(r.data, 4) else "?"
            }
            AppLog.i("Nikon 録画前の状態: $vals")
            // ApplicationMode(0xD1F0) を持つ機種は 1 にしてから録画（libgphoto2 と同じ手順）
            if (0xD1F0 in info.properties) {
                val cur = try { c.getDevicePropValue(0xD1F0).data.firstOrNull()?.toInt() } catch (_: Exception) { null }
                if (cur != 1) AppLog.i("Nikon ApplicationMode=1: ${PtpNames.describe(c.setDevicePropValue(0xD1F0, byteArrayOf(1)))}")
            }
        }
        // ライブビュー開始直後はカメラが準備中のことがあるため、撮影可能（DeviceReady）になるまで待つ
        if (caps.afReadyPoll) {
            val end = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < end && Rc.isBusy(c.deviceReady().code)) Thread.sleep(100)
        }
        // 「録画できない状態」(0xA004)・Busy は、少し待ってやり直す
        var r = c.transact(OP_START_MOVIE)
        var tries = 0
        while ((Rc.isBusy(r.code) || r.code == 0xA004) && tries < 8) {
            Thread.sleep(if (r.code == 0xA004) 500L else 150L); tries++
            r = c.transact(OP_START_MOVIE)
        }
        if (tries > 0) AppLog.i("Nikon StartMovieRecInCard: 再試行 $tries 回 → ${PtpNames.describe(r)}")
        return r
    }
    override fun stopMovie(): PtpResult = c.transact(OP_END_MOVIE)

    override fun abort() = c.abort()
    override fun closeGracefully() { c.closeSession(); c.close() }
    override fun close() = c.close()
}
