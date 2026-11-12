package com.example.d750bridge.camera

import com.example.d750bridge.ptp.PtpDeviceInfo
import com.example.d750bridge.ptp.PtpIpClient
import com.example.d750bridge.ptp.PtpResult
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln

/**
 * 対応メーカー。接続方式はメーカーごとに異なる。
 *  - Nikon : PTP/IP (TCP 15740) + Nikon拡張
 *  - Canon : PTP/IP (TCP 15740) + Canon EOS拡張（EOS Utility接続）
 *  - Sony  : Camera Remote API（HTTP/JSON-RPC、SSDPで検出）
 *  - LUMIX : LUMIX Wi-Fi API（HTTP cam.cgi + UDPライブビュー）
 */
enum class CameraBrand(
    val label: String,
    val protocol: String,
    val defaultSsidPrefix: String,
    val ssidHint: String,
    val defaultHosts: List<String>,
    val port: Int,
    val rawExt: Set<String>,
    val setupSteps: List<String>,
    val note: String
) {
    NIKON(
        "Nikon", "PTP/IP", "Nikon_", "例: Nikon_",
        listOf("192.168.1.1"), 15740, setOf("NEF", "NRW"),
        listOf(
            "電池を十分に充電し、HDMI/USBケーブルを外す",
            "セットアップメニュー →「Wi-Fi」→「ネットワーク接続」を「有効」にする",
            "下の「接続する」を押し、SSID（Nikon_…）を選ぶ"
        ),
        "PTP/IP（TCP 15740）＋Nikon拡張。D750などWi-Fi内蔵機のアクセスポイントモード向け。"
    ),
    CANON(
        "Canon", "PTP/IP (EOS)", "EOS", "例: EOS",
        listOf("192.168.1.2", "192.168.1.1"), 15740, setOf("CR2", "CR3", "CRW"),
        listOf(
            "メニュー →「ワイヤレス通信の設定」→「Wi-Fi機能」→「スマートフォンと接続」（または「EOS Utility」）",
            "「かんたん接続」でSSIDとパスワードを表示（EOS Utilityモードは、パソコンとペアリング済みでないと拒否されることがあります）",
            "SSIDの先頭とパスワードを入力して「接続する」→ カメラ側の確認で「OK」",
            "ルーター経由（インフラストラクチャモード）の場合は、スマホも同じWi-Fiにつないで「接続済みのWi-Fiを使う」（IPは自動で探索）"
        ),
        "PTP/IP＋Canon EOS拡張（EOS Utility接続）。EOS Utility接続の無い機種は利用できません。"
    ),
    SONY(
        "Sony", "Camera Remote API", "DIRECT-", "例: DIRECT-",
        listOf("192.168.122.1"), 8080, setOf("ARW", "SRF", "SR2"),
        listOf(
            "カメラで「スマートフォン操作」（または「スマートリモコン内蔵」アプリ）を起動",
            "カメラに表示されたSSID（DIRECT-…）とパスワードを入力して「接続する」",
            "接続後、カメラが撮影待機画面になるまで数秒待つ"
        ),
        "Camera Remote API（HTTP/JSON）。2018年頃までのα/RX/ZV等が対象。PC Remote専用の新機種は非対応。"
    ),
    LUMIX(
        "LUMIX", "LUMIX Wi-Fi", "", "例: GH5-、DC-S5M2-（カメラ画面のSSID）",
        listOf("192.168.54.1"), 80, setOf("RW2", "RAW"),
        listOf(
            "カメラの Wi-Fi →「Wi-Fi機能」→「新規に接続」→「スマートフォンで撮影/再生」",
            "カメラ画面のSSIDの先頭（パスワード設定時はパスワードも）を入力して「接続する」",
            "カメラに「接続を許可しますか？」が出たら「はい」"
        ),
        "LUMIX Wi-Fi API（HTTP＋UDPライブビュー）。露出値の書き込み形式は機種差があり未検証。"
    );
}

/** AF/MFの種類（メーカー共通の表現）。 */
enum class FocusKind(val label: String) { AFS("AF-S"), AFC("AF-C"), AFA("AF-A"), MF("MF") }

/** 露出設定の種類。値はメーカー共通の単位（ISO=数値/0=AUTO、絞り=F値×100、露出補正=0.001EV、シャッター=ドライバー固有キー）。 */
enum class ExpKind(val label: String) { ISO("ISO"), APERTURE("絞り"), SHUTTER("シャッター速度"), BIAS("露出補正") }

/** カメラごとに使える機能。UIはこれを見て機能を出し分ける。 */
data class CamCaps(
    val liveView: Boolean = false,
    val capture: Boolean = false,
    val touchAf: Boolean = false,
    /** AF/撮影可否のポーリング（Nikon DeviceReady 相当）。無い場合はコマンド応答だけで判断する。 */
    val afReadyPoll: Boolean = false,
    val mfDrive: Boolean = false,
    /** SDカード一覧・サムネイル・RAW取り込み（PTP系のみ） */
    val media: Boolean = false,
    val nativeBurst: Boolean = false,
    val exposure: Boolean = false,
    val focusModes: Set<FocusKind> = emptySet(),
    val focalLength: Boolean = false,
    val propSurvey: Boolean = false,
    /** 動画の録画開始/停止 */
    val movie: Boolean = false
) {
    fun badges(): List<Pair<String, Boolean>> = listOf(
        "ライブビュー" to liveView, "撮影" to capture, "タッチAF" to touchAf, "露出設定" to exposure,
        "MF駆動" to mfDrive, "動画" to movie, "SD転送" to media, "カメラ連写" to nativeBurst, "焦点距離" to focalLength
    )
}

data class CameraIdentity(
    val brand: CameraBrand,
    val manufacturer: String,
    val model: String,
    val firmware: String,
    val protocol: String,
    val host: String,
    val caps: CamCaps
) {
    /** 表示名。メーカー名の「Corporation」等を除き、機種名がメーカー名で始まる場合（Canon EOS R6 など）は重ねない。 */
    val title: String get() {
        val maker = manufacturer.replace(Regex("(?i)\\s*(Corporation|Corp\\.?|Inc\\.?|Co\\.,? ?Ltd\\.?)$"), "").trim()
        val m = model.trim()
        return when {
            m.isEmpty() -> maker.ifBlank { brand.label }
            maker.isEmpty() || m.startsWith(maker, ignoreCase = true) -> m
            else -> "$maker $m"
        }
    }
}

data class LvFrame(val code: Int, val jpeg: ByteArray?)

data class SetResult(val ok: Boolean, val msg: String)

/** シャッター速度の選択肢。raw はドライバー固有のキー（Nikon=0.1ms単位、その他=マイクロ秒）。options は速い→遅い順。 */
data class ShutterOption(val raw: Long, val label: String, val seconds: Double)

/** カメラが実際に返した露出関連の現在値と選択肢（入力値ではなくカメラ側の値）。 */
data class ExposureInfo(
    val shutterOptions: List<ShutterOption> = emptyList(),
    val shutterRaw: Long? = null,
    val shutterLabel: String? = null,
    val modeCode: Int? = null,
    val modeLabel: String? = null,
    val iso: Long? = null,
    val isoOptions: List<Long> = emptyList(),
    val apertureX100: Long? = null,
    val apertureOptions: List<Long> = emptyList(),
    /** 露出補正（0.001EV単位、符号付き） */
    val biasMilli: Long? = null,
    val biasOptions: List<Long> = emptyList(),
    val focus: FocusKind? = null,
    val battery: Int? = null,
    /** 選択肢がカメラから得られず標準の一覧を使っている（LUMIX）。 */
    val synthesizedOptions: Boolean = false
)

/** 応答コード（PTPのコード体系をメーカー共通の結果表現として使う）。 */
object Rc {
    const val OK = 0x2001
    const val GENERAL = 0x2002
    const val NOT_SUPPORTED = 0x2005
    const val BUSY = 0x2019
    const val CANON_NOT_READY = 0xA102
    fun isBusy(code: Int) = code == BUSY || code == CANON_NOT_READY
    fun ok(note: String = "") = PtpResult(OK, emptyList(), ByteArray(0), note)
    fun busy(note: String = "") = PtpResult(BUSY, emptyList(), ByteArray(0), note)
    fun fail(note: String, code: Int = GENERAL) = PtpResult(code, emptyList(), ByteArray(0), note)
    fun unsupported(what: String) = PtpResult(NOT_SUPPORTED, emptyList(), ByteArray(0), "（$what はこのカメラでは未対応）")
}

/**
 * メーカーごとの通信ドライバー。すべてブロッキングI/O。呼び出し側（ViewModel）が Mutex で直列化して使う。
 * 未対応の操作は Rc.unsupported を返す（成功を装わない）。
 */
interface CameraDriver : Closeable {
    val brand: CameraBrand
    val host: String
    val caps: CamCaps
    val identity: CameraIdentity?
    /** PTP系（Nikon/Canon）のみ。SDカード操作・診断で直接使う。 */
    val ptp: PtpIpClient? get() = null
    val deviceInfo: PtpDeviceInfo? get() = null
    /** 撮影通知(0x4002)・設定変更(0x4006)など。PTPイベントの番号に揃えて通知する。 */
    var onEvent: ((code: Int, params: List<Int>) -> Unit)?

    fun open(): CameraIdentity

    fun startLiveView(): PtpResult
    fun endLiveView(): PtpResult
    fun liveViewFrame(): LvFrame

    /** 1回分の撮影指示（Busy時の再試行は呼び出し側）。 */
    fun capture(): PtpResult

    /** 画像内の相対位置(0..1)へAF枠を移してAFを開始する。lvW/lvH はライブビュー画像の画素数。 */
    fun afAt(nx: Float, ny: Float, lvW: Int, lvH: Int): PtpResult
    fun cancelAf(): PtpResult = Rc.ok()
    /** OK=撮影/AF可能、BUSY=処理中。afReadyPoll=false のカメラは常にOK。 */
    fun afReady(): PtpResult = Rc.ok()
    /** MFでレンズを駆動。nearFlag はユーザーが確認した「Near側」の方向フラグ（1/2）。 */
    fun mfDrive(far: Boolean, steps: Int, nearFlag: Int): PtpResult = Rc.unsupported("MF駆動")
    fun focalLengthMm(): Double? = null

    fun readExposure(): ExposureInfo
    /** 露出値を書き込み、カメラの値を読み直して照合した結果を返す。 */
    fun setExposure(kind: ExpKind, value: Long): String
    fun focusMode(): FocusKind? = null
    fun setFocusMode(kind: FocusKind): SetResult = SetResult(false, "${kind.label} への切替はこのカメラでは未対応です")

    /** カメラ本体の連続撮影（高速連写）。戻り値=(使える, 説明, 復元用の元の値) */
    fun prepareNativeBurst(n: Int): Triple<Boolean, String, Long?> =
        Triple(false, "高速: このカメラではカメラ連写の設定に未対応のため、通常方式で連写します", null)
    fun restoreNativeBurst(saved: Long) {}

    /** 動画の録画開始/停止（カメラが動画モード・ライブビュー中である必要がある機種が多い）。 */
    fun startMovie(): PtpResult = Rc.unsupported("動画撮影")
    fun stopMovie(): PtpResult = Rc.unsupported("動画撮影")

    /** 定期的に呼ばれる（イベント取得・接続維持）。 */
    fun keepAlive() {}

    /** 別スレッドから、ブロック中の通信を中断する。 */
    fun abort()
    /** セッションを正常に閉じる（失敗しても例外にしない）。 */
    fun closeGracefully() { close() }
}

fun CameraDriver.requirePtp(): PtpIpClient =
    ptp ?: throw UnsupportedOperationException("${brand.label} ではSDカード操作（PTP）に未対応です")

/** 主要メーカーのRAW形式（拡張子）と MIME タイプ。 */
object RawFormats {
    private val mimes = mapOf(
        "NEF" to "image/x-nikon-nef", "NRW" to "image/x-nikon-nrw",
        "CR2" to "image/x-canon-cr2", "CR3" to "image/x-canon-cr3", "CRW" to "image/x-canon-crw",
        "ARW" to "image/x-sony-arw", "SRF" to "image/x-sony-srf", "SR2" to "image/x-sony-sr2",
        "RAF" to "image/x-fuji-raf", "ORF" to "image/x-olympus-orf", "RW2" to "image/x-panasonic-rw2",
        "RAW" to "image/x-panasonic-raw", "DNG" to "image/x-adobe-dng", "PEF" to "image/x-pentax-pef",
        "SRW" to "image/x-samsung-srw", "3FR" to "image/x-hasselblad-3fr", "IIQ" to "image/x-phaseone-iiq",
        "ERF" to "image/x-epson-erf", "MEF" to "image/x-mamiya-mef", "MOS" to "image/x-leaf-mos",
        "X3F" to "image/x-sigma-x3f", "GPR" to "image/x-gopro-gpr"
    )
    val EXT: Set<String> = mimes.keys
    fun ext(name: String): String = name.substringAfterLast('.', "").uppercase()
    fun isRaw(name: String): Boolean = ext(name) in EXT
    fun mime(name: String): String = mimes[ext(name)] ?: "application/octet-stream"
}

/** リトルエンディアンの数値変換。 */
object Le {
    fun value(b: ByteArray): Long {
        var v = 0L
        for (i in b.indices.reversed()) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }
    fun signed(b: ByteArray): Long {
        var v = value(b)
        val bits = b.size * 8
        if (bits in 1..63 && ((v shr (bits - 1)) and 1L) == 1L) v -= (1L shl bits)
        return v
    }
    fun bytes(value: Long, size: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array().copyOf(size)
}

/** 表示用の書式と、公称値へのスナップ。 */
object ExpFormat {
    private val nominalIso = longArrayOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500,
        3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200, 64000, 80000,
        102400, 128000, 204800, 409600, 819200
    )
    /** F値×10 の公称値（1/3段・1/2段）。 */
    private val nominalF10 = intArrayOf(
        10, 11, 12, 14, 16, 17, 18, 20, 22, 24, 25, 28, 32, 34, 35, 40, 45, 48, 50, 56, 63, 67, 71, 80, 90, 95, 100,
        110, 130, 140, 160, 180, 190, 200, 220, 250, 270, 290, 320, 360, 380, 400, 450, 510, 570, 640, 720, 810, 910
    )
    /** 1/3段の標準シャッター速度（秒）。速い→遅い。 */
    val stdShutterSeconds: List<Double> = buildList {
        listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125,
            100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3).forEach { add(1.0 / it) }
        addAll(listOf(0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0, 60.0))
    }

    fun isoLabel(v: Long?): String = when (v) { null -> "—"; 0L -> "AUTO"; else -> v.toString() }
    fun apertureLabel(x100: Long?): String = when {
        x100 == null -> "—"
        x100 % 100L == 0L -> "F${x100 / 100}"
        else -> "F%.1f".format(x100 / 100.0)
    }
    fun biasLabel(milli: Long?): String = milli?.let { if (it == 0L) "±0.0" else "%+.1f".format(it / 1000.0) } ?: "—"

    fun snapIso(v: Double): Long = nominalIso.minByOrNull { abs(ln(it / v)) } ?: v.toLong()
    fun snapApertureX100(f: Double): Long = (nominalF10.minByOrNull { abs(ln(it / 10.0 / f)) } ?: (f * 10).toInt()) * 10L

    fun shutterLabel(sec: Double): String = when {
        sec <= 0.0 -> "?"
        sec < 0.3 -> "1/${Math.round(1.0 / sec)}"
        sec < 1.0 -> "%.1f\"".format(sec)
        abs(sec - Math.round(sec)) < 0.05 -> "${Math.round(sec)}\""
        else -> "%.1f\"".format(sec)
    }
    fun micros(sec: Double): Long = Math.round(sec * 1_000_000.0).coerceAtLeast(1L)

    /** 一覧の中で値に最も近いもの（線形距離）。 */
    fun nearest(options: Collection<Long>, target: Long): Long? = options.minByOrNull { abs(it - target) }
}
