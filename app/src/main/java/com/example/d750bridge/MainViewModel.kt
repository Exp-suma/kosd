package com.example.d750bridge

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.d750bridge.platform.PendingFile
import com.example.d750bridge.platform.PlatformIo
import com.example.d750bridge.platform.SaveFolder
import com.example.d750bridge.ai.AfConst
import com.example.d750bridge.ai.AiConfig
import com.example.d750bridge.ai.AiPlan
import com.example.d750bridge.ai.LumaGrid
import com.example.d750bridge.ai.NightMode
import com.example.d750bridge.ai.Region
import com.example.d750bridge.ai.Subject
import com.example.d750bridge.ai.SubjectDetector
import com.example.d750bridge.ai.TargetTracker
import com.example.d750bridge.ai.landscapePlan
import com.example.d750bridge.ai.vehicleAnchor
import com.example.d750bridge.camera.CameraBrand
import com.example.d750bridge.camera.CameraDriver
import com.example.d750bridge.camera.CameraIdentity
import com.example.d750bridge.camera.CameraLocator
import com.example.d750bridge.camera.CanonDriver
import com.example.d750bridge.camera.ExpFormat
import com.example.d750bridge.camera.ExpKind
import com.example.d750bridge.camera.ExposureInfo
import com.example.d750bridge.camera.FocusKind
import com.example.d750bridge.camera.Jpeg
import com.example.d750bridge.camera.Le
import com.example.d750bridge.camera.LumixDriver
import com.example.d750bridge.camera.NikonDriver
import com.example.d750bridge.camera.Rc
import com.example.d750bridge.camera.SonyDriver
import com.example.d750bridge.camera.requirePtp
import com.example.d750bridge.focus.DistanceEst
import com.example.d750bridge.focus.DistanceFocusMap
import com.example.d750bridge.focus.FocusAnalyzer
import com.example.d750bridge.orbis.OrbisCfg
import com.example.d750bridge.orbis.OrbisHistoryStore
import com.example.d750bridge.orbis.OrbisHit
import com.example.d750bridge.orbis.PlateOcr
import com.example.d750bridge.orbis.PlateRead
import com.example.d750bridge.orbis.PlateVotes
import com.example.d750bridge.orbis.cropVehicleForOcr
import com.example.d750bridge.speed.SpeedEstimator
import com.example.d750bridge.speed.SpeedInfo
import com.example.d750bridge.speed.SpeedProfile
import com.example.d750bridge.speed.SpeedProfileStore
import com.example.d750bridge.track.Appearance
import com.example.d750bridge.track.TrackCfg
import com.example.d750bridge.track.TrackManager
import com.example.d750bridge.track.TrackState
import com.example.d750bridge.track.TrackView
import com.example.d750bridge.focus.FocusCfg
import com.example.d750bridge.focus.FocusProfileStore
import com.example.d750bridge.focus.FocusUi
import com.example.d750bridge.focus.Meas
import com.example.d750bridge.focus.Sharp
import com.example.d750bridge.net.CameraWifi
import com.example.d750bridge.net.WifiPhase
import com.example.d750bridge.ptp.PtpDeviceInfo
import com.example.d750bridge.ptp.PtpException
import com.example.d750bridge.ptp.PtpConnectException
import com.example.d750bridge.ptp.PtpIpClient
import com.example.d750bridge.ptp.PtpNames
import com.example.d750bridge.ptp.PtpResult
import com.example.d750bridge.ptp.ShutterSpeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/** 接続の段階（接続画面のステッパー表示用）。 */
enum class ConnStage(val label: String) {
    IDLE("未接続"),
    WIFI("Wi-Fiに接続中"),
    LOCATING("カメラを探しています"),
    SESSION("カメラと通信を確立中"),
    READY("接続完了"),
    FAILED("接続できませんでした")
}

data class UiState(
    val brand: CameraBrand = CameraBrand.NIKON,
    val ssidPrefix: String = "Nikon_",
    val passphrase: String = "",          // メモリ上のみ。ログ・保存しない
    val autoIp: Boolean = true,
    val cameraIp: String = "192.168.1.1", // 手動入力時
    val resolvedHost: String? = null,     // 自動検出で見つけたカメラのアドレス
    val stage: ConnStage = ConnStage.IDLE,
    val camera: CameraIdentity? = null,
    val probeBusy: Boolean = false,
    val probeStatus: String = "未接続",
    val deviceInfo: PtpDeviceInfo? = null,
    val readouts: List<String> = emptyList(),
    val readoutsBusy: Boolean = false,
    val captureBusy: Boolean = false,
    val captureStatus: String = "未実行",
    val storageIds: List<Int> = emptyList(),
    val selectedStorageId: Int? = null,
    val mediaItems: List<MediaUiItem> = emptyList(),
    val mediaStatus: String = "未取得",
    val mediaLoading: Boolean = false,
    val selectedHandles: Set<Int> = emptySet(),
    val moreMediaAvailable: Boolean = false,
    val transferBusy: Boolean = false,
    val transferProgress: String = "未実行",
    val transferPercent: Int = 0,
    val liveViewRunning: Boolean = false,
    val liveViewStatus: String = "停止中",
    val liveFps: Float = 0f,
    val afStatus: String = "未実行",
    val settingStatus: String = "未実行",
    val settingBusy: Boolean = false,
    val exposure: ExposureInfo = ExposureInfo()
) {
    val connected: Boolean get() = stage == ConnStage.READY && camera != null
}

/** ライブビュー上のAF枠。nx,ny は画像内の相対位置(0..1)。ok: null=実行中 / true=AF受理 / false=失敗 */
data class AfTarget(val nx: Float, val ny: Float, val ok: Boolean? = null)

/** AF所要時間の実測。count は DeviceReady OK を確認できた回数。 */
data class AfStat(val count: Int, val avgMs: Long, val lastMs: Long, val cmdAvgMs: Long)

data class AiUi(
    val modelStatus: String = "未初期化（AI検出ONで読み込み）",
    val detectMs: Long = 0,
    val detCount: Int = 0,
    val night: Boolean = false,
    val luma: Int = 0,
    val planNote: String = "",
    val lastAction: String = "—",
    val stats: Map<String, AfStat> = emptyMap()
)

private val OCCLUDER_LABELS = setOf("person", "bicycle", "motorcycle", "car", "bus", "truck", "train")
private const val MAX_SINGLE_STEP = 2000   // MfDrive 1回あたりの上限（暴走防止）
private const val MIN_SCORE = 0.8f        // これ未満のシャープネスは低コントラストとして解析しない（目安値・要実機調整）

private data class AfRun(val ok: Boolean, val msg: String, val cmdMs: Long)

enum class BurstMode(val label: String) { NORMAL("通常"), HIGH("高速"), AI_FOLLOW("AI追従") }

data class BurstCfg(
    val mode: BurstMode = BurstMode.NORMAL,
    val limited: Boolean = false,      // false=停止ボタンまで（上限999）
    val count: Int = 10,
    val intervalMs: Int = 0,           // 通常/AI追従のみ。0=カメラが受け付ける限り最速
    val nativeN: Int = 5               // 高速: 1回の撮影指示でカメラに要求する枚数
)

data class BurstUi(
    val state: String = "待機",
    val shots: Int = 0,                // 撮影指示の回数
    val events: Int = 0,               // カメラからのオブジェクト追加通知の件数（RAW+JPEGなら1枚で2件）
    val fps: Float = 0f,
    val errors: Int = 0,
    val busyWaits: Int = 0,
    val cameraReady: String = "—",
    val note: String = ""
)

/** インターバル撮影の設定。 */
data class TlCfg(
    val intervalSec: Int = 5,
    val count: Int = 240,
    val unlimited: Boolean = false,
    val shoot: Boolean = true,      // カメラで本撮影（SDカードに保存）
    val record: Boolean = true,     // ライブビュー画像を動画用のコマとして端末に保存
    val fps: Int = 24,
    val startDelaySec: Int = 0      // 開始までの待ち時間
)

data class TlUi(
    val running: Boolean = false,
    val shots: Int = 0,
    val frames: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val nextAtMs: Long = 0L,
    val startedAtMs: Long = 0L,
    val status: String = "待機",
    val encoding: Boolean = false,
    val encodeProgress: Float = 0f,
    val lastVideo: String? = null,
    /** 最近のコマ（コマ番号, 縮小画像）。新しい順。 */
    val strip: List<Pair<Int, Bitmap>> = emptyList()
)

/** フォーカスブラケットの設定。step はMF駆動のステップ数（1枚ごとの移動量）。 */
data class FbCfg(
    val shots: Int = 10,
    val step: Int = 30,
    val towardFar: Boolean = true,
    val returnToStart: Boolean = true,
    val settleMs: Int = 300
)

data class FbUi(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val status: String = "待機")

data class PredCfg(val enabled: Boolean = false, val fineTune: Boolean = false, val leadMs: Int = 300)
data class PredUi(val status: String = "OFF", val distM: Float? = null, val points: Int = 0, val slope: Float? = null)

/** 置きピン。nx,ny は画像内の相対位置。lockNote はピン固定(MF切替)の結果説明。 */
data class PinState(val nx: Float, val ny: Float, val lockNote: String)

data class PreviewState(val name: String, val bitmap: Bitmap?, val loading: Boolean)

data class MediaUiItem(val handle: Int, val name: String, val size: Long, val format: Int)

/**
 * 最適化の要点
 *  1. カメラとのセッションを1本だけ保持し、Mutexで直列化して全機能で共有する。
 *     メーカーごとの通信は camera/ 配下のドライバー（Nikon / Canon / Sony / LUMIX）が担当する。
 *  2. ライブビューは「取得」と「JPEGデコード」を別コルーチンに分離し、最新フレームのみ表示する。
 *     フレームは UiState に入れず専用の StateFlow で配信（画面全体の再描画を防ぐ）。
 *  3. 連写は DeviceBusy(0x2019) を再試行し、固定レートでペース配分する。
 *  4. 露出値はカメラの選択肢へスナップして書き込み、読み直して照合する（ドライバー側）。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val wifi = CameraWifi(app)
    private val prefs = app.getSharedPreferences("conn", Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(loadInitialUi())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 画面下部のスナックバーに出す通知（接続・失敗など）。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private fun notify(msg: String) { _messages.tryEmit(msg) }

    private val _liveFrame = MutableStateFlow<Bitmap?>(null)
    val liveFrame: StateFlow<Bitmap?> = _liveFrame.asStateFlow()

    private val _afTarget = MutableStateFlow<AfTarget?>(null)
    val afTarget: StateFlow<AfTarget?> = _afTarget.asStateFlow()

    private val _thumbs = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val thumbs: StateFlow<Map<Int, Bitmap>> = _thumbs.asStateFlow()
    private val _preview = MutableStateFlow<PreviewState?>(null)
    val preview: StateFlow<PreviewState?> = _preview.asStateFlow()
    private var thumbJob: Job? = null
    private var previewJob: Job? = null

    @Volatile private var shutterPressed = false
    private var shutterJob: Job? = null

    /** 撮影指示が受理されるたびに+1（UIのシャッターフラッシュ・枚数表示用）。 */
    private val _shotTick = MutableStateFlow(0)
    val shotTick: StateFlow<Int> = _shotTick.asStateFlow()

    private val ipRegex = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    private var allMediaHandles: List<Int> = emptyList()
    private var mediaPage = 0
    private var liveViewJob: Job? = null
    private var transferJob: Job? = null
    private var keepAliveJob: Job? = null

    // ---- AI AF支援 ----
    private val _aiCfg = MutableStateFlow(AiConfig())
    val aiCfg: StateFlow<AiConfig> = _aiCfg.asStateFlow()
    private val _aiUi = MutableStateFlow(AiUi())
    val aiUi: StateFlow<AiUi> = _aiUi.asStateFlow()
    private val _aiPlan = MutableStateFlow<AiPlan?>(null)
    val aiPlan: StateFlow<AiPlan?> = _aiPlan.asStateFlow()
    private var detector: SubjectDetector? = null
    private val _pin = MutableStateFlow<PinState?>(null)
    val pin: StateFlow<PinState?> = _pin.asStateFlow()
    private var savedFocusMode: FocusKind? = null     // 置きピンでMFへ切り替える前のフォーカスモード（解除時に戻す）
    private var lastAiArea = 0f
    private val tracker = TargetTracker()
    private val afInFlight = AtomicBoolean(false)
    private var lastAiAfNanos = 0L
    private var lastAiPoint: Pair<Float, Float>? = null
    private val afSamples = HashMap<String, MutableList<Pair<Long, Long>>>() // 方式 -> (AfDrive応答ms, 合計ms)

    // ---- 共有セッション ----
    private val sessionMutex = Mutex()
    private var session: CameraDriver? = null
    private var sessionNet: Network? = null
    private var sessionKey: String = ""
    @Volatile private var sessionRef: CameraDriver? = null
    @Volatile private var autoConnectPending = false

    /** インストールごとの識別子（Canon/LUMIXのペアリングに使う）。 */
    private val installGuid: ByteArray by lazy {
        val hex = prefs.getString("guid", null)?.takeIf { it.length == 32 }
            ?: java.util.UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("guid", it).apply() }
        ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
    private val installIdHex: String get() = installGuid.joinToString("") { "%02x".format(it) }

    private val caps get() = _ui.value.camera?.caps

    private fun loadInitialUi(): UiState {
        val b = prefs.getString("brand", null)?.let { n -> CameraBrand.values().firstOrNull { it.name == n } } ?: CameraBrand.NIKON
        return UiState(
            brand = b,
            ssidPrefix = prefs.getString("ssid_${b.name}", b.defaultSsidPrefix) ?: b.defaultSsidPrefix,
            autoIp = prefs.getBoolean("autoIp", true),
            cameraIp = prefs.getString("ip_${b.name}", b.defaultHosts.first()) ?: b.defaultHosts.first()
        )
    }

    /** メーカーの切替（接続中は切り替えない）。SSID・IPはメーカーごとに保存した値を使う。 */
    fun setBrand(b: CameraBrand) {
        if (b == _ui.value.brand || _ui.value.probeBusy) return
        if (session != null || _ui.value.camera != null) disconnectAll()
        prefs.edit().putString("brand", b.name).apply()
        if (b != CameraBrand.CANON) stopCanonResponder()
        _ui.update {
            it.copy(
                brand = b,
                ssidPrefix = prefs.getString("ssid_${b.name}", b.defaultSsidPrefix) ?: b.defaultSsidPrefix,
                cameraIp = prefs.getString("ip_${b.name}", b.defaultHosts.first()) ?: b.defaultHosts.first(),
                resolvedHost = null, stage = ConnStage.IDLE, probeStatus = "未接続"
            )
        }
    }
    fun setSsidPrefix(v: String) {
        _ui.update { it.copy(ssidPrefix = v) }
        prefs.edit().putString("ssid_${_ui.value.brand.name}", v).apply()
    }
    fun setPassphrase(v: String) = _ui.update { it.copy(passphrase = v) }
    fun setCameraIp(v: String) {
        _ui.update { it.copy(cameraIp = v) }
        prefs.edit().putString("ip_${_ui.value.brand.name}", v).apply()
    }
    fun setAutoIp(on: Boolean) {
        _ui.update { it.copy(autoIp = on, resolvedHost = null) }
        prefs.edit().putBoolean("autoIp", on).apply()
    }
    fun setVerboseLog(on: Boolean) { AppLog.verbose = on }
    /** 主要メーカーのRAW形式すべて（CR3 / CR2 / NEF / ARW / RAF / ORF / RW2 / DNG など）。 */
    fun isRaw(item: MediaUiItem): Boolean = com.example.d750bridge.camera.RawFormats.isRaw(item.name)

    // ------------------------------------------------------------------
    // セッション管理
    // ------------------------------------------------------------------
    /**
     * カメラから直接受け取った画像（Canonで保存先がPCのとき）をスマホに保存する。
     * JPEG / HEIF は「ピクチャ/CamBridge」、RAW などは「Download/CamBridge」。
     */
    private fun saveReceivedImage(name: String, data: ByteArray) {
        val ext = name.substringAfterLast('.').uppercase()
        val isImage = ext == "JPG" || ext == "HIF"
        val mime = when (ext) { "JPG" -> "image/jpeg"; "HIF" -> "image/heif"; else -> com.example.d750bridge.camera.RawFormats.mime(name) }
        val f = PlatformIo.create(getApplication(), if (isImage) SaveFolder.PICTURES else SaveFolder.DOWNLOAD, name, mime)
        try {
            f.openStream().use { it.write(data) }
            f.publish()
            notify("カメラから $name を受け取り、保存しました")
        } catch (e: Exception) {
            f.discard(); throw e
        }
    }

    // ---- Canon EOS Utility モードのペアリング支援（カメラの「パソコン検索」に応答する） ----
    private var canonResponder: com.example.d750bridge.camera.CanonUpnpResponder? = null
    private var canonResponderNet: Network? = null

    private fun ensureCanonResponder() {
        val net = wifi.network ?: return
        val ip = wifi.state.value.phoneIp ?: return
        if (_ui.value.brand != CameraBrand.CANON) { stopCanonResponder(); return }
        val cur = canonResponder
        if (cur != null && cur.running && canonResponderNet == net) return
        stopCanonResponder()
        canonResponder = com.example.d750bridge.camera.CanonUpnpResponder(getApplication(), net, ip, installGuid).also { it.start() }
        canonResponderNet = net
    }

    private fun stopCanonResponder() { canonResponder?.stop(); canonResponder = null; canonResponderNet = null }

    private fun locateStatus(msg: String) { AppLog.i(msg); _ui.update { it.copy(probeStatus = msg) } }

    private fun keyOf(s: UiState) = "${s.brand}|${if (s.autoIp) "auto" else s.cameraIp.trim()}"

    private fun isPtp(b: CameraBrand) = b == CameraBrand.NIKON || b == CameraBrand.CANON

    /**
     * Sony / LUMIX（HTTP）の接続先。DHCPサーバー/ゲートウェイ/メーカー既定値へ並列にTCP接続を試す。
     * PTP系（Nikon/Canon）はここを使わない: カメラは同時に1本しか接続を受けないため、
     * 探るだけのTCP接続を入れると本番の接続が応答されず read timeout になる（v1.0.0の不具合）。
     */
    private fun resolveHttpHostLocked(net: Network, s: UiState): String {
        _ui.update { if (it.stage != ConnStage.READY) it.copy(stage = ConnStage.LOCATING) else it }
        val cands = CameraLocator.candidates(wifi.state.value, s.brand)
        AppLog.i("カメラIP自動検出（${s.brand.label}）: 候補 ${cands.joinToString()}")
        val ports = if (s.brand == CameraBrand.SONY) listOf(8080, 10000) else listOf(s.brand.port)
        var found: String? = null
        for (p in ports) { found = CameraLocator.firstReachable(net, cands, p); if (found != null) break }
        return found ?: if (s.brand == CameraBrand.SONY) (cands.firstOrNull() ?: s.brand.defaultHosts.first())   // SonyはSSDPでも探す
        else throw IllegalStateException(
            "カメラが見つかりません（${cands.joinToString()} のポート ${s.brand.port} が応答しません）。" +
                "カメラ側のWi-Fi設定を確認するか、詳細設定で手動IPを指定してください"
        )
    }

    /** PTP系: 候補に直接つないでも見つからないときの探索（UPnP → /24全探索 → Canonは待ち受け待機）。 */
    private fun discoverPtpHostLocked(net: Network, s: UiState, tried: List<String>): String {
        val w = wifi.state.value
        locateStatus("UPnPでカメラを探しています…")
        val ssdp = CameraLocator.ssdpScan(net)
        val canonIps = ssdp.filter { it.looksCanon }.map { it.ip }.distinct()
        val ssdpIps = (canonIps + ssdp.map { it.ip }).distinct().filter { it != w.phoneIp && it !in tried }
        var found: String? = if (ssdpIps.isNotEmpty()) CameraLocator.firstReachable(net, ssdpIps, s.brand.port) else null
        if (found == null) {
            locateStatus("ネットワーク内のカメラを探しています（${w.phoneIp?.substringBeforeLast('.') ?: "?"}.1〜254）…")
            found = CameraLocator.scanSubnet(net, w.phoneIp, s.brand.port).firstOrNull { it !in tried }
        }
        val seen = (canonIps.ifEmpty { listOfNotNull(w.dhcpServer) }).firstOrNull()
        if (found == null && s.brand == CameraBrand.CANON && seen != null) {
            val ok = CameraLocator.waitOpen(net, seen, s.brand.port, 30_000) { left ->
                locateStatus("カメラ（$seen）の待ち受けを待っています…あと${left}秒。カメラが「接続待機中／ペアリング」画面か確認してください")
            }
            if (ok) found = seen
        }
        if (found == null) {
            val ssdpNote = if (ssdp.isEmpty()) "UPnP応答なし" else "UPnP応答: ${ssdp.map { it.ip }.distinct().joinToString()}${if (canonIps.isNotEmpty()) "（Canon: ${canonIps.joinToString()}）" else ""}"
            throw IllegalStateException(
                "カメラが見つかりません。${tried.joinToString()} と ${w.phoneIp?.substringBeforeLast('.') ?: "?"}.1〜254 のポート ${s.brand.port} が応答しません（$ssdpNote）。" +
                    if (s.brand == CameraBrand.CANON) "カメラのWi-Fi機能が「EOS Utility（リモート操作）」で接続待機中になっているか、カメラ画面のIPアドレスを詳細設定の手動IPに入れて試してください"
                    else "カメラのWi-Fi（ネットワーク接続）が有効か確認するか、詳細設定で手動IP（D750の既定は192.168.1.1）を指定してください"
            )
        }
        Thread.sleep(1500)   // 探索で開いた接続をカメラが片付ける時間
        return found
    }

    private fun openDriverLocked(net: Network, s: UiState, host: String, connectMs: Int): CameraDriver {
        val hint = if (s.brand == CameraBrand.CANON) "（カメラの画面に接続の確認が出たら「OK」を押してください。最大90秒待ちます）" else ""
        _ui.update { if (it.stage != ConnStage.READY) it.copy(stage = ConnStage.SESSION, probeStatus = "$host と通信を確立しています…$hint") else it }
        val d: CameraDriver = when (s.brand) {
            CameraBrand.NIKON -> NikonDriver(net, host, connectMs)
            CameraBrand.CANON -> CanonDriver(net, host, installGuid, connectMs)
            CameraBrand.SONY -> SonyDriver(net, host)
            CameraBrand.LUMIX -> LumixDriver(net, host, installIdHex)
        }
        d.onEvent = eventHandler
        if (d is CanonDriver) d.onObjectData = { name, bytes -> saveReceivedImage(name, bytes) }
        sessionRef = d
        try {
            val id = d.open()
            AppLog.i("接続: ${id.title} / ${id.protocol} / ${id.host}")
            _ui.update { it.copy(camera = id, deviceInfo = d.deviceInfo, resolvedHost = if (s.autoIp) d.host else it.resolvedHost) }
            return d
        } catch (e: Exception) {
            sessionRef = null
            d.close()
            throw e
        }
    }

    /** TCPはつながったがハンドシェイクで失敗した場合（前の接続の後始末中など）は、2秒待って1回だけやり直す。 */
    private fun openWithRetryLocked(net: Network, s: UiState, host: String, connectMs: Int): CameraDriver =
        if (s.brand == CameraBrand.CANON) openCanonLocked(net, s, host, connectMs) else openWithRetryInner(net, s, host, connectMs)

    /**
     * Canon: カメラは「パソコン検索」で見つけた相手だけを受け付ける。拒否（Init_Fail）された場合は、
     * カメラ側でこのアプリ（CamBridge）が選ばれるまで、応答を続けながら最大90秒やり直す。
     */
    private fun openCanonLocked(net: Network, s: UiState, host: String, connectMs: Int): CameraDriver {
        ensureCanonResponder()
        val end = System.currentTimeMillis() + 90_000
        var attempt = 0
        while (true) {
            attempt++
            try {
                return openDriverLocked(net, s, host, connectMs)
            } catch (e: com.example.d750bridge.ptp.PtpException) {
                val rejected = e.message?.contains("拒否") == true && e.message?.contains("使用中") != true
                if (!rejected || System.currentTimeMillis() > end) throw e
                val r = canonResponder
                val left = ((end - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                val seen = if (r != null && r.searches > 0) "カメラがこの${com.example.d750bridge.platform.PlatformInfo.deviceWord}を見つけています。" else "カメラからの検索をまだ受信していません。"
                _ui.update { it.copy(probeStatus = "${seen}カメラの画面で接続先「CamBridge」を選んで「OK」を押してください（あと${left}秒）") }
                AppLog.i("Canon: 拒否されたため再試行 $attempt 回目（検索受信 ${r?.searches ?: 0} 回 / 説明取得 ${r?.descFetches ?: 0} 回）")
                Thread.sleep(3000)
            }
        }
    }

    private fun openWithRetryInner(net: Network, s: UiState, host: String, connectMs: Int): CameraDriver = try {
        openDriverLocked(net, s, host, connectMs)
    } catch (e: PtpConnectException) {
        throw e
    } catch (e: IOException) {
        if (s.brand != CameraBrand.NIKON) throw e   // Canonは再接続するとカメラ側の確認画面が取り消されるため再試行しない
        AppLog.w("$host との通信確立に失敗（${e.javaClass.simpleName}: ${e.message}）。2秒後に再試行します")
        _ui.update { it.copy(probeStatus = "応答がないため再試行しています…") }
        Thread.sleep(2000)
        openDriverLocked(net, s, host, connectMs)
    }

    private fun ensureSessionLocked(): CameraDriver {
        val net = wifi.network ?: throw IllegalStateException("カメラWi-Fi未接続")
        val s = _ui.value
        val cur = session
        if (cur != null && sessionNet == net && sessionKey == keyOf(s)) return cur
        dropSessionLocked(false)
        val d: CameraDriver = when {
            !s.autoIp -> {
                val ip = s.cameraIp.trim()
                if (!ipRegex.matches(ip)) throw IllegalStateException("カメラIPの形式が不正: '$ip'")
                openWithRetryLocked(net, s, ip, 10_000)
            }
            s.resolvedHost != null -> try {
                openWithRetryLocked(net, s, s.resolvedHost, 10_000)
            } catch (e: Exception) {
                _ui.update { it.copy(resolvedHost = null) }; throw e
            }
            isPtp(s.brand) -> {
                // 探りの接続を入れず、候補へ順に本番の接続を試す（TCP接続できない番地だけ次へ進む）
                _ui.update { if (it.stage != ConnStage.READY) it.copy(stage = ConnStage.LOCATING) else it }
                val w = wifi.state.value
                val quick = (listOfNotNull(w.dhcpServer) + s.brand.defaultHosts + listOfNotNull(w.gateway)).distinct().filter { it != w.phoneIp }
                AppLog.i("カメラIP自動検出（${s.brand.label}）: 直接接続を試す順 ${quick.joinToString()}")
                var got: CameraDriver? = null
                for (h in quick) {
                    locateStatus("$h に接続しています…")
                    try { got = openWithRetryLocked(net, s, h, 3000); break }
                    catch (e: PtpConnectException) { AppLog.w(e.message ?: "接続不可: $h") }
                }
                got ?: openWithRetryLocked(net, s, discoverPtpHostLocked(net, s, quick), 10_000)
            }
            else -> {
                val host = resolveHttpHostLocked(net, s)
                try { openDriverLocked(net, s, host, 10_000) } catch (e: Exception) { _ui.update { it.copy(resolvedHost = null) }; throw e }
            }
        }
        if (s.autoIp) _ui.update { it.copy(resolvedHost = d.host) }
        session = d; sessionNet = net; sessionKey = keyOf(s)
        return d
    }

    private fun dropSessionLocked(graceful: Boolean) {
        val d = session ?: return
        session = null; sessionRef = null
        try { if (graceful) d.closeGracefully() else d.close() } catch (_: Exception) {}
    }

    /** セッションを使ってブロッキングI/Oを実行する。他の操作とは Mutex で直列化される。 */
    private suspend fun <T> withSession(block: (CameraDriver) -> T): T = sessionMutex.withLock {
        withContext(Dispatchers.IO) {
            val d = ensureSessionLocked()
            d.onEvent = eventHandler
            try {
                block(d)
            } catch (e: PtpException) {
                dropSessionLocked(true); throw e
            } catch (e: IOException) {
                dropSessionLocked(false); throw e
            }
        }
    }

    private fun closeSessionAsync(graceful: Boolean = false) {
        if (!graceful) sessionRef?.abort()
        viewModelScope.launch(Dispatchers.IO) {
            val done = withTimeoutOrNull(3000) { sessionMutex.withLock { dropSessionLocked(graceful) } }
            if (done == null) { sessionRef?.abort(); sessionMutex.withLock { dropSessionLocked(false) } }
        }
    }

    /** 接続中は定期的にカメラへ問い合わせる（Canonのイベント取得・LUMIXの接続維持）。LV中はLVループ側で行う。 */
    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2500)
                if (_ui.value.liveViewRunning) continue
                val d = sessionRef ?: continue
                if (!sessionMutex.tryLock()) continue
                try {
                    if (session === d) d.keepAlive()
                } catch (e: IOException) {
                    AppLog.w("接続維持に失敗（次の操作で再接続します）: ${e.message}")
                    dropSessionLocked(false)
                } catch (e: Exception) {
                    AppLog.w("接続維持に失敗: ${e.message}")
                } finally {
                    sessionMutex.unlock()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 接続（Wi-Fi → カメラ検出 → セッション）
    // ------------------------------------------------------------------
    /** ボタン1つで接続: Wi-Fi接続が済んだら自動でカメラ接続へ進む。 */
    fun connectAll() {
        val s = _ui.value
        if (s.probeBusy) return
        if (wifi.network != null && wifi.state.value.phase == WifiPhase.CONNECTED) { connectCamera(); return }
        if (s.ssidPrefix.isBlank()) {
            _ui.update { it.copy(stage = ConnStage.FAILED, probeStatus = "SSIDの先頭を入力するか、「接続済みのWi-Fiを使う」を選んでください") }
            return
        }
        autoConnectPending = true
        _ui.update { it.copy(stage = ConnStage.WIFI, probeStatus = "Wi-Fiに接続中…（システムの確認ダイアログを承認）", resolvedHost = null) }
        wifi.connect(s.ssidPrefix.trim(), s.passphrase.ifEmpty { null })
    }

    /** 端末のWi-Fi設定で接続済みのネットワークを使う。 */
    fun useExistingWifi() {
        if (_ui.value.probeBusy) return
        autoConnectPending = true
        _ui.update { it.copy(stage = ConnStage.WIFI, probeStatus = "接続済みのWi-Fiを確認中…", resolvedHost = null) }
        wifi.useExisting()
    }

    fun disconnectAll() {
        autoConnectPending = false
        liveViewJob?.cancel(); transferJob?.cancel(); thumbJob?.cancel(); shutterPressed = false; burstRun = false; shutterJob?.cancel()
        keepAliveJob?.cancel(); focusJob?.cancel(); predJob?.cancel(); tlJob?.cancel(); fbJob?.cancel(); _recStartMs.value = null
        closeSessionAsync(graceful = true)
        stopCanonResponder()
        wifi.disconnect()
        _ui.update {
            it.copy(
                stage = ConnStage.IDLE, probeStatus = "未接続", deviceInfo = null, camera = null, resolvedHost = null,
                readouts = emptyList(), exposure = ExposureInfo(), mediaItems = emptyList(), selectedHandles = emptySet(),
                storageIds = emptyList(), mediaStatus = "未取得"
            )
        }
        _thumbs.value = emptyMap()
    }

    private fun onWifiChanged(phase: WifiPhase, message: String?) {
        when (phase) {
            WifiPhase.CONNECTED -> if (_ui.value.brand == CameraBrand.CANON) {
                // カメラはWi-Fiにつながった直後からパソコンを探し始めるため、すぐに応答を始める
                viewModelScope.launch { delay(300); ensureCanonResponder() }
                if (autoConnectPending) { autoConnectPending = false; viewModelScope.launch { delay(600); connectCamera() } }
            } else if (autoConnectPending) {
                autoConnectPending = false
                viewModelScope.launch { delay(600); connectCamera() }
            }
            WifiPhase.REQUESTING -> _ui.update { if (it.stage == ConnStage.IDLE || it.stage == ConnStage.FAILED) it.copy(stage = ConnStage.WIFI) else it }
            WifiPhase.LOST -> {
                autoConnectPending = false
                stopCanonResponder()
                liveViewJob?.cancel(); keepAliveJob?.cancel()
                closeSessionAsync()
                if (_ui.value.stage != ConnStage.IDLE) notify("カメラのWi-Fiが切断されました")
                _ui.update { it.copy(stage = ConnStage.IDLE, camera = null, probeStatus = "Wi-Fiが切断されました", resolvedHost = null) }
            }
            WifiPhase.UNAVAILABLE -> {
                autoConnectPending = false
                _ui.update { it.copy(stage = ConnStage.FAILED, probeStatus = message ?: "Wi-Fiに接続できませんでした") }
            }
            WifiPhase.IDLE -> {}
        }
    }

    /** カメラとのセッションを確立する。成功/失敗は実際の通信結果のみで判定する。成功後もセッションは保持する。 */
    fun connectCamera() {
        if (wifi.network == null) {
            _ui.update { it.copy(probeStatus = "Wi-Fi未接続", stage = ConnStage.IDLE) }
            return
        }
        if (_ui.value.probeBusy) return
        _ui.update { it.copy(probeBusy = true, probeStatus = "カメラを探しています…", stage = ConnStage.LOCATING, deviceInfo = null, camera = null) }
        viewModelScope.launch {
            try {
                if (liveViewJob?.isActive == true) liveViewJob?.cancelAndJoin()
                sessionMutex.withLock { withContext(Dispatchers.IO) { dropSessionLocked(true) } } // 常に新規接続
                val exp = withSession { d ->
                    d.deviceInfo?.let { info ->
                        AppLog.i("DeviceInfo: ${info.manufacturer} ${info.model} / ver=${info.deviceVersion} / serial=…${info.serialNumber.takeLast(4)}")
                        AppLog.i("対応操作 ${info.operations.size}件 / 対応プロパティ ${info.properties.size}件 / 拡張=${info.vendorExtensionDesc}")
                        AppLog.i("対応操作一覧: " + info.operations.joinToString(", ") { "${PtpNames.hex(it)}:${PtpNames.op(it, info.vendorExtensionId)}" })
                        AppLog.i("対応プロパティ一覧: " + info.properties.joinToString(", ") { "${PtpNames.hex(it)}:${PtpNames.prop(it)}" })
                        AppLog.i("対応イベント一覧: " + info.events.joinToString(", ") { PtpNames.hex(it) })
                    }
                    if (d.caps.exposure) d.readExposure() else ExposureInfo()
                }
                val id = _ui.value.camera
                _ui.update { it.copy(probeStatus = "接続完了: ${id?.title ?: "カメラ"}", stage = ConnStage.READY, exposure = exp) }
                notify("${id?.title ?: "カメラ"} に接続しました")
                startKeepAlive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("カメラ接続に失敗", e)
                _ui.update { it.copy(probeStatus = "失敗: ${e.message ?: e.javaClass.simpleName}", stage = ConnStage.FAILED, camera = null) }
                notify("接続に失敗しました")
            } finally {
                _ui.update { it.copy(probeBusy = false) }
            }
        }
    }

    /** 診断: カメラの全プロパティ・ストレージを読み取る（読み取りのみ。カメラの設定は変更しない）。 */
    fun readDiagnostics() {
        if (_ui.value.readoutsBusy) return
        _ui.update { it.copy(readoutsBusy = true) }
        viewModelScope.launch {
            try {
                val out = withSession { d ->
                    val out = ArrayList<String>()
                    val c = d.ptp
                    val info = d.deviceInfo
                    if (c == null || info == null) {
                        val e = d.readExposure()
                        out.add("${d.brand.label}（${d.identity?.protocol}）はPTPのプロパティ一覧を持たないため、主な値のみ表示します")
                        out.add("露出モード: ${e.modeLabel ?: "—"}")
                        out.add("ISO: ${ExpFormat.isoLabel(e.iso)}（選択肢 ${e.isoOptions.size}件）")
                        out.add("絞り: ${ExpFormat.apertureLabel(e.apertureX100)}（選択肢 ${e.apertureOptions.size}件）")
                        out.add("シャッター: ${e.shutterLabel ?: "—"}（選択肢 ${e.shutterOptions.size}件）")
                        out.add("露出補正: ${ExpFormat.biasLabel(e.biasMilli)} EV（選択肢 ${e.biasOptions.size}件）")
                        out.add("フォーカス: ${e.focus?.label ?: "—"} / 電池: ${e.battery?.let { "$it%" } ?: "—"}")
                        return@withSession out
                    }
                    if (info.declares(0x1015)) {
                        for (code in info.properties) {
                            try {
                                val r = c.getDevicePropValue(code)
                                val line = if (r.code == PtpIpClient.RC_OK)
                                    "${PtpNames.hex(code)} ${PtpNames.prop(code)}: ${PtpNames.decodeProp(code, r.data)}  [生:${PtpNames.rawHex(r.data)}]"
                                else
                                    "${PtpNames.hex(code)} ${PtpNames.prop(code)}: 取得失敗 ${PtpNames.hex(r.code)} ${PtpNames.responseName(r.code)}"
                                out.add(line); AppLog.i("PROP $line")
                            } catch (e: Exception) {
                                AppLog.e("プロパティ ${PtpNames.hex(code)} の読み取りで例外", e)
                                out.add("${PtpNames.hex(code)}: 例外 ${e.message}")
                                break // 通信状態が不明になるので以降は中止
                            }
                        }
                    } else {
                        out.add("GetDevicePropValue は申告されていないため、状態取得は未対応")
                    }
                    try {
                        if (info.declares(0x1004)) {
                            val ids = c.getStorageIds()
                            out.add("ストレージ数: ${ids.size} (${ids.joinToString { "0x" + Integer.toHexString(it) }})")
                        }
                        if (info.declares(0x1007)) out.add("カメラ内オブジェクト数: ${c.countObjects()}")
                    } catch (e: Exception) {
                        AppLog.e("ストレージ/オブジェクト数の取得に失敗", e)
                        out.add("ストレージ情報: 失敗 ${e.message}")
                    }
                    out
                }
                _ui.update { it.copy(readouts = out) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("診断の読み取りに失敗", e)
                _ui.update { it.copy(readouts = listOf("失敗: ${e.message}")) }
            } finally {
                _ui.update { it.copy(readoutsBusy = false) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 撮影
    // ------------------------------------------------------------------

    /** 撮影指示。DeviceBusy(0x2019)の間は短く待って再試行する（連写が1枚で止まる主因だった）。 */
    private fun captureOnce(d: CameraDriver, maxBusyWaitMs: Long = 5000): PtpResult {
        val deadline = System.nanoTime() + maxBusyWaitMs * 1_000_000
        var r = d.capture()
        var tries = 0
        while (Rc.isBusy(r.code) && System.nanoTime() < deadline) {
            Thread.sleep(if (tries < 10) 15L else 40L)
            tries++
            r = d.capture()
        }
        if (tries > 0) AppLog.v { "撮影 busy再試行 $tries 回 → ${PtpNames.hex(r.code)}" }
        return r
    }

    /** ライブビュー中の通常撮影が拒否されたら true。以降は最初から「LV一時停止→撮影→LV再開」にする。 */
    @Volatile private var captureNeedsLvStop = false

    /**
     * ライブビュー中の撮影。まず通常の撮影指示を試し、拒否/DeviceBusyのままなら
     * ライブビューを一旦終了 → 撮影 → ライブビュー再開 に切り替える（LV中の撮影を受け付けない機種がある）。
     * セッションのロックはこのブロック全体で保持されるため、LVのフレーム取得は途中に割り込まない。
     */
    private fun captureMaybeInLiveView(d: CameraDriver): PtpResult {
        if (!_ui.value.liveViewRunning) return captureOnce(d)
        if (!captureNeedsLvStop) {
            val r = captureOnce(d, 1200)
            if (r.code == Rc.OK) return r
            AppLog.w("ライブビュー中の撮影が拒否: ${PtpNames.describe(r)}。LVを一時停止して撮影に切替")
            captureNeedsLvStop = true
        }
        val end = d.endLiveView()
        AppLog.i("撮影のためLV一時停止: ${PtpNames.describe(end)}")
        Thread.sleep(250)
        val r = captureOnce(d, 5000)
        AppLog.i("LV停止中の撮影: ${PtpNames.describe(r)}")
        Thread.sleep(200)
        var st = d.startLiveView()
        if (Rc.isBusy(st.code)) { Thread.sleep(300); st = d.startLiveView() }
        AppLog.i("LV再開: ${PtpNames.describe(st)}")
        return r
    }

    private fun captureUnavailable(): String? {
        if (wifi.network == null) return "撮影不可: Wi-Fi未接続"
        val c = caps ?: return "撮影不可: カメラ未接続"
        if (!c.capture) return "撮影不可: このカメラは撮影コマンドを申告していません"
        return null
    }

    /** テスト撮影（画像数の増加で確認）。UI側で確認ダイアログを通った場合のみ呼ばれる。 */
    fun testCapture() {
        captureUnavailable()?.let { msg -> _ui.update { it.copy(captureStatus = msg) }; return }
        if (_ui.value.captureBusy) return
        _ui.update { it.copy(captureBusy = true, captureStatus = "実行中…") }
        viewModelScope.launch {
            try {
                val status = withSession { d ->
                    val c = d.ptp
                    val canCount = c != null && d.deviceInfo?.declares(0x1007) == true
                    val before = if (canCount) c!!.countObjects() else -1
                    AppLog.i("撮影前オブジェクト数=$before")
                    val r = captureOnce(d)
                    AppLog.i("撮影 応答 ${PtpNames.describe(r)}")
                    var after = -1
                    if (r.code == Rc.OK && before >= 0) {
                        Thread.sleep(2500)
                        after = c!!.countObjects()
                        AppLog.i("撮影後オブジェクト数=$after")
                    }
                    when {
                        r.code != Rc.OK -> "失敗: 応答 ${PtpNames.describe(r)}"
                        after > before && before >= 0 -> "成功: 画像が増えた ($before → $after)"
                        before < 0 -> "コマンドは受理された（画像数は未確認）"
                        else -> "コマンドは受理されたが、2.5秒後も画像数が増えていない ($before → $after)"
                    }
                }
                _ui.update { it.copy(captureStatus = status) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("テスト撮影に失敗", e)
                _ui.update { it.copy(captureStatus = "失敗: ${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                _ui.update { it.copy(captureBusy = false) }
            }
        }
    }

    /**
     * シャッターボタン。ライブビューは止めずに撮影する（フレーム取得とはセッションをフレーム毎に共有）。
     * 押して350ms未満で離す = 1枚、350ms以上押し続ける = 離すまで連写（最大999枚）。
     */
    fun shutterPress() {
        // セルフタイマーのカウントダウン中にもう一度押したら中止
        if (_countdown.value > 0) { selfTimerJob?.cancel(); return }
        if (_ui.value.captureBusy) return
        captureUnavailable()?.let { msg -> _ui.update { it.copy(captureStatus = msg) }; notify(msg); return }
        shutterPressed = true
        _ui.update { it.copy(captureBusy = true) }
        shutterJob = viewModelScope.launch {
            try {
                var waited = 0
                while (shutterPressed && waited < 350) { delay(10); waited += 10 }
                val hold = shutterPressed
                if (!hold) {
                    val st = _selfTimer.value
                    if (st > 0) {
                        // セルフタイマー: 1秒ごとにカウントダウンしてから撮影（もう一度押すと中止）
                        val ok = runCountdown(st)
                        if (!ok) { _ui.update { it.copy(captureStatus = "セルフタイマーを中止しました") }; return@launch }
                    }
                    if (_burstCfg.value.mode == BurstMode.AI_FOLLOW) predictiveStep()
                    val r = withSession { d -> captureMaybeInLiveView(d) }
                    val msg = if (r.code == Rc.OK) "撮影OK（1枚）" else "シャッター失敗: ${PtpNames.describe(r)}"
                    if (r.code == Rc.OK) _shotTick.update { it + 1 }
                    _ui.update { it.copy(captureStatus = msg) }
                    if (r.code != Rc.OK) notify(msg)
                } else {
                    burstLoop({ shutterPressed }, false)   // 押している間だけ連写。離したら停止
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("シャッター失敗", e)
                _ui.update { it.copy(captureStatus = "シャッター失敗: ${e.message}") }
                notify("シャッター失敗: ${e.message}")
            } finally {
                shutterPressed = false
                _ui.update { it.copy(captureBusy = false) }
            }
        }
    }

    fun shutterRelease() { shutterPressed = false }

    // ------------------------------------------------------------------
    // SDカード一覧 / RAW取り込み
    // ------------------------------------------------------------------
    private fun withCamera(block: (PtpIpClient) -> Unit) {
        viewModelScope.launch {
            try {
                withSession { d -> block(d.requirePtp()) }
                startThumbLoading()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("カメラ操作失敗", e)
                _ui.update { if (it.mediaLoading) it.copy(mediaStatus = "取得失敗: ${e.message}", mediaLoading = false) else it }
            } finally { _ui.update { it.copy(mediaLoading = false) } }
        }
    }

    fun refreshMedia() {
        if (_ui.value.mediaLoading) return
        _ui.update { it.copy(mediaLoading = true, mediaStatus = "SDカード情報を取得中…", mediaItems = emptyList(), selectedHandles = emptySet()) }
        allMediaHandles = emptyList(); mediaPage = 0
        withCamera { c ->
            val ids = c.getStorageIds()
            val storage = ids.firstOrNull()
            if (storage == null) { _ui.update { it.copy(storageIds = ids, mediaStatus = "カメラ内ストレージがありません") }; return@withCamera }
            allMediaHandles = c.getObjectHandles(storage).asReversed()   // 新しい順（後ろから）
            _ui.update { it.copy(storageIds = ids, selectedStorageId = storage, mediaStatus = "画像 ${allMediaHandles.size} 件。新しい順に読み込み中…", moreMediaAvailable = allMediaHandles.isNotEmpty()) }
            loadMediaPage(c)
        }
    }

    fun selectStorage(storageId: Int) {
        if (_ui.value.mediaLoading) return
        allMediaHandles = emptyList(); mediaPage = 0; thumbJob?.cancel(); _thumbs.value = emptyMap()
        _ui.update { it.copy(mediaLoading = true, selectedStorageId = storageId, mediaItems = emptyList(), selectedHandles = emptySet(), mediaStatus = "ストレージ内の画像一覧を取得中…") }
        withCamera { c ->
            allMediaHandles = c.getObjectHandles(storageId).asReversed()   // 新しい順（後ろから）
            _ui.update { it.copy(mediaStatus = "画像 ${allMediaHandles.size} 件。新しい順に30件ずつ読み込みます", moreMediaAvailable = allMediaHandles.isNotEmpty()) }
            loadMediaPage(c)
        }
    }

    private fun loadMediaPage(c: PtpIpClient) {
        val start = mediaPage * 30
        val end = minOf(start + 30, allMediaHandles.size)
        val items = ArrayList<MediaUiItem>()
        for (i in start until end) {
            val info = c.getObjectInfo(allMediaHandles[i])
            if (info.format == 0x3001) continue   // フォルダ（Association）は一覧に出さない
            items.add(MediaUiItem(allMediaHandles[i], info.fileName, info.size, info.format))
        }
        mediaPage++
        _ui.update { it.copy(mediaItems = it.mediaItems + items, mediaStatus = "新しい順 ${end} / ${allMediaHandles.size} 件を確認（画像 ${it.mediaItems.size + items.size} 件）", moreMediaAvailable = end < allMediaHandles.size) }
    }

    fun loadMoreMedia() {
        if (_ui.value.mediaLoading || !_ui.value.moreMediaAvailable) return
        _ui.update { it.copy(mediaLoading = true) }
        withCamera { c -> loadMediaPage(c) }
    }

    private fun decodeThumb(data: ByteArray, maxSide: Int): Bitmap? = try {
        val jpeg = Jpeg.extract(data) ?: data
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)?.let { b ->
            val m = maxOf(b.width, b.height)
            if (m > maxSide) Bitmap.createScaledBitmap(b, b.width * maxSide / m, b.height * maxSide / m, true).also { if (it !== b) b.recycle() } else b
        }
    } catch (_: Exception) { null }

    /** 一覧のサムネイルを順次取得（GetThumb）。1枚ごとにセッションを解放するので他操作や転送に割り込める。 */
    private fun startThumbLoading() {
        thumbJob?.cancel()
        thumbJob = viewModelScope.launch {
            for (item in _ui.value.mediaItems) {
                if (_thumbs.value.containsKey(item.handle)) continue
                ensureActive()
                try {
                    val data = withSession { d -> d.requirePtp().getThumb(item.handle) } ?: continue
                    val bmp = withContext(Dispatchers.Default) { decodeThumb(data, 240) } ?: continue
                    _thumbs.update { it + (item.handle to bmp) }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    AppLog.w("サムネイル取得失敗 ${item.name}: ${e.message}")
                    break
                }
            }
        }
    }

    /** 拡大プレビュー。Nikon GetLargeThumb(0x90C4)が申告されていればそれ、無ければ通常サムネイル。 */
    fun showPreview(item: MediaUiItem) {
        previewJob?.cancel()
        _preview.value = PreviewState(item.name, _thumbs.value[item.handle], true)
        previewJob = viewModelScope.launch {
            try {
                val large = _ui.value.brand == CameraBrand.NIKON && _ui.value.deviceInfo?.declares(0x90C4) == true
                val data = withSession { d -> val c = d.requirePtp(); (if (large) c.getLargeThumb(item.handle) else null) ?: c.getThumb(item.handle) }
                val bmp = data?.let { withContext(Dispatchers.Default) { decodeThumb(it, 1600) } }
                _preview.update { p -> if (p?.name == item.name) p.copy(bitmap = bmp ?: p.bitmap, loading = false) else p }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.w("プレビュー取得失敗 ${item.name}: ${e.message}")
                _preview.update { p -> p?.copy(loading = false) }
            }
        }
    }

    fun closePreview() { previewJob?.cancel(); _preview.value = null }

    fun toggleMediaSelection(handle: Int, selected: Boolean) = _ui.update {
        it.copy(selectedHandles = if (selected) it.selectedHandles + handle else it.selectedHandles - handle)
    }

    fun selectVisibleMedia(select: Boolean) = _ui.update {
        val rawHandles = it.mediaItems.filter { m -> isRaw(m) }.map { m -> m.handle }
        it.copy(selectedHandles = if (select) it.selectedHandles + rawHandles else it.selectedHandles - rawHandles.toSet())
    }

    fun importSelectedRaw() {
        val selected = _ui.value.selectedHandles.toList()
        if (selected.isEmpty() || _ui.value.transferBusy) return
        thumbJob?.cancel()
        transferJob = viewModelScope.launch {
            _ui.update { it.copy(transferBusy = true, transferPercent = 0, transferProgress = "転送準備中…") }
            try {
                if (liveViewJob?.isActive == true) liveViewJob?.cancelAndJoin()
                var completed = 0
                var lastUi = 0L
                for (handle in selected) {
                    ensureActive()
                    val info = withSession { d -> d.requirePtp().getObjectInfo(handle) }
                    val safeName = info.fileName.map { ch -> if (ch == '/' || ch == '\\' || ch.code < 32) '_' else ch }.joinToString("").ifBlank { "IMG_$handle.NEF" }
                    if (!com.example.d750bridge.camera.RawFormats.isRaw(safeName)) throw IllegalArgumentException("RAW以外は取り込み対象外です: $safeName")
                    val target = PlatformIo.create(getApplication(), SaveFolder.RAW_IMPORT, safeName, com.example.d750bridge.camera.RawFormats.mime(safeName))
                    var offset = 0L
                    try {
                        target.openStream().use { out ->
                            while (offset < info.size) {
                                ensureActive()
                                val amount = minOf(256 * 1024L, info.size - offset).toInt()
                                val off = offset.toInt()
                                val chunk = withSession { d -> d.requirePtp().getPartialObject(handle, off, amount) }
                                if (chunk.isEmpty()) throw IllegalStateException("転送データが空です: $safeName offset=$offset")
                                val accepted = minOf(chunk.size, amount)
                                out.write(chunk, 0, accepted); offset += accepted
                                val now = System.nanoTime()
                                if (now - lastUi > 250_000_000L) {
                                    lastUi = now
                                    val overall = (((completed + offset.toDouble() / info.size.coerceAtLeast(1)) / selected.size) * 100).toInt()
                                    val o = offset
                                    _ui.update { it.copy(transferPercent = overall, transferProgress = "$safeName  ${(o * 100 / info.size.coerceAtLeast(1))}%  (${o}/${info.size} bytes)") }
                                }
                            }
                            out.flush()
                        }
                        if (offset != info.size) throw IllegalStateException("サイズ検証失敗: $offset / ${info.size}")
                        target.publish()
                    } catch (e: Exception) {
                        target.discard()
                        throw e
                    }
                    completed++
                }
                _ui.update { it.copy(transferPercent = 100, transferProgress = "完了: ${selected.size} ファイル。${SaveFolder.RAW_IMPORT.label} に保存しました", selectedHandles = emptySet()) }
                notify("${selected.size} ファイルを取り込みました")
            } catch (e: Exception) {
                AppLog.e("RAW転送失敗/中断", e)
                _ui.update { it.copy(transferProgress = "失敗/中断: ${e.message}") }
                if (e is CancellationException) throw e
            } finally { _ui.update { it.copy(transferBusy = false) } }
        }
    }

    fun cancelTransfer() { transferJob?.cancel(); _ui.update { it.copy(transferProgress = "キャンセル要求中…") } }

    // ------------------------------------------------------------------
    // ライブビュー
    // ------------------------------------------------------------------
    private val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 } // AI検出(MediaPipe)がARGB_8888を要求

    fun startLiveView() {
        if (_ui.value.liveViewRunning) return
        if (wifi.network == null) { _ui.update { it.copy(liveViewStatus = "Wi-Fi未接続") }; return }
        if (caps?.liveView == false) {
            _ui.update { it.copy(liveViewStatus = "このカメラはライブビューに未対応です") }
            notify("このカメラはライブビューに未対応です"); return
        }
        captureNeedsLvStop = false
        _ui.update { it.copy(liveViewRunning = true, liveViewStatus = "ライブビュー開始中…", liveFps = 0f) }
        liveViewJob = viewModelScope.launch {
            // 取得(ネットワーク)とデコード(CPU)を分離。デコードが遅れたら古いフレームは捨てる。
            val frames = Channel<ByteArray>(Channel.CONFLATED)
            val aiFrames = Channel<Pair<Bitmap, Long>>(Channel.CONFLATED)
            val decoder = launch(Dispatchers.Default) {
                for (jpeg in frames) {
                    val bmp = try { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decodeOpts) } catch (_: Exception) { null }
                    if (bmp != null) {
                        _liveFrame.value = bmp
                        frameSeq++
                        aiFrames.trySend(bmp to System.nanoTime())   // 表示とは別コルーチンで検出（最新フレームのみ）
                    }
                }
            }
            val aiJob = launch(Dispatchers.Default) { runAiLoop(aiFrames) }
            try {
                val start = withSession { d -> d.startLiveView() }
                if (start.code != Rc.OK) throw IllegalStateException("ライブビュー開始失敗: ${PtpNames.describe(start)}")
                _ui.update { it.copy(liveViewStatus = "ライブビュー動作中") }
                var emptyStreak = 0
                var frameCount = 0
                var windowStart = System.nanoTime()
                while (true) {
                    ensureActive()
                    // フレーム毎にロックを取り直す → 連写・設定変更・AFがフレーム間に割り込める
                    val f = withSession { d -> d.liveViewFrame() }
                    val jpeg = f.jpeg
                    if (f.code != Rc.OK || jpeg == null) {
                        // Busy / まだフレーム準備中。短く待って再試行
                        if (++emptyStreak > 400) throw IllegalStateException(
                            if (f.code != Rc.OK) "ライブビュー応答エラー ${PtpNames.hex(f.code)} ${PtpNames.responseName(f.code)}"
                            else "ライブビュー応答からJPEGを抽出できません"
                        )
                        delay(if (f.code != Rc.OK) 15L else 10L)
                        continue
                    }
                    emptyStreak = 0
                    frames.trySend(jpeg)
                    lastLiveJpeg = jpeg
                    frameCount++
                    val now = System.nanoTime()
                    if (now - windowStart >= 1_000_000_000L) {
                        val fps = (frameCount * 1e9 / (now - windowStart)).toFloat()
                        _ui.update { it.copy(liveViewStatus = "ライブビュー動作中 %.1f fps".format(fps), liveFps = fps) }
                        frameCount = 0; windowStart = now
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("ライブビュー停止/失敗", e)
                _ui.update { it.copy(liveViewStatus = "停止/失敗: ${e.message}") }
                notify("ライブビューが停止しました: ${e.message}")
            } finally {
                frames.close()
                aiFrames.close()
                decoder.cancel()
                aiJob.cancel()
                _afTarget.value = null
                _aiPlan.value = null
                tracker.reset()
                trackMgr.reset(); speedEst.reset(); _tracks.value = emptyList(); _speeds.value = emptyMap(); targetView = null
                shutterPressed = false; burstRun = false   // LV停止時に連写要求を確実に解除
                if (_recStartMs.value != null && _ui.value.brand == CameraBrand.NIKON) { _recStartMs.value = null; notify("ライブビュー停止で録画も終了しました") }
                withContext(NonCancellable) {
                    if (sessionRef != null) { try { withSession { d -> d.endLiveView() } } catch (_: Exception) {} }
                }
                _ui.update { it.copy(liveViewRunning = false, liveFps = 0f, liveViewStatus = if (it.liveViewStatus.startsWith("停止/失敗")) it.liveViewStatus else "停止中") }
            }
        }
    }

    fun stopLiveView() { liveViewJob?.cancel(); _ui.update { it.copy(liveViewStatus = "停止要求中…") } }

    // ------------------------------------------------------------------
    // AF
    // ------------------------------------------------------------------
    private fun afOp(label: String, op: (CameraDriver) -> PtpResult) {
        viewModelScope.launch {
            try {
                val r = withSession(op)
                _ui.update { it.copy(afStatus = "$label: ${PtpNames.describe(r)}") }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("$label 失敗", e)
                _ui.update { it.copy(afStatus = "$label 失敗: ${e.message}") }
            }
        }
    }
    /**
     * ライブビュー画像のタッチ位置にAFエリアを移動してAFを実行する。
     * 置きピンモード中は「ピンの設置」（その位置でAF→固定）になり、被写体を追従するAFは行わない。
     * 座標はライブビューJPEGのピクセル座標（幅×高さ）として送る。カメラでの座標系は実機で要確認（ログに送信値を出す）。
     */
    fun focusAt(nx: Float, ny: Float) {
        if (_calibPick.value > 0) { pickCalibPoint(nx, ny); return }   // 速度校正の基準点指定中はAFしない
        if (_aiCfg.value.pinMode) placePin(nx, ny) else runAf(nx, ny, "タッチ")
    }

    /** 現在のAI検出結果（枠＋先読み補正済みのAF点）でAFを実行する。 */
    fun aiFocusNow() {
        if (_aiCfg.value.pinMode) {
            _aiUi.update { it.copy(lastAction = "置きピンモード中は被写体へのAFを行いません（ピンを置き直すにはライブビューをタップ）") }
            return
        }
        val p = _aiPlan.value
        if (p == null) {
            _aiUi.update { it.copy(lastAction = "AI AF未実行: 検出対象がありません（AI支援がOFF、または被写体が映っていません）") }
            return
        }
        if (p.state != TrackState.TRACKING) {
            _aiUi.update { it.copy(lastAction = "AI AF未実行: 対象は${p.state.label}のためAFしません（遮蔽物へのピント移動を防止）") }
            return
        }
        runAf(p.nx, p.ny, "AI", p.region)
    }

    /**
     * AF点の候補を作る。夜間はコントラストの高い点を優先し、AFが決まらなければ次の候補で再試行する。
     *  - AI: 検出範囲(region)内の高コントラスト点（最大3点）→ 最後にAI本来の点
     *  - タッチ/置きピン: 指定点 → その周囲(±5%/±7%)の高コントラスト点（最大2点）
     * 戻り値の second は「夜間補正が有効か」。
     */
    private fun planCandidates(bmp: Bitmap, nx: Float, ny: Float, region: Region?, tag: String): Pair<List<Pair<Float, Float>>, Boolean> {
        val mode = _aiCfg.value.nightMode
        val primary = nx to ny
        if (mode == NightMode.OFF) return listOf(primary) to false
        val grid = LumaGrid.of(bmp)
        val night = AfConst.isNight(mode, grid.mean)
        if (!night) return listOf(primary) to false
        val out = ArrayList<Pair<Float, Float>>()
        if (tag == "AI" && region != null) {
            out.addAll(grid.contrastPoints(region.x0, region.y0, region.x1, region.y1, 3))
            out.add(primary)
        } else {
            out.add(primary)
            out.addAll(grid.contrastPoints(nx - 0.05f, ny - 0.07f, nx + 0.05f, ny + 0.07f, 2))
        }
        val uniq = ArrayList<Pair<Float, Float>>()
        for (c in out) if (uniq.none { hypot(it.first - c.first, it.second - c.second) < 0.02f }) uniq.add(c)
        return uniq.take(3) to true
    }

    /**
     * AFの共通処理。方式(tag)ごとに所要時間を実測する。
     *  cmdMs  = ChangeAfArea送信〜AfDrive応答まで
     *  totalMs= ChangeAfArea送信〜DeviceReady(0x90C8)がOKを返すまで（昼3秒 / 夜5秒まで待つ）
     * DeviceReadyがAF完了を意味するかはD750実機で未確認。数値は「方式どうしの相対比較」に使う。
     * 夜間は、AfDriveが拒否された/DeviceReadyで完了を確認できなかった場合に、次の候補点でAFをやり直す（最大3点）。
     * 戻り値: 最後の試行でAfDriveが受理されたか。
     */
    private suspend fun afSequence(cands: List<Pair<Float, Float>>, tag: String, night: Boolean, bmpW: Int, bmpH: Int): Boolean {
        var accepted = false
        val poll = caps?.afReadyPoll == true
        for ((i, cand) in cands.withIndex()) {
            val cx = cand.first.coerceIn(0f, 1f); val cy = cand.second.coerceIn(0f, 1f)
            val x = (cx * (bmpW - 1)).toInt()
            val y = (cy * (bmpH - 1)).toInt()
            _afTarget.value = AfTarget(cx, cy, null)
            val tStart = System.nanoTime()
            val run = withSession { d ->
                val r = d.afAt(cx, cy, bmpW, bmpH)
                AfRun(r.code == Rc.OK, "AF ${PtpNames.describe(r)}", (System.nanoTime() - tStart) / 1_000_000)
            }
            var ready = false
            if (run.ok) {
                if (!poll) ready = true
                else {
                    val waitNs = if (night) 5_000_000_000L else 3_000_000_000L
                    val pollStart = System.nanoTime()
                    while (System.nanoTime() - pollStart < waitNs) {
                        val r = withSession { d -> d.afReady() }   // フレーム取得などが割り込めるよう毎回ロックを解放
                        if (r.code == Rc.OK) { ready = true; break }
                        if (!Rc.isBusy(r.code)) break
                        delay(25)
                    }
                }
            }
            val totalMs = (System.nanoTime() - tStart) / 1_000_000
            val msg = "$tag AF${if (night) "[夜間 ${i + 1}/${cands.size}]" else ""} ($x,$y / ${bmpW}x$bmpH): ${run.msg}" +
                if (run.ok) "  所要 ${totalMs}ms（応答 ${run.cmdMs}ms / 完了確認 ${if (!poll) "非対応" else if (ready) "OK" else "未確認"}）" else ""
            AppLog.i(msg)
            if (run.ok && ready) recordAf(tag, run.cmdMs, totalMs)
            val shownOk = run.ok && (ready || !night)
            _afTarget.update { t -> if (t != null && t.nx == cx && t.ny == cy) t.copy(ok = shownOk) else t }
            _ui.update { it.copy(afStatus = msg) }
            _aiUi.update { it.copy(lastAction = msg) }
            accepted = run.ok
            if (!night || (run.ok && ready)) break
            // 夜間リトライ: AFを中断して次の候補へ
            try { withSession { d -> d.cancelAf() } } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
        return accepted
    }

    private fun runAf(nx: Float, ny: Float, tag: String, region: Region? = null) {
        val bmp = _liveFrame.value ?: return
        if (!afInFlight.compareAndSet(false, true)) return   // AFの多重実行を避ける
        viewModelScope.launch {
            var accepted = false
            try {
                val (cands, night) = withContext(Dispatchers.Default) { planCandidates(bmp, nx, ny, region, tag) }
                accepted = afSequence(cands, tag, night, bmp.width, bmp.height)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("$tag AF失敗", e)
                _afTarget.update { t -> t?.copy(ok = false) }
                _ui.update { it.copy(afStatus = "$tag AF失敗: ${e.message}") }
            } finally {
                afInFlight.set(false)
            }
            // AFが受理されたら、設定ONのときだけMfDriveによるピント補正を続けて行う（置きピン中は行わない）
            if (accepted && _focusCfg.value.autoCorrectOn && !_aiCfg.value.pinMode) startFocusCorrection()
        }
    }

    // ---- 置きピン ----
    /** 置きピンを置く: その位置でAF → 成功したらカメラをMFへ切り替えてピントを固定（設定でOFF可）。 */
    private fun placePin(nx: Float, ny: Float) {
        val bmp = _liveFrame.value ?: return
        if (!afInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                restoreFocusMode()   // 以前のピン固定(MF)があれば解除してからAF
                val (cands, night) = withContext(Dispatchers.Default) { planCandidates(bmp, nx, ny, null, "置きピン") }
                val ok = afSequence(cands, "置きピン", night, bmp.width, bmp.height)
                if (!ok) {
                    _pin.value = null
                    _aiUi.update { it.copy(lastAction = "置きピン失敗: AFが受理されませんでした（ピンは設定されていません）。上のAF状態の応答コードを確認してください") }
                    return@launch
                }
                val lock = if (_aiCfg.value.pinLockMf) lockFocusMf()
                else "MF固定なし: AFの追従は止めていますが、撮影時にカメラ側のAFが働く可能性があります"
                _pin.value = PinState(nx, ny, lock)
                _aiUi.update { it.copy(lastAction = "置きピン設置 (${(nx * 100).toInt()}%, ${(ny * 100).toInt()}%): $lock") }
                AppLog.i("置きピン設置: $lock")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("置きピン失敗", e)
                _aiUi.update { it.copy(lastAction = "置きピン失敗: ${e.message}") }
            } finally {
                afInFlight.set(false)
            }
        }
    }

    /** ピント固定のためMFへ切り替える。書き込み可否・反映はドライバーがカメラの応答で確認する。 */
    private suspend fun lockFocusMf(): String {
        return try {
            withSession { d ->
                val before = d.focusMode()
                if (before == FocusKind.MF) return@withSession "カメラはすでにMFです（ピント位置はこのまま固定）"
                val r = d.setFocusMode(FocusKind.MF)
                if (r.ok) {
                    if (savedFocusMode == null && before != null) savedFocusMode = before
                    "MFに切替えてピント固定OK（解除で元のモードへ戻します）"
                } else "MF固定できません: ${r.msg}。AFの追従は止めています"
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { "MF固定に失敗: ${e.message}。AFの追従は止めています" }
    }

    /** 置きピンでMFにした場合、元のフォーカスモードへ戻す。 */
    private suspend fun restoreFocusMode() {
        val prev = savedFocusMode ?: return
        try {
            val r = withSession { d -> d.setFocusMode(prev) }
            if (r.ok) { savedFocusMode = null; AppLog.i("フォーカスモードを元に戻しました") }
            else AppLog.w("フォーカスモードを戻せません: ${r.msg}")
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { AppLog.w("フォーカスモード復元に失敗: ${e.message}") }
    }

    /** ピンを外す（置きピンモードはそのまま。AFは再びピンを置くまで動かない）。MFにしていたら元へ戻す。 */
    fun releasePin() {
        viewModelScope.launch {
            _pin.value = null
            restoreFocusMode()
            _aiUi.update { it.copy(lastAction = "置きピンを外しました") }
        }
    }

    private fun recordAf(tag: String, cmdMs: Long, totalMs: Long) {
        val list = afSamples.getOrPut(tag) { ArrayList() }
        list.add(cmdMs to totalMs)
        if (list.size > 50) list.removeAt(0)
        val stats = afSamples.mapValues { (_, v) ->
            AfStat(v.size, v.map { it.second }.average().toLong(), v.last().second, v.map { it.first }.average().toLong())
        }
        _aiUi.update { it.copy(stats = stats) }
    }

    fun clearAfStats() { afSamples.clear(); _aiUi.update { it.copy(stats = emptyMap()) } }

    // ---- AI AF設定 ----
    fun setAiDetect(on: Boolean) {
        _aiCfg.update { it.copy(detectEnabled = on, autoAf = if (on) it.autoAf else false) }
        if (!on) { _aiPlan.value = null; tracker.reset() }
    }
    fun setAiAuto(on: Boolean) = _aiCfg.update { it.copy(autoAf = on && it.detectEnabled && !it.pinMode && it.subject != Subject.LANDSCAPE) }
    fun setSubject(s: Subject) {
        _aiCfg.update { it.copy(subject = s, pointYRatio = s.defaultPointY, autoAf = if (s == Subject.LANDSCAPE) false else it.autoAf) }
        _aiPlan.value = null; tracker.reset(); lastAiPoint = null; lastAiArea = 0f
        trackMgr.reset(); speedEst.reset(); _tracks.value = emptyList(); _speeds.value = emptyMap(); targetView = null; lastRel = null
    }
    fun setNightMode(m: NightMode) = _aiCfg.update { it.copy(nightMode = m) }
    fun setPinLockMf(on: Boolean) = _aiCfg.update { it.copy(pinLockMf = on) }
    /** 置きピンモード。ONの間は被写体が来てもAFを追従させない（自動AFも停止）。OFFにするとピンを外して元のAFモードへ戻す。 */
    fun setPinMode(on: Boolean) {
        _aiCfg.update { it.copy(pinMode = on, autoAf = if (on) false else it.autoAf) }
        if (on) _aiUi.update { it.copy(lastAction = "置きピンON: ライブビューをタップした位置でAFし、ピントを置きます。被写体を追従するAFは停止します") }
        else releasePin()
    }
    fun setAiTruckCar(on: Boolean) = _aiCfg.update { it.copy(includeTruckCar = on) }
    fun setAiPointY(v: Float) = _aiCfg.update { it.copy(pointYRatio = v.coerceIn(0.05f, 0.95f)) }
    fun setAiLead(ms: Int) = _aiCfg.update { it.copy(leadMs = ms.coerceIn(0, 800)) }

    private fun ensureDetector(): SubjectDetector {
        detector?.let { return it }
        val d = SubjectDetector(getApplication<Application>())
        detector = d
        val obj = d.objError?.let { "利用不可: $it" } ?: "OK（EfficientDet-Lite0 / COCO）"
        val face = d.faceError?.let { "利用不可: $it（顔が取れない代わりに人物枠の頭部位置を使います）" } ?: "OK（BlazeFace）"
        _aiUi.update { it.copy(modelStatus = "物体検出: $obj / 顔検出: $face") }
        return d
    }

    /**
     * 1フレーム分の検出→追跡→AF点計画。戻り値は (計画, 対象クラスの検出件数)。
     * 遮蔽物(OCCLUDER_LABELS)も検出して追跡に渡し、対象が隠れたときの状態遷移(遮蔽/予測/再認識)に使う。
     * 対象が TRACKING 以外のときは、最後に確認したAF点の枠内相対位置を予測枠に当てはめる（急に動かさない）。
     */
    private fun computePlan(det: SubjectDetector, bmp: Bitmap, grid: LumaGrid, night: Boolean, t: Long, cfg: AiConfig): Pair<AiPlan?, Int> {
        if (det.objError != null) return null to 0
        val src = if (night) det.brighten(bmp, (AfConst.NIGHT_TARGET_LUMA / maxOf(grid.mean, 8f)).coerceIn(1.5f, 5f)) else bmp
        try {
            val minScore = if (night) cfg.minScore * 0.7f else cfg.minScore
            val faceMin = minScore * 0.75f
            val targetLabels: Set<String> = when (cfg.subject) {
                Subject.PERSON -> setOf("person")
                Subject.TRAIN -> setOf("train")
                else -> if (cfg.includeTruckCar) setOf("bus", "truck", "car") else setOf("bus")
            }
            val all = det.detectObjects(src, targetLabels + OCCLUDER_LABELS, minScore)
            var tdets = all.filter { it.label in targetLabels }
            if (cfg.subject == Subject.PERSON && tdets.isEmpty()) tdets = det.detectFaces(src, faceMin)   // 人物枠が取れない近接・暗所は顔だけで探す
            val hists = tdets.map { Appearance.hist(src, it) }
            val views = trackMgr.update(tdets, hists, all, t, _trackCfg.value)
            _tracks.value = views
            val tv = views.firstOrNull { it.isTarget }
            targetView = tv
            if (tv == null) return null to tdets.size
            val box = tv.box
            val bw = (box.x1 - box.x0).coerceAtLeast(0.001f)
            val bh = (box.y1 - box.y0).coerceAtLeast(0.001f)
            val tracking = tv.state == TrackState.TRACKING
            val a: com.example.d750bridge.ai.Anchor
            if (tracking) {
                a = if (cfg.subject == Subject.PERSON) det.personAnchor(src, box, cfg, faceMin) else vehicleAnchor(box, cfg.subject, cfg, tv.vx)
                lastRel = ((a.x - box.x0) / bw) to ((a.y - box.y0) / bh)
            } else {
                val rel = lastRel ?: (0.5f to cfg.pointYRatio)
                a = com.example.d750bridge.ai.Anchor(
                    box.x0 + rel.first * bw, box.y0 + rel.second * bh, "${tv.state.label}（最後に確認したAF位置から予測）",
                    Region(box.x0, box.y0, box.x1, box.y1)
                )
            }
            val lead = cfg.leadMs / 1000f
            val px = (a.x + (if (tracking && abs(tv.vx) > 0.02f) tv.vx * lead else 0f)).coerceIn(0.02f, 0.98f)
            val py = (a.y + (if (tracking && abs(tv.vy) > 0.02f) tv.vy * lead else 0f)).coerceIn(0.02f, 0.98f)
            return AiPlan(px, py, box, tv.vx, tv.vy, tracking, a.note, a.region, tv.state, tv.id) to tdets.size
        } finally {
            if (src !== bmp) src.recycle()
        }
    }

    /** ライブビューのフレームを順に検出する。表示を遅らせないよう、最新フレームだけを処理する。 */
    private suspend fun runAiLoop(frames: Channel<Pair<Bitmap, Long>>) {
        for ((bmp, t) in frames) {
            liveSharpness(bmp)
            val cfg = _aiCfg.value
            if (!cfg.detectEnabled) {
                if (_aiPlan.value != null) _aiPlan.value = null
                if (_tracks.value.isNotEmpty()) { trackMgr.reset(); speedEst.reset(); _tracks.value = emptyList(); _speeds.value = emptyMap(); targetView = null }
                continue
            }
            val t0 = System.nanoTime()
            val grid = LumaGrid.of(bmp)
            val night = AfConst.isNight(cfg.nightMode, grid.mean)
            var count = 0
            val plan: AiPlan? = try {
                if (cfg.subject == Subject.LANDSCAPE) {
                    count = 1
                    landscapePlan(grid)
                } else {
                    val det = ensureDetector()
                    val r = computePlan(det, bmp, grid, night, t, cfg)
                    count = r.second
                    r.first
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                AppLog.w("AI検出に失敗: ${e.javaClass.simpleName}: ${e.message}"); null
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            _aiPlan.value = plan
            if (plan == null) { lastAiPoint = null; lastAiArea = 0f }
            if (cfg.subject != Subject.LANDSCAPE) postTrack(bmp, t)
            _aiUi.update { it.copy(detectMs = ms, detCount = count, night = night, luma = grid.mean.toInt(), planNote = plan?.note ?: "") }
            if (plan != null && cfg.autoAf && !cfg.pinMode && cfg.subject != Subject.LANDSCAPE) maybeAutoAf(plan, cfg)
        }
    }

    /**
     * 自動AF。多重実行・撮影中・間隔不足は送らない。
     * 再AFのきっかけ: ①AF点が横/縦に動いた ②枠の大きさが変わった（被写体が近づく/遠ざかる＝距離が変わった）。
     */
    private fun maybeAutoAf(plan: AiPlan, cfg: AiConfig) {
        if (plan.state != TrackState.TRACKING) return   // 遮蔽中・予測中・再認識中は、遮蔽物にピントが移らないようAFを送らない
        if (afInFlight.get() || _ui.value.captureBusy || _focusUi.value.running) return
        val now = System.nanoTime()
        if (now - lastAiAfNanos < cfg.minIntervalMs * 1_000_000L) return
        val lp = lastAiPoint
        val moved = lp == null || hypot(plan.nx - lp.first, plan.ny - lp.second) >= cfg.moveThreshold
        val areaChanged = lastAiArea > 0f && abs(plan.det.area - lastAiArea) / lastAiArea > cfg.areaThreshold
        if (!moved && !areaChanged) return
        lastAiAfNanos = now
        lastAiPoint = plan.nx to plan.ny
        lastAiArea = plan.det.area
        viewModelScope.launch { runAf(plan.nx, plan.ny, "AI", plan.region) }
    }

    // ------------------------------------------------------------------
    // ピント解析・補正（MfDriveによるレンズ直接駆動 + ライブビュー画像のシャープネス）
    //
    // できること/できないこと（実機未検証）:
    //  - 前ピン/後ピンは「1枚の画像のシャープネス」では判定できない。ここでは、フォーカスを前後へ少し動かして
    //    シャープネスの変化を見る（山登り）ことで、ピークの方向を求める。方向(Near/Far)の対応は実機で確認が必要。
    //  - MfDriveの方向フラグ(1/2)・ステップ量の単位・ライブビュー/AFモードごとの受理可否は未確認。応答コードをそのまま表示する。
    // ------------------------------------------------------------------
    private val focusStore by lazy { FocusProfileStore(getApplication<Application>()) }
    private val _focusCfg = MutableStateFlow(FocusCfg())
    val focusCfg: StateFlow<FocusCfg> = _focusCfg.asStateFlow()
    private val _focusUi = MutableStateFlow(FocusUi())
    val focusUi: StateFlow<FocusUi> = _focusUi.asStateFlow()
    private var focusJob: Job? = null
    @Volatile private var frameSeq = 0L
    private var focusNet = 0                 // MfDriveで動かした累計（Far=+, Near=−）。初期位置へ戻す用
    private var lastSharpNanos = 0L
    private var sharpPeak = 0f

    private fun focusStatus(msg: String) {
        _focusUi.update { it.copy(status = msg) }
        AppLog.i("ピント: $msg")
    }

    /** ピント解析に使う領域: 置きピン > AI目標 > 最後のAF枠 > 画面中央。 */
    private fun evalRegion(): Region {
        val pin = _pin.value
        val plan = _aiPlan.value
        val tgt = _afTarget.value
        val cx: Float
        val cy: Float
        when {
            pin != null -> { cx = pin.nx; cy = pin.ny }
            plan != null -> { cx = plan.nx; cy = plan.ny }
            tgt != null -> { cx = tgt.nx; cy = tgt.ny }
            else -> { cx = 0.5f; cy = 0.5f }
        }
        return Region(cx - 0.09f, cy - 0.09f, cx + 0.09f, cy + 0.09f)
    }

    /** ライブビュー中のシャープネスを約0.4秒ごとに更新する（AIループのスレッドで実行）。 */
    private fun liveSharpness(bmp: Bitmap) {
        if (!_focusCfg.value.analysisOn || _focusUi.value.running) return
        val now = System.nanoTime()
        if (now - lastSharpNanos < 400_000_000L) return
        lastSharpNanos = now
        val s = FocusAnalyzer.measure(bmp, evalRegion()) ?: return
        if (s.score > sharpPeak) sharpPeak = s.score else sharpPeak *= 0.995f   // ピークはゆっくり減衰（被写体が変わっても追従）
        val hint = if (s.anisotropy > 3.5f && s.score < sharpPeak * 0.6f) "モーションブレの疑い（片方向の勾配だけ弱い）" else ""
        _focusUi.update { it.copy(score = s.score, peak = sharpPeak, blurHint = hint) }
    }

    private suspend fun waitFrames(n: Int, timeoutMs: Long): Boolean {
        val target = frameSeq + n
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (frameSeq < target) {
            if (System.nanoTime() > end) return false
            delay(15)
        }
        return true
    }

    /** 駆動直後の揺らぎを避けるため、新しいフレームを待ってから n 枚のシャープネスを平均する。 */
    private suspend fun measureStable(region: Region, n: Int = 3): Meas? {
        val list = ArrayList<Sharp>()
        for (i in 0 until n) {
            if (!waitFrames(1, 1500)) return null
            val bmp = _liveFrame.value ?: return null
            val s = withContext(Dispatchers.Default) { FocusAnalyzer.measure(bmp, region) } ?: return null
            list.add(s)
        }
        val scores = list.map { it.score }
        val mean = scores.average().toFloat()
        val sd = sqrt(scores.map { (it - mean) * (it - mean) }.average()).toFloat()
        val cv = if (mean > 0f) sd / mean else 1f
        return Meas(Sharp(mean, list.map { it.ex }.average().toFloat(), list.map { it.ey }.average().toFloat(), list.map { it.luma }.average().toFloat()), cv)
    }

    private suspend fun readFocalLength(): Double? {
        if (caps?.focalLength != true) return null
        return try {
            withSession<Double?> { d -> d.focalLengthMm() }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { null }
    }

    /** 焦点距離を読み、校正バケットと微調整量を更新する。 */
    private suspend fun applyFocalLength() {
        val mm = readFocalLength()
        val bucket = focusStore.bucketOf(mm)
        _focusUi.update {
            it.copy(
                focalMm = mm, bucket = bucket, fine = focusStore.fine(bucket), calibrated = focusStore.calibrated(bucket),
                nearFlag = focusStore.nearFlag, dirConfirmed = focusStore.dirConfirmed, lastResult = focusStore.lastResult(bucket)
            )
        }
    }

    fun refreshFocalLength() {
        viewModelScope.launch {
            try { applyFocalLength(); focusStatus("焦点距離を読み取りました") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { focusStatus("焦点距離の読み取り失敗: ${e.message}") }
        }
    }

    /** MF駆動 1回分。units>0 = Far, <0 = Near。上限・Busy再試行・完了待ち・安定待ちを含む。失敗時はメッセージを返す。 */
    private suspend fun driveFocus(units: Int): String? {
        if (units == 0) return null
        val far = units > 0
        val amount = abs(units)
        if (amount > MAX_SINGLE_STEP) return "移動量 ${amount} が1回の上限 ${MAX_SINGLE_STEP} を超えるため実行しません"
        val nearFlag = focusStore.nearFlag
        var res: PtpResult = Rc.fail("未実行")
        for (attempt in 0 until 12) {
            res = withSession { d -> d.mfDrive(far, amount, nearFlag) }
            if (!Rc.isBusy(res.code)) break
            delay(40)
        }
        if (res.code != Rc.OK) return "MF駆動失敗: ${PtpNames.describe(res)}（${if (far) "Far" else "Near"}, steps=$amount）"
        if (caps?.afReadyPoll == true) {
            val end = System.nanoTime() + 1_500_000_000L
            while (System.nanoTime() < end) {
                val r = withSession { d -> d.afReady() }
                if (!Rc.isBusy(r.code)) break
                delay(25)
            }
        }
        focusNet += if (far) amount else -amount
        lastDriveNs = System.nanoTime()
        _focusUi.update { it.copy(net = focusNet) }
        delay(_focusCfg.value.settleMs.toLong())
        waitFrames(2, 1500)
        return null
    }

    private fun focusPrecheck(needLiveView: Boolean): String? {
        if (wifi.network == null) return "Wi-Fi未接続"
        if (needLiveView && !_ui.value.liveViewRunning) return "ライブビューを開始してください（解析にライブビュー映像が必要）"
        val c = caps
        if (c != null && !c.mfDrive) return "${_ui.value.camera?.title ?: _ui.value.brand.label} は通信でのMF駆動に未対応です。この機能は使えません"
        return null
    }

    /** 手動Near/Far。いつでも自動補正より優先（実行中の補正は中断する）。level: 0=微 1=標準 2=大。 */
    fun manualFocus(far: Boolean, level: Int) {
        manualHoldUntilNs = System.nanoTime() + 2_000_000_000L   // 予測フォーカスより手動を優先
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            try {
                focusPrecheck(false)?.let { focusStatus(it); return@launch }
                _focusUi.update { it.copy(running = false) }
                applyFocalLength()
                val fine = _focusUi.value.fine
                val mag = when (level) { 0 -> fine; 1 -> fine * 5; else -> fine * 20 }
                val units = if (far) mag else -mag
                val err = driveFocus(units)
                focusStatus(err ?: "手動 ${if (far) "Far" else "Near"} ${abs(units)}ステップ完了（累計 $focusNet）")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("手動フォーカス失敗", e)
                focusStatus("手動フォーカス失敗: ${e.message}")
            }
        }
    }

    /** アプリが動かした累計ぶんを戻す（レンズのバックラッシュ等で完全な元位置にはならない場合がある）。 */
    fun focusReset() {
        manualHoldUntilNs = System.nanoTime() + 2_000_000_000L
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            try {
                focusPrecheck(false)?.let { focusStatus(it); return@launch }
                val back = -focusNet
                if (back == 0) { focusStatus("戻す量がありません"); return@launch }
                var left = back
                while (left != 0) {
                    val d = left.coerceIn(-MAX_SINGLE_STEP, MAX_SINGLE_STEP)
                    val err = driveFocus(d)
                    if (err != null) { focusStatus(err); return@launch }
                    left -= d
                }
                focusStatus("アプリでの累計移動を戻しました")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { focusStatus("戻し失敗: ${e.message}") }
        }
    }

    fun stopFocusCorrection() {
        focusJob?.cancel()
        _focusUi.update { it.copy(running = false) }
        focusStatus("補正を停止しました")
    }

    fun startFocusCorrection() {
        if (_focusUi.value.running) return
        focusPrecheck(true)?.let { focusStatus(it); return }
        if (_aiCfg.value.pinMode) { focusStatus("置きピンモード中は自動補正しません"); return }
        if (afInFlight.get() || _ui.value.captureBusy) { focusStatus("AF/撮影の実行中は補正を開始できません"); return }
        focusJob?.cancel()
        _focusUi.update { it.copy(running = true, verdict = "解析中", confidence = 0, status = "補正を開始") }
        focusJob = viewModelScope.launch {
            try {
                runCorrection()
            } catch (e: CancellationException) {
                focusStatus("補正を中断しました（手動操作または停止）")
                throw e
            } catch (e: Exception) {
                AppLog.e("ピント補正失敗", e)
                _focusUi.update { it.copy(verdict = "判定不能", confidence = 0) }
                focusStatus("補正失敗: ${e.message}")
            } finally {
                _focusUi.update { it.copy(running = false) }
            }
        }
    }

    private fun finishCorrection(verdict: String, conf: Int, msg: String) {
        _focusUi.update { it.copy(verdict = verdict, confidence = conf, status = msg) }
        AppLog.i("ピント補正: $verdict / 信頼度${conf}% / $msg")
    }

    /**
     * 山登り法によるピント補正。
     *  1. 現在位置(0)と ±step のシャープネスを測る（3点）。どちらにも改善しなければ「現在位置がピーク付近」。
     *  2. 改善した側へ、改善が続く間 step ずつ進む。行き過ぎたらベスト位置へ戻り、step を半分にして逆側を試す。
     *  3. step が最小値を下回る/最大回数/移動上限に達したら終了。開始時より悪ければ元の位置へ戻す。
     * 位置は「開始位置=0, Far=+, Near=−」。前ピン/後ピンの表記は、方向確認済み(dirConfirmed)のときだけ。
     */
    private suspend fun runCorrection() {
        val cfg = _focusCfg.value
        applyFocalLength()
        val ui0 = _focusUi.value
        val fine = ui0.fine
        val region = evalRegion()
        val eps = cfg.sensitivity
        val startNet = focusNet

        val base = measureStable(region, 3)
        if (base == null) { finishCorrection("判定不能", 0, "ライブビュー映像を取得できません"); return }
        if (base.s.score < MIN_SCORE) { finishCorrection("判定不能", 0, "低コントラストで解析できません（エッジの少ない面/暗すぎる）。補正しません"); return }
        if (base.cv > 0.25f) { finishCorrection("判定不能", 0, "フレーム間でシャープネスが安定しません（動き・ノイズ）。補正しません"); return }
        val blur = if (base.s.anisotropy > 3.5f) "／モーションブレの疑いあり" else ""

        var pos = 0
        var failMsg: String? = null
        suspend fun at(target: Int): Float? {
            val err = driveFocus(target - pos)
            if (err != null) { failMsg = err; return null }
            pos = target
            val m = measureStable(region, 2)
            if (m == null) { failMsg = "ライブビュー映像を取得できません"; return null }
            _focusUi.update { it.copy(score = m.s.score) }
            return m.s.score
        }

        var step = fine
        val sPlus = at(step)
        if (sPlus == null) { finishCorrection("判定不能", 0, failMsg ?: "駆動失敗"); return }
        val sMinus = at(-step)
        if (sMinus == null) { at(0); finishCorrection("判定不能", 0, failMsg ?: "駆動失敗"); return }
        val b0 = base.s.score
        var best = b0
        var bestPos = 0
        var dir = 0
        if (sPlus > b0 * (1 + eps) && sPlus >= sMinus) { dir = 1; best = sPlus; bestPos = step }
        else if (sMinus > b0 * (1 + eps)) { dir = -1; best = sMinus; bestPos = -step }

        if (dir == 0) {
            at(0)
            val bothWorse = sPlus < b0 * (1 - eps) && sMinus < b0 * (1 - eps)
            if (bothWorse) {
                val drop = ((b0 - maxOf(sPlus, sMinus)) / b0).coerceIn(0f, 1f)
                val conf = (50f * (1f - minOf(1f, base.cv * 3f)) + 50f * drop).toInt()
                finishCorrection("合焦（ピーク付近）", conf, "前後どちらへ動かしてもシャープネスが下がりました。補正不要$blur")
            } else {
                finishCorrection("判定不能", 0, "±${step}ステップでシャープネスが変化しません。ステップ校正を実行するか、コントラストのある領域で試してください")
            }
            return
        }

        var curStep = step
        var dirC = dir
        var iter = 0
        val minStep = maxOf(2, fine / 4)
        while (iter < cfg.maxIter && curStep >= minStep) {
            iter++
            val target = bestPos + dirC * curStep
            if (abs(target) > cfg.maxTotal) { focusStatus("移動上限(±${cfg.maxTotal})に達したため終了"); break }
            val sc = at(target)
            if (sc == null) { at(bestPos); finishCorrection("判定不能", 0, failMsg ?: "駆動失敗"); return }
            if (sc > best * (1 + eps / 2)) { best = sc; bestPos = target }
            else { curStep /= 2; dirC = -dirC }   // 行き過ぎ: 半分の刻みで逆側を試す
        }
        val errBack = driveFocus(bestPos - pos)
        if (errBack == null) pos = bestPos
        val fin = measureStable(region, 3)
        val finScore = fin?.s?.score ?: best
        if (finScore < b0 * (1 - eps)) {
            driveFocus(-pos); pos = 0
            finishCorrection("判定不能", 0, "補正後のほうがシャープネスが低いため、元の位置へ戻しました（悪化防止）")
            return
        }
        val gain = ((finScore - b0) / b0).coerceIn(0f, 1f)
        val conf = (50f * (1f - minOf(1f, (fin?.cv ?: base.cv) * 3f)) + 50f * gain).toInt()
        val moved = pos - 0
        val verdict = when {
            abs(moved) < maxOf(1, fine / 2) -> "合焦（ピーク付近）"
            focusStore.dirConfirmed && cfg.verdictOn -> if (moved > 0) "前ピン（Far側へ補正）" else "後ピン（Near側へ補正）"
            else -> "ピント位置ずれ（${if (moved > 0) "Far" else "Near"}側へ補正。方向が未確認のため前/後は断定しません）"
        }
        focusStore.setLastResult(_focusUi.value.bucket, "移動 ${moved}ステップ / シャープネス %.1f→%.1f (%+.0f%%)".format(b0, finScore, (finScore / b0 - 1) * 100))
        _focusUi.update { it.copy(lastResult = focusStore.lastResult(it.bucket)) }
        recordDistancePoint(true)   // 合焦位置と枠の大きさ(=距離)の対応を記録（予測フォーカスの校正）
        finishCorrection(verdict, conf, "補正完了: 開始位置から ${moved}ステップ、シャープネス %.1f → %.1f$blur".format(b0, finScore))
        if (startNet == focusNet) AppLog.i("ピント補正: 位置変化なし")
    }

    /** ステップ校正: シャープネスが約15%変わる最小のステップ量を探し、現在の焦点距離の微調整量として保存する。 */
    fun calibrateStep() {
        if (_focusUi.value.running) return
        focusPrecheck(true)?.let { focusStatus(it); return }
        focusJob?.cancel()
        _focusUi.update { it.copy(running = true, status = "ステップ校正中…") }
        focusJob = viewModelScope.launch {
            try {
                applyFocalLength()
                val bucket = _focusUi.value.bucket
                val region = evalRegion()
                val base = measureStable(region, 3)
                if (base == null || base.s.score < MIN_SCORE || base.cv > 0.25f) { focusStatus("校正できません: 領域のコントラスト不足またはシャープネスが不安定"); return@launch }
                var found = 0
                for (st in listOf(4, 8, 16, 32, 64, 128, 256)) {
                    val e1 = driveFocus(st)
                    if (e1 != null) { focusStatus(e1); return@launch }
                    val m = measureStable(region, 2) ?: run { focusStatus("ライブビュー映像を取得できません"); return@launch }
                    val rel = abs(m.s.score - base.s.score) / base.s.score
                    val e2 = driveFocus(-st)
                    if (e2 != null) { focusStatus(e2); return@launch }
                    if (rel > 0.15f) { found = st; break }
                }
                if (found == 0) { focusStatus("256ステップまででシャープネスが変化しませんでした（MfDriveが効いていない/コントラスト不足の可能性）。校正値は保存しません"); return@launch }
                if (bucket == null) {
                    focusStatus("目安は ${found}ステップですが、焦点距離が校正点(70/85/100/135/150/180/200mm)に合わないため保存しません。ズームを合わせて再実行してください")
                    return@launch
                }
                focusStore.setFine(bucket, found, true)
                applyFocalLength()
                focusStatus("${bucket}mm の微調整量を ${found}ステップに校正して保存しました")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("ステップ校正失敗", e)
                focusStatus("ステップ校正失敗: ${e.message}")
            } finally {
                _focusUi.update { it.copy(running = false) }
            }
        }
    }

    // ---- 方向の確認 ----
    /** 「Nearを押すと近くの被写体がシャープになる」ことを目視で確認できたとき。 */
    fun confirmFocusDirection() {
        focusStore.dirConfirmed = true
        _focusUi.update { it.copy(dirConfirmed = true, nearFlag = focusStore.nearFlag) }
        focusStatus("方向を確認済みにしました（Near=フラグ${focusStore.nearFlag}）")
    }
    /** Near/Far が逆だったとき入れ替える。 */
    fun swapFocusDirection() {
        focusStore.nearFlag = if (focusStore.nearFlag == 1) 2 else 1
        focusStore.dirConfirmed = true
        _focusUi.update { it.copy(dirConfirmed = true, nearFlag = focusStore.nearFlag) }
        focusStatus("Near/Farを入れ替えました（Near=フラグ${focusStore.nearFlag}）")
    }

    // ---- 設定 ----
    fun setFocusAnalysis(on: Boolean) = _focusCfg.update { it.copy(analysisOn = on) }
    fun setFocusVerdict(on: Boolean) = _focusCfg.update { it.copy(verdictOn = on) }
    fun setFocusAutoCorrect(on: Boolean) = _focusCfg.update { it.copy(autoCorrectOn = on) }
    fun setFocusSensitivity(v: Float) = _focusCfg.update { it.copy(sensitivity = v.coerceIn(0.03f, 0.25f)) }
    fun setFocusSettle(ms: Int) = _focusCfg.update { it.copy(settleMs = ms.coerceIn(100, 800)) }
    fun setFocusMaxIter(n: Int) = _focusCfg.update { it.copy(maxIter = n.coerceIn(2, 16)) }
    fun setFocusMaxTotal(n: Int) = _focusCfg.update { it.copy(maxTotal = n.coerceIn(50, 900)) }
    fun setFocusFine(v: Int) {
        val bucket = _focusUi.value.bucket
        focusStore.setFine(bucket, v.coerceIn(2, 300), false)   // 手動で変えた値は「校正済み」にしない
        _focusUi.update { it.copy(fine = focusStore.fine(bucket), calibrated = false) }
    }

    // ------------------------------------------------------------------
    // PTPイベント（連写の撮影通知・カメラ本体での設定変更の反映）
    // ------------------------------------------------------------------
    private val objectHandles = java.util.Collections.synchronizedList(ArrayList<Int>())
    private val objectAddedCount = AtomicInteger(0)
    private val captureCompleteCount = AtomicInteger(0)
    private var propRefreshJob: Job? = null
    private var lastPropRefreshNs = 0L

    private val eventHandler: (Int, List<Int>) -> Unit = { code, params ->
        when (code) {
            0x4002 -> {   // ObjectAdded
                objectAddedCount.incrementAndGet()
                params.firstOrNull()?.let { h -> objectHandles.add(h); if (objectHandles.size > 50) objectHandles.removeAt(0) }
            }
            0x400D -> captureCompleteCount.incrementAndGet()   // CaptureComplete
            0x4006 -> scheduleExposureRefresh()                // DevicePropChanged: カメラ本体で設定が変わった
        }
    }

    /** カメラ本体で変更された設定をアプリへ反映する（連続イベントはまとめ、最短2秒間隔）。 */
    private fun scheduleExposureRefresh() {
        propRefreshJob?.cancel()
        propRefreshJob = viewModelScope.launch {
            delay(600)
            val now = System.nanoTime()
            if (now - lastPropRefreshNs < 2_000_000_000L) return@launch
            lastPropRefreshNs = now
            try {
                val exp = withSession { d -> d.readExposure() }
                _ui.update { it.copy(exposure = exp) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { AppLog.w("設定の自動反映に失敗: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------
    // 遮蔽物に強い追跡（TRACKING / OCCLUDED / PREDICTING / REACQUIRING / LOST）
    // ------------------------------------------------------------------
    private val trackMgr = TrackManager()
    private val _trackCfg = MutableStateFlow(TrackCfg())
    val trackCfg: StateFlow<TrackCfg> = _trackCfg.asStateFlow()
    private val _tracks = MutableStateFlow<List<TrackView>>(emptyList())
    val tracks: StateFlow<List<TrackView>> = _tracks.asStateFlow()
    private var lastRel: Pair<Float, Float>? = null
    @Volatile private var targetView: TrackView? = null

    fun setOcclusionTolerance(v: Float) = _trackCfg.update { it.copy(occlusionTolerance = v.coerceIn(0.5f, 8f), predictMax = maxOf(it.predictMax, v.coerceIn(0.5f, 8f))) }
    fun setPredictMax(v: Float) = _trackCfg.update { it.copy(predictMax = maxOf(v.coerceIn(1f, 12f), it.occlusionTolerance), loseAfter = maxOf(it.loseAfter, v.coerceIn(1f, 12f) + 1f)) }
    fun setReacqThreshold(v: Float) = _trackCfg.update { it.copy(reacqThreshold = v.coerceIn(0.2f, 0.8f)) }

    // ------------------------------------------------------------------
    // 車両速度測定（推定値）
    // ------------------------------------------------------------------
    private val speedEst = SpeedEstimator()
    private val speedStore by lazy { SpeedProfileStore(getApplication<Application>()) }
    private val _speedOn = MutableStateFlow(false)
    val speedOn: StateFlow<Boolean> = _speedOn.asStateFlow()
    private val _speedProfile = MutableStateFlow(SpeedProfile())
    val speedProfile: StateFlow<SpeedProfile> = _speedProfile.asStateFlow()
    private val _speeds = MutableStateFlow<Map<Int, SpeedInfo>>(emptyMap())
    val speeds: StateFlow<Map<Int, SpeedInfo>> = _speeds.asStateFlow()
    private val _calibPick = MutableStateFlow(0)          // 0=なし 1=基準点1を待つ 2=基準点2を待つ
    val calibPick: StateFlow<Int> = _calibPick.asStateFlow()
    private val _profileNames = MutableStateFlow<List<String>>(emptyList())
    val profileNames: StateFlow<List<String>> = _profileNames.asStateFlow()

    fun setSpeedOn(on: Boolean) {
        _speedOn.value = on
        if (!on) { speedEst.reset(); _speeds.value = emptyMap() }
    }
    fun startCalibPick() { _calibPick.value = 1 }
    fun cancelCalibPick() { _calibPick.value = 0 }
    private fun pickCalibPoint(nx: Float, ny: Float) {
        when (_calibPick.value) {
            1 -> { _speedProfile.update { it.copy(p1x = nx, p1y = ny) }; _calibPick.value = 2 }
            2 -> { _speedProfile.update { it.copy(p2x = nx, p2y = ny) }; _calibPick.value = 0 }
        }
        speedEst.reset()
    }
    fun updateSpeedProfile(p: SpeedProfile) { _speedProfile.value = p; speedEst.reset() }
    fun refreshProfileNames() { _profileNames.value = speedStore.names() }
    fun saveSpeedProfile() {
        val p = _speedProfile.value
        if (p.name.isBlank()) { _aiUi.update { it.copy(lastAction = "校正プロファイルに名前（撮影地点）を入力してください") }; return }
        speedStore.save(p); refreshProfileNames()
        _aiUi.update { it.copy(lastAction = "校正プロファイル「${p.name}」を保存しました") }
    }
    fun loadSpeedProfile(name: String) { speedStore.load(name)?.let { _speedProfile.value = it; speedEst.reset() } }
    fun deleteSpeedProfile(name: String) { speedStore.delete(name); refreshProfileNames() }

    /** 追跡結果から速度を更新し、オービスモードの判定を行う（AIループのスレッド）。 */
    private fun postTrack(bmp: Bitmap, t: Long) {
        val views = _tracks.value
        val infos = if (_speedOn.value) speedEst.update(views, t, _speedProfile.value, bmp.width, bmp.height, _focusUi.value.focalMm) else emptyMap()
        _speeds.value = infos
        if (_orbisCfg.value.enabled && _speedOn.value) orbisCheck(views, infos, bmp)
    }

    // ------------------------------------------------------------------
    // オービスモード（娯楽・技術検証用。法的な取締り・違反判定には使えない）
    // ------------------------------------------------------------------
    private val _orbisCfg = MutableStateFlow(OrbisCfg())
    val orbisCfg: StateFlow<OrbisCfg> = _orbisCfg.asStateFlow()
    private val _orbisHits = MutableStateFlow<List<OrbisHit>>(emptyList())
    val orbisHits: StateFlow<List<OrbisHit>> = _orbisHits.asStateFlow()
    private val orbisOver = HashMap<Int, Int>()
    private val orbisDone = HashSet<Int>()
    private val ocrMutex = Mutex()
    private var plateOcr: PlateOcr? = null
    private val orbisHistory by lazy { OrbisHistoryStore(getApplication<Application>()) }

    fun setOrbisEnabled(on: Boolean) {
        _orbisCfg.update { it.copy(enabled = on) }
        if (on && !_speedOn.value) setSpeedOn(true)   // オービスは速度測定が前提
    }
    fun setOrbisLimit(kmh: Int) = _orbisCfg.update { it.copy(limitKmh = kmh.coerceIn(5, 300)) }
    fun setOrbisSound(on: Boolean) = _orbisCfg.update { it.copy(sound = on) }
    fun setOrbisSave(on: Boolean) = _orbisCfg.update { it.copy(saveHistory = on) }
    fun setOrbisShoot(on: Boolean) = _orbisCfg.update { it.copy(shootForOcr = on) }
    fun setOrbisMinConf(v: Int) = _orbisCfg.update { it.copy(minConf = v.coerceIn(0, 95)) }
    /** 検知履歴（画面上の履歴・サムネイル・保存済みテキスト履歴）をすべて削除する。 */
    fun clearOrbisHistory() {
        _orbisHits.value = emptyList(); orbisDone.clear(); orbisOver.clear()
        orbisHistory.clear()
        _aiUi.update { it.copy(lastAction = "オービスの検知履歴を削除しました") }
    }
    fun orbisSavedCount(): Int = orbisHistory.count()

    private fun orbisCheck(views: List<TrackView>, infos: Map<Int, SpeedInfo>, bmp: Bitmap) {
        val cfg = _orbisCfg.value
        for (v in views) {
            val si = infos[v.id] ?: continue
            val kmh = si.kmh
            if (v.id in orbisDone || kmh == null || v.state != TrackState.TRACKING) continue
            if (kmh >= cfg.limitKmh && si.conf >= cfg.minConf) {
                val n = (orbisOver[v.id] ?: 0) + 1
                orbisOver[v.id] = n
                if (n >= cfg.confirmFrames) { orbisDone.add(v.id); registerHit(v, si, bmp, cfg) }
            } else orbisOver[v.id] = 0
        }
        val alive = views.map { it.id }.toSet()
        orbisOver.keys.retainAll(alive)
    }

    private fun cropThumb(bmp: Bitmap, box: com.example.d750bridge.ai.Det): Bitmap? {
        val w = bmp.width; val h = bmp.height
        val l = (box.x0 * w).toInt().coerceIn(0, w - 2); val t = (box.y0 * h).toInt().coerceIn(0, h - 2)
        val r = (box.x1 * w).toInt().coerceIn(l + 1, w); val b = (box.y1 * h).toInt().coerceIn(t + 1, h)
        if (r - l < 8 || b - t < 8) return null
        val crop = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
        val s = 160f / crop.width
        if (s >= 1f) return crop
        val sc = Bitmap.createScaledBitmap(crop, 160, (crop.height * s).toInt().coerceAtLeast(1), true)
        if (sc !== crop) crop.recycle()
        return sc
    }

    private fun beep() {
        PlatformIo.beep()
    }

    private fun updateHit(id: Int, f: (OrbisHit) -> OrbisHit) {
        _orbisHits.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    private fun registerHit(v: TrackView, si: SpeedInfo, bmp: Bitmap, cfg: OrbisCfg) {
        val thumb = try { cropThumb(bmp, v.box) } catch (_: Exception) { null }
        val hit = OrbisHit(v.id, System.currentTimeMillis(), si.kmh ?: 0f, cfg.limitKmh, si.conf, "読み取り中…", 0, "", thumb)
        _orbisHits.update { (listOf(hit) + it).take(30) }
        if (cfg.sound) beep()
        AppLog.i("オービス検知: ID${v.id} 推定%.0f km/h（設定 ${cfg.limitKmh}）信頼度${si.conf}%%".format(si.kmh ?: 0f))
        viewModelScope.launch(Dispatchers.Default) { ocrMutex.withLock { runPlateOcr(v.id, cfg) } }
    }

    /** ナンバー認識。同じ車両は毎フレーム読まず、検知時に最大4回だけ読む（高解像度撮影OCRは任意）。 */
    private suspend fun runPlateOcr(id: Int, cfg: OrbisCfg) {
        val ocr = plateOcr ?: PlateOcr(getApplication<Application>()).also { plateOcr = it }
        if (!ocr.available) {
            updateHit(id) { it.copy(plate = "認識不能", plateNote = "OCRを初期化できません") }
            return
        }
        val votes = PlateVotes()
        var note = ""
        if (cfg.shootForOcr) {
            val r = try { ocrFromCapture(ocr, id) } catch (e: CancellationException) { throw e } catch (e: Exception) { AppLog.w("撮影OCR失敗: ${e.message}"); null }
            if (r != null) { votes.add(r); votes.add(r) } else note = "高解像度撮影からは読めず（JPEG記録/通信を確認）。"   // 高解像度の読み取りは2票分
        }
        for (i in 0 until 4) {
            val v = _tracks.value.firstOrNull { it.id == id }
            val bmp = _liveFrame.value
            if (v != null && bmp != null) {
                val crop = cropVehicleForOcr(bmp, v.box)
                if (crop != null) { votes.add(ocr.read(crop)); crop.recycle() }
            }
            if ((votes.best()?.second ?: 0) >= 70) break
            delay(600)
        }
        val best = votes.best()
        val ok = best != null && best.second >= 55
        val plate = if (ok) best!!.first else "認識不能"
        val pc = if (ok) best!!.second else 0
        val pn = note + if (best != null && !ok) "候補「${best.first}」は読み取りが不安定なため未確定（${best.second}%）" else if (best == null) "ナンバーの形式に合う文字列を読めませんでした（LV解像度では小さすぎることが多い）" else "読取信頼度は一致回数ベースの目安"
        updateHit(id) { it.copy(plate = plate, plateConf = pc, plateNote = pn) }
        if (_orbisCfg.value.saveHistory) _orbisHits.value.firstOrNull { it.id == id }?.let { orbisHistory.append(it) }
        AppLog.i("ナンバー: $plate（信頼度$pc%）")
    }

    private suspend fun downloadObject(handle: Int, size: Long): ByteArray? {
        val bos = ByteArrayOutputStream(size.toInt().coerceAtLeast(1024))
        var off = 0L
        while (off < size) {
            val len = minOf(256 * 1024L, size - off).toInt()
            val chunk: ByteArray
            try { chunk = withSession { d -> d.requirePtp().getPartialObject(handle, off.toInt(), len) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { return null }
            if (chunk.isEmpty()) return null
            bos.write(chunk); off += chunk.size
        }
        return bos.toByteArray()
    }

    /**
     * 検知時に1枚撮影し、新しく増えたJPEGを取得して、車両の下側（ナンバーがある辺り）だけをフル解像度で切り出してOCRする。
     * LVと撮影画像は同じ画角(FX 3:2)・撮影遅延ぶんの車両移動は枠を広げて吸収、という仮定（未検証）。JPEGで記録していないと使えない。
     */
    private suspend fun ocrFromCapture(ocr: PlateOcr, id: Int): PlateRead? {
        if (caps?.media != true) return null   // 撮影画像の取得はPTP系（Nikon/Canon）のみ
        if (_ui.value.captureBusy || burstActive) return null
        val box = _tracks.value.firstOrNull { it.id == id }?.box ?: return null
        val before = objectAddedCount.get()
        val r = withSession { d -> captureMaybeInLiveView(d) }
        if (r.code != PtpIpClient.RC_OK) return null
        var tries = 0
        while (objectAddedCount.get() == before && tries < 50) { delay(100); tries++ }
        delay(400)   // RAW+JPEGの両方の通知を待つ
        val added = objectAddedCount.get() - before
        if (added <= 0) return null
        val handles = synchronized(objectHandles) { objectHandles.takeLast(added).toList() }
        var jpeg: ByteArray? = null
        for (h in handles.asReversed()) {
            var info: PtpIpClient.ObjectInfo? = null
            try { info = withSession { d -> d.requirePtp().getObjectInfo(h) } } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            if (info == null || info.format != 0x3801 || info.size > 40_000_000L) continue
            jpeg = downloadObject(h, info.size)
            if (jpeg != null) break
        }
        val jp = jpeg ?: return null
        val bmp = PlatformIo.decodeRegion(jp, sample = { w -> maxOf(1, w / 2400) }) { W, H ->
            val bw = box.x1 - box.x0; val bh = box.y1 - box.y0
            val l = ((box.x0 - 0.25f * bw) * W).toInt().coerceIn(0, W - 2)
            val rr = ((box.x1 + 0.25f * bw) * W).toInt().coerceIn(l + 1, W)
            val t = ((box.y0 + 0.35f * bh) * H).toInt().coerceIn(0, H - 2)
            val b = ((box.y1 + 0.10f * bh) * H).toInt().coerceIn(t + 1, H)
            intArrayOf(l, t, rr, b)
        } ?: return null
        val read = ocr.read(bmp)
        bmp.recycle()
        return read
    }

    // ------------------------------------------------------------------
    // 連写エンジン（通常 / 高速=カメラ連写 / AI追従）
    // ------------------------------------------------------------------
    private val _burstCfg = MutableStateFlow(BurstCfg())
    val burstCfg: StateFlow<BurstCfg> = _burstCfg.asStateFlow()
    private val _burstUi = MutableStateFlow(BurstUi())
    val burstUi: StateFlow<BurstUi> = _burstUi.asStateFlow()
    @Volatile private var burstRun = false
    @Volatile private var burstActive = false

    fun setBurstMode(m: BurstMode) = _burstCfg.update { it.copy(mode = m) }
    fun setBurstLimited(on: Boolean) = _burstCfg.update { it.copy(limited = on) }
    fun setBurstCount(n: Int) = _burstCfg.update { it.copy(count = n.coerceIn(1, 999)) }
    fun setBurstInterval(ms: Int) = _burstCfg.update { it.copy(intervalMs = ms.coerceIn(0, 5000)) }
    fun setBurstNative(n: Int) = _burstCfg.update { it.copy(nativeN = n.coerceIn(2, 20)) }

    private suspend fun burstLoop(keepGoing: () -> Boolean, useLimit: Boolean) {
        val cfg = _burstCfg.value
        val limit = if (useLimit && cfg.limited) cfg.count else 999
        val checkReady = caps?.afReadyPoll == true
        var instr = 0
        var errors = 0
        var consecutive = 0
        var busyWaits = 0
        var native = false
        var savedMode: Long? = null
        var stopReason = "終了"
        val evBase = objectAddedCount.get()
        val t0 = System.nanoTime()
        var lastUi = 0L
        burstActive = true
        _burstUi.value = BurstUi(state = "開始")
        try {
            if (cfg.mode == BurstMode.HIGH) {
                val r = withSession { d -> d.prepareNativeBurst(cfg.nativeN) }
                native = r.first; savedMode = r.third
                _burstUi.update { it.copy(note = r.second) }
            }
            while (keepGoing() && instr < limit) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                // 1) カメラが撮影可能か（バッファ満杯・SD書き込み中は DeviceBusy）
                if (checkReady) {
                    var ready = false
                    val end = System.nanoTime() + 2_000_000_000L
                    while (System.nanoTime() < end && keepGoing()) {
                        val rr = withSession { d -> d.afReady() }
                        if (!Rc.isBusy(rr.code)) { ready = true; break }
                        busyWaits++
                        _burstUi.update { it.copy(cameraReady = "待機中（バッファ/書き込み）", busyWaits = busyWaits) }
                        delay(10)
                    }
                    if (!keepGoing()) break
                    if (!ready) {
                        errors++; consecutive++
                        if (consecutive >= 3) { stopReason = "停止: カメラが2秒以内に撮影可能になりません（3回連続）"; break }
                        continue
                    }
                    _burstUi.update { it.copy(cameraReady = "撮影可") }
                }
                // 2) AI追従: 撮影指示の前に予測フォーカスを同期実行（撮影とフォーカス駆動が競合しない）
                if (cfg.mode == BurstMode.AI_FOLLOW) predictiveStep()
                // 3) 撮影指示（応答を待ってから次へ。無制限に送らない）
                val shotStart = System.nanoTime()
                val r = withSession { d -> captureMaybeInLiveView(d) }
                if (r.code != PtpIpClient.RC_OK) {
                    errors++; consecutive++
                    val f = PtpNames.describe(r)
                    AppLog.e("連写: 撮影指示が拒否 $f（${instr}回受理後・連続失敗$consecutive）")
                    if (Rc.isBusy(r.code) && consecutive < 3) { delay(100); continue }   // 回復: 少し待って再試行
                    stopReason = "停止: $f"
                    break
                }
                consecutive = 0
                instr++
                _shotTick.update { it + 1 }
                if (native) {
                    // カメラ側の連続撮影が終わるのを、完了通知/追加通知で待つ（途中でボタンを離しても、始まった連写は止められない）
                    val cc = captureCompleteCount.get(); val oa = objectAddedCount.get()
                    val end = System.nanoTime() + (3000L + cfg.nativeN * 300L) * 1_000_000L
                    while (System.nanoTime() < end) {
                        if (captureCompleteCount.get() > cc || objectAddedCount.get() - oa >= cfg.nativeN) break
                        delay(20)
                    }
                } else if (cfg.intervalMs > 0) {
                    val wait = cfg.intervalMs - (System.nanoTime() - shotStart) / 1_000_000
                    if (wait > 0) delay(wait)
                }
                val now = System.nanoTime()
                if (now - lastUi > 200_000_000L) {
                    lastUi = now
                    val sec = ((now - t0) / 1e9).coerceAtLeast(0.001)
                    val ev = objectAddedCount.get() - evBase
                    val eff = if (native) ev else instr
                    _burstUi.update { it.copy(state = "連写中", shots = instr, events = ev, fps = (eff / sec).toFloat(), errors = errors) }
                    _ui.update { it.copy(captureStatus = "連写中: 指示${instr}回 / カメラ通知${ev}件（実効 %.2f 件/秒）".format(eff / sec)) }
                }
            }
        } finally {
            burstActive = false
            val sec = ((System.nanoTime() - t0) / 1e9).coerceAtLeast(0.001)
            val ev = objectAddedCount.get() - evBase
            withContext(NonCancellable) {
                if (native) {
                    savedMode?.let { sv -> try { withSession { d -> d.restoreNativeBurst(sv) } } catch (_: Exception) { } }
                }
            }
            _burstUi.update { it.copy(state = stopReason, shots = instr, events = ev, fps = ((if (native) ev else instr) / sec).toFloat(), errors = errors, busyWaits = busyWaits) }
            _ui.update { it.copy(captureStatus = "連写終了: 指示${instr}回 / カメラ通知${ev}件 / 失敗${errors}回（$stopReason）") }
            AppLog.i("連写終了: 指示${instr} 通知${ev} 失敗${errors} busy待ち${busyWaits} $stopReason")
        }
    }

    /** 連写パネルの開始ボタン（指定枚数または停止ボタンまで）。 */
    fun startBurstPanel() {
        if (_ui.value.captureBusy) return
        captureUnavailable()?.let { msg -> _ui.update { it.copy(captureStatus = msg) }; notify(msg); return }
        burstRun = true
        _ui.update { it.copy(captureBusy = true) }
        shutterJob = viewModelScope.launch {
            try { burstLoop({ burstRun }, true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AppLog.e("連写失敗", e)
                _burstUi.update { it.copy(state = "停止: 通信エラー ${e.message}") }
            } finally { burstRun = false; _ui.update { it.copy(captureBusy = false) } }
        }
    }

    fun stopBurstPanel() { burstRun = false; shutterPressed = false }

    // ------------------------------------------------------------------
    // 距離にもとづく予測フォーカス
    // ------------------------------------------------------------------
    private val distMap = DistanceFocusMap()
    private val _predCfg = MutableStateFlow(PredCfg())
    val predCfg: StateFlow<PredCfg> = _predCfg.asStateFlow()
    private val _predUi = MutableStateFlow(PredUi())
    val predUi: StateFlow<PredUi> = _predUi.asStateFlow()
    private var anchorInv = 0f
    private var anchorPos = 0
    private var hasAnchor = false
    @Volatile private var manualHoldUntilNs = 0L
    @Volatile private var lastDriveNs = 0L
    private var lastCorrNs = 0L
    private var predJob: Job? = null

    private fun predStatus(m: String) { _predUi.update { it.copy(status = m) } }

    /** 現在の対象の枠の高さから逆距離(1/m)を計算。焦点距離不明・校正点外・追跡不安定なら null。 */
    private fun currentInv(heightFrac: Float): Pair<Float, Float>? {   // (逆距離, 距離m)
        val tv = targetView ?: return null
        val d = DistanceEst.distanceM(tv.label, heightFrac, _focusUi.value.focalMm) ?: return null
        return (1f / d) to d
    }

    /** 合焦位置と枠の大きさ(=距離)の対応を記録し、予測の基準位置にする。 */
    fun recordDistancePoint(auto: Boolean = false) {
        val tv = targetView
        val ui = _focusUi.value
        if (tv == null || tv.state != TrackState.TRACKING) { if (!auto) predStatus("追跡中の対象がありません"); return }
        val bucket = ui.bucket
        if (bucket == null || ui.focalMm == null) { if (!auto) predStatus("焦点距離が校正点(70/85/100/135/150/180/200mm)に合っていません"); return }
        val h = tv.box.y1 - tv.box.y0
        val inv = currentInv(h) ?: return
        val key = "$bucket/${tv.label}"
        distMap.record(key, inv.first, focusNet)
        anchorInv = inv.first; anchorPos = focusNet; hasAnchor = true
        _predUi.update { it.copy(points = distMap.count(key), slope = distMap.slope(key), distM = inv.second, status = "距離校正点を記録（${distMap.count(key)}点, 距離 %.1f m）".format(inv.second)) }
    }

    fun clearDistanceMap() {
        val tv = targetView; val b = _focusUi.value.bucket
        if (tv != null && b != null) distMap.clear("$b/${tv.label}")
        hasAnchor = false
        _predUi.update { it.copy(points = 0, slope = null, status = "距離校正を消去しました") }
    }

    /**
     * 予測フォーカス1回分。枠の高さの変化から被写体の距離変化を推定し、校正した「逆距離↔レンズ位置」の傾きでMfDriveを先行して動かす。
     * 距離推定が不確か（追跡が不安定・校正不足・移動量が大きすぎる）なときは動かさない。MF運用が前提（AFでレンズが動くと基準位置が無効）。
     */
    private suspend fun predictiveStep() {
        val tv = targetView
        val ui = _focusUi.value
        if (tv == null) { predStatus("対象なし"); return }
        if (tv.state != TrackState.TRACKING) { predStatus("${tv.state.label}のため予測移動しません"); return }
        if (System.nanoTime() < manualHoldUntilNs) { predStatus("手動操作を優先中"); return }
        if (ui.running) return
        val bucket = ui.bucket
        if (bucket == null || ui.focalMm == null) { predStatus("焦点距離が校正点に合わない/不明のため停止"); return }
        val key = "$bucket/${tv.label}"
        val k = distMap.slope(key)
        if (k == null) { predStatus("距離校正が不足（現在${distMap.count(key)}点。距離を変えて合焦させ、2点以上記録）"); return }
        if (!hasAnchor) { predStatus("基準位置が未記録"); return }
        if (tv.ageSec < 1f || tv.hits < 8) { predStatus("追跡が安定するまで待機"); return }
        val h = tv.box.y1 - tv.box.y0
        val hPred = (h + tv.vh * (_predCfg.value.leadMs / 1000f)).coerceAtLeast(0.01f)
        val cur = currentInv(h) ?: return
        val pred = currentInv(hPred) ?: return
        _predUi.update { it.copy(distM = cur.second, points = distMap.count(key), slope = k) }
        val target = anchorPos + (k * (pred.first - anchorInv)).toInt()
        val delta = target - focusNet
        val fine = ui.fine
        if (abs(delta) < maxOf(2, fine / 2)) { predStatus("距離 %.1f m — 移動不要".format(cur.second)); return }
        val cap = minOf(fine * 10, 400)
        if (abs(delta) > cap) { predStatus("予測移動量 $delta が上限 $cap を超えるため実行しません（距離推定が不確か）"); return }
        val err = driveFocus(delta)
        predStatus(err ?: "予測フォーカス: 距離 %.1f m（先読み %.1f m）→ %+dステップ".format(cur.second, pred.second, delta))
    }

    fun setPredEnabled(on: Boolean) {
        _predCfg.update { it.copy(enabled = on) }
        if (on) startPredLoop() else { predJob?.cancel(); predStatus("OFF") }
    }
    fun setPredFineTune(on: Boolean) = _predCfg.update { it.copy(fineTune = on) }
    fun setPredLead(ms: Int) = _predCfg.update { it.copy(leadMs = ms.coerceIn(0, 1000)) }

    private fun startPredLoop() {
        predJob?.cancel()
        predStatus("待機")
        predJob = viewModelScope.launch {
            var lastFocalNs = 0L
            while (true) {
                delay(200)
                if (!_ui.value.liveViewRunning || !_predCfg.value.enabled || burstActive || _aiCfg.value.pinMode) continue
                try {
                    val now = System.nanoTime()
                    if (now - lastFocalNs > 10_000_000_000L) { lastFocalNs = now; applyFocalLength() }
                    predictiveStep()
                    // 予測の合間の微調整: 被写体がほぼ動いておらず、しばらく補正していなければ山登りで合わせ直す
                    val tv = targetView
                    if (_predCfg.value.fineTune && tv != null && tv.state == TrackState.TRACKING && !_focusUi.value.running &&
                        now - lastDriveNs > 1_500_000_000L && now - lastCorrNs > 5_000_000_000L && now > manualHoldUntilNs
                    ) { lastCorrNs = now; startFocusCorrection() }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { predStatus("予測フォーカス失敗: ${e.message}") }
            }
        }
    }

    // ------------------------------------------------------------------
    // AF微調整値の調査（PTPで取得・変更できるかの調査ツール）
    // ------------------------------------------------------------------
    private val _propSurvey = MutableStateFlow("未実行")
    val propSurvey: StateFlow<String> = _propSurvey.asStateFlow()

    /** カメラが申告したベンダー拡張プロパティ(0xD000〜)の型・書込可否・現在値を一覧する。結果はログにも出す。 */
    fun surveyProperties() {
        viewModelScope.launch {
            try {
                val di = _ui.value.deviceInfo
                if (di == null || caps?.propSurvey != true) { _propSurvey.value = "PTP（Nikon）で接続しているときだけ調査できます。先にカメラへ接続してください"; return@launch }
                val codes = di.properties.filter { it in 0xD000..0xDFFF }
                _propSurvey.value = "調査中… ${codes.size}件"
                val lines = withSession<List<String>> { drv ->
                    val c = drv.requirePtp()
                    codes.map { code ->
                        try {
                            val d = c.getPropertyDescriptor(code)
                            "${PtpNames.hex(code)} type=0x${d.dataType.toString(16)} ${if (d.writable) "W" else "R"} 現在値=0x${Le.value(d.current).toString(16)}" +
                                (if (d.allowedValues.isNotEmpty()) " 選択肢${d.allowedValues.size}件" else if (d.range != null) " 範囲指定" else "")
                        } catch (e: Exception) { "${PtpNames.hex(code)} 取得失敗: ${e.message}" }
                    }
                }
                lines.forEach { AppLog.i("プロパティ調査: $it") }
                _propSurvey.value = "ベンダー拡張プロパティ ${codes.size}件を調査（全件はログに出力済み・「ログをエクスポート」で取得可）。\n" +
                    "AF微調整に対応するプロパティコードは、公開仕様がないため特定できていません。書込可(W)の項目は、本体でAF微調整を変えた前後で現在値が変わるかを比べると手がかりになります（このアプリから推測で書き込むことはしません）。\n" +
                    lines.take(40).joinToString("\n")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _propSurvey.value = "調査失敗: ${e.message}" }
        }
    }

    private fun pinBlocks(): Boolean {
        if (!_aiCfg.value.pinMode) return false
        _ui.update { it.copy(afStatus = "置きピンモード中のためAFは実行しません（ピンを置き直すにはライブビューをタップ。モードをOFFにすると通常AFに戻ります）") }
        return true
    }
    /** AFボタン: 最後にAFした位置（なければ中央）でAF。 */
    fun autofocus() {
        if (pinBlocks()) return
        val t = _afTarget.value
        val bmp = _liveFrame.value
        afOp("AF") { it.afAt(t?.nx ?: 0.5f, t?.ny ?: 0.5f, bmp?.width ?: 640, bmp?.height ?: 424) }
    }
    fun cancelAutofocus() = afOp("AFキャンセル") { it.cancelAf() }

    // ------------------------------------------------------------------
    // セルフタイマー
    // ------------------------------------------------------------------
    private val _selfTimer = MutableStateFlow(0)          // 設定（秒）。0=OFF
    val selfTimer: StateFlow<Int> = _selfTimer.asStateFlow()
    private val _countdown = MutableStateFlow(0)          // 残り秒（0=カウントダウンなし）
    val countdown: StateFlow<Int> = _countdown.asStateFlow()
    private var selfTimerJob: Job? = null

    fun setSelfTimer(sec: Int) { _selfTimer.value = sec.coerceIn(0, 30) }

    /** 1秒ごとのカウントダウン。中止されたら false。 */
    private suspend fun runCountdown(sec: Int): Boolean {
        val job = viewModelScope.launch {
            for (left in sec downTo 1) { _countdown.value = left; delay(1000) }
        }
        selfTimerJob = job
        job.join()
        val ok = !job.isCancelled
        _countdown.value = 0
        selfTimerJob = null
        return ok
    }

    // ------------------------------------------------------------------
    // フォーカスブラケット（MFでピントをずらしながら連続撮影。深度合成用）
    // ------------------------------------------------------------------
    private val _fbCfg = MutableStateFlow(FbCfg())
    val fbCfg: StateFlow<FbCfg> = _fbCfg.asStateFlow()
    private val _fb = MutableStateFlow(FbUi())
    val fb: StateFlow<FbUi> = _fb.asStateFlow()
    private var fbJob: Job? = null

    fun setFbShots(n: Int) = _fbCfg.update { it.copy(shots = n.coerceIn(2, 300)) }
    fun setFbStep(n: Int) = _fbCfg.update { it.copy(step = n.coerceIn(1, MAX_SINGLE_STEP)) }
    fun setFbTowardFar(on: Boolean) = _fbCfg.update { it.copy(towardFar = on) }
    fun setFbReturn(on: Boolean) = _fbCfg.update { it.copy(returnToStart = on) }
    fun setFbSettle(ms: Int) = _fbCfg.update { it.copy(settleMs = ms.coerceIn(0, 3000)) }

    private fun fbStatus(m: String) { _fb.update { it.copy(status = m) }; AppLog.i("フォーカスブラケット: $m") }

    fun startFocusBracket() {
        if (_fb.value.running) return
        val cfg = _fbCfg.value
        if (!_ui.value.connected) { fbStatus("カメラに接続してください"); return }
        captureUnavailable()?.let { fbStatus(it); notify(it); return }
        focusPrecheck(false)?.let { fbStatus(it); notify(it); return }
        if (_ui.value.captureBusy || _tl.value.running) { fbStatus("撮影中は開始できません"); return }
        focusJob?.cancel()
        _fb.value = FbUi(running = true, total = cfg.shots, status = "準備中…")
        _ui.update { it.copy(captureBusy = true) }
        fbJob = viewModelScope.launch {
            var moved = 0
            var prevMode: FocusKind? = null
            try {
                // MF駆動はライブビュー中のみ受け付ける機種があるため、先に開始する
                if (!_ui.value.liveViewRunning) {
                    fbStatus("ライブビューを開始しています…")
                    startLiveView()
                    val until = System.currentTimeMillis() + 15_000
                    while (_liveFrame.value == null && System.currentTimeMillis() < until) delay(100)
                    delay(800)
                }
                // 撮影のたびにカメラがAFし直さないよう、MFに切り替える
                val (before, res) = withSession { d -> val b = d.focusMode(); b to (if (b == FocusKind.MF) null else d.setFocusMode(FocusKind.MF)) }
                if (res != null && !res.ok) throw IllegalStateException("MFに切り替えられません（${res.msg}）。カメラ／レンズをMFにしてから実行してください")
                if (res != null) prevMode = before
                val dir = if (cfg.towardFar) 1 else -1
                for (i in 0 until cfg.shots) {
                    ensureActive()
                    fbStatus("撮影 ${i + 1} / ${cfg.shots}")
                    var r = withSession { d -> captureMaybeInLiveView(d) }
                    if (r.code != Rc.OK) { delay(500); r = withSession { d -> captureMaybeInLiveView(d) } }
                    if (r.code != Rc.OK) throw IllegalStateException("${i + 1}枚目の撮影に失敗: ${PtpNames.describe(r)}")
                    _shotTick.update { it + 1 }
                    _fb.update { it.copy(done = i + 1) }
                    if (i < cfg.shots - 1) {
                        delay(cfg.settleMs.toLong())   // 撮影の書き込み・ブレが収まるのを待つ
                        driveFocus(dir * cfg.step)?.let { throw IllegalStateException("ピント移動に失敗: $it") }
                        moved += dir * cfg.step
                    }
                }
                fbStatus("完了: ${cfg.shots}枚（ピントを合計 ${kotlin.math.abs(moved)} ステップ${if (cfg.towardFar) "遠く" else "近く"}へ移動）")
                notify("フォーカスブラケット完了（${cfg.shots}枚）")
            } catch (e: CancellationException) {
                fbStatus("中止しました（${_fb.value.done}枚撮影済み）")
                throw e
            } catch (e: Exception) {
                AppLog.e("フォーカスブラケット失敗", e)
                fbStatus("停止: ${e.message}")
                notify("フォーカスブラケットが停止しました")
            } finally {
                withContext(NonCancellable) {
                    if (cfg.returnToStart && moved != 0) {
                        var left = -moved
                        while (left != 0) {
                            val d = left.coerceIn(-MAX_SINGLE_STEP, MAX_SINGLE_STEP)
                            if (try { driveFocus(d) } catch (_: Exception) { "例外" } != null) break
                            left -= d
                        }
                    }
                    prevMode?.let { m -> try { withSession { d -> d.setFocusMode(m) } } catch (_: Exception) {} }
                }
                _fb.update { it.copy(running = false) }
                _ui.update { it.copy(captureBusy = false) }
            }
        }
    }

    fun stopFocusBracket() { fbJob?.cancel() }

    // ------------------------------------------------------------------
    // 動画撮影
    // ------------------------------------------------------------------
    private val _recStartMs = MutableStateFlow<Long?>(null)   // 録画中なら開始時刻
    val recStartMs: StateFlow<Long?> = _recStartMs.asStateFlow()
    private val _recBusy = MutableStateFlow(false)
    val recBusy: StateFlow<Boolean> = _recBusy.asStateFlow()

    fun toggleMovie() {
        if (_recBusy.value) return
        if (_recStartMs.value != null) stopMovie() else startMovie()
    }

    private fun startMovie() {
        val c = caps
        if (!_ui.value.connected || c == null) { notify("カメラに接続してください"); return }
        if (!c.movie) { notify("このカメラは通信での動画撮影に未対応です"); return }
        _recBusy.value = true
        viewModelScope.launch {
            try {
                // Nikon / Canon はライブビュー中でないと録画できない
                if (_ui.value.brand.let { it == CameraBrand.NIKON || it == CameraBrand.CANON } && !_ui.value.liveViewRunning) {
                    startLiveView()
                    val until = System.currentTimeMillis() + 15_000
                    while (_liveFrame.value == null && System.currentTimeMillis() < until) delay(100)
                    delay(800)
                }
                val r = withSession { d -> d.startMovie() }
                if (r.code == Rc.OK) {
                    _recStartMs.value = System.currentTimeMillis()
                    _ui.update { it.copy(captureStatus = "録画を開始しました") }
                } else {
                    // 応答コードに合わせた案内（コードそのものも必ず表示する）
                    val hint = when {
                        r.code == 0xA00B -> "（ライブビューが動作していません。ライブビューを開始してから録画してください）"
                        r.code == 0xA004 && _ui.value.brand == CameraBrand.NIKON ->
                            "（カメラが録画できない状態です。ライブビューセレクターが動画側か、カードの空き・書き込み禁止、本体でメニューや再生画面を表示していないかを確認してください）"
                        r.code == 0x2005 || r.code == Rc.NOT_SUPPORTED -> "（この接続方式では通信での録画に対応していない可能性があります）"
                        _ui.value.brand == CameraBrand.CANON -> "（カメラを動画モードにしてください）"
                        else -> "（「診断」タブのログを共有していただくと原因を調べられます）"
                    }
                    val msg = "録画を開始できません: ${PtpNames.describe(r)}$hint"
                    _ui.update { it.copy(captureStatus = msg) }; notify(msg)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("録画開始に失敗", e)
                notify("録画を開始できません: ${e.message}")
            } finally { _recBusy.value = false }
        }
    }

    private fun stopMovie() {
        _recBusy.value = true
        viewModelScope.launch {
            try {
                val r = withSession { d -> d.stopMovie() }
                val sec = _recStartMs.value?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0
                if (r.code == Rc.OK) {
                    _recStartMs.value = null
                    _ui.update { it.copy(captureStatus = "録画を停止しました（約${sec}秒・カメラのカードに保存）") }
                    notify("録画を停止しました（約${sec}秒）")
                } else {
                    val msg = "録画を停止できません: ${PtpNames.describe(r)}（カメラ本体で停止してください）"
                    _ui.update { it.copy(captureStatus = msg) }; notify(msg)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("録画停止に失敗", e)
                notify("録画を停止できません: ${e.message}")
            } finally { _recBusy.value = false }
        }
    }

    // ------------------------------------------------------------------
    // インターバル撮影・タイムラプス
    //  - 決まった時刻に撮影（処理が間に合わなかった回はスキップとして数え、時刻はずらさない）
    //  - カメラで本撮影（SDに保存）／ライブビュー画像をコマとして端末に保存、のどちらか・両方
    //  - 保存したコマを MP4 (H.264) にして「ムービー/CamBridge」に保存
    // ------------------------------------------------------------------
    @Volatile private var lastLiveJpeg: ByteArray? = null
    private val _tlCfg = MutableStateFlow(TlCfg())
    val tlCfg: StateFlow<TlCfg> = _tlCfg.asStateFlow()
    private val _tl = MutableStateFlow(TlUi())
    val tl: StateFlow<TlUi> = _tl.asStateFlow()
    private var tlJob: Job? = null
    private var encodeJob: Job? = null
    private val tlDir: java.io.File by lazy { java.io.File(getApplication<Application>().filesDir, "timelapse").apply { mkdirs() } }

    private fun tlFrames(): List<java.io.File> = (tlDir.listFiles { f -> f.name.endsWith(".jpg") } ?: emptyArray()).sortedBy { it.name }

    fun setTlInterval(sec: Int) = _tlCfg.update { it.copy(intervalSec = sec.coerceIn(1, 3600)) }
    fun setTlCount(n: Int) = _tlCfg.update { it.copy(count = n.coerceIn(2, 9999)) }
    fun setTlUnlimited(on: Boolean) = _tlCfg.update { it.copy(unlimited = on) }
    fun setTlShoot(on: Boolean) = _tlCfg.update { it.copy(shoot = on) }
    fun setTlRecord(on: Boolean) = _tlCfg.update { it.copy(record = on) }
    fun setTlFps(fps: Int) = _tlCfg.update { it.copy(fps = fps) }
    fun setTlStartDelay(sec: Int) = _tlCfg.update { it.copy(startDelaySec = sec.coerceIn(0, 24 * 3600)) }

    private fun tlStatus(m: String) { _tl.update { it.copy(status = m) }; AppLog.i("タイムラプス: $m") }

    private fun thumbOf(jpeg: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 4; inPreferredConfig = Bitmap.Config.RGB_565 })
    } catch (_: Exception) { null }

    fun startTimelapse() {
        if (_tl.value.running || _tl.value.encoding) return
        val cfg = _tlCfg.value
        if (!cfg.shoot && !cfg.record) { tlStatus("「カメラで撮影」か「動画用にコマを保存」のどちらかをONにしてください"); return }
        if (!_ui.value.connected) { tlStatus("カメラに接続してください"); notify("カメラに接続してください"); return }
        if (cfg.shoot) captureUnavailable()?.let { tlStatus(it); notify(it); return }
        if (cfg.record && caps?.liveView == false) { tlStatus("このカメラはライブビューに未対応のため、動画用のコマを保存できません"); return }
        val existing = tlFrames().size
        _tl.update { it.copy(running = true, shots = 0, skipped = 0, errors = 0, frames = existing, startedAtMs = System.currentTimeMillis(), status = "開始") }
        tlJob = viewModelScope.launch {
            var consecutiveErr = 0
            try {
                if (cfg.record && !_ui.value.liveViewRunning) {
                    tlStatus("ライブビューを開始しています…")
                    lastLiveJpeg = null
                    startLiveView()
                    val until = System.currentTimeMillis() + 15_000
                    while (lastLiveJpeg == null && System.currentTimeMillis() < until) delay(100)
                    if (lastLiveJpeg == null) throw IllegalStateException("ライブビューの映像が届きません")
                }
                if (cfg.startDelaySec > 0) {
                    val until = System.currentTimeMillis() + cfg.startDelaySec * 1000L
                    _tl.update { it.copy(nextAtMs = until) }
                    while (System.currentTimeMillis() < until) {
                        tlStatus("開始まで ${((until - System.currentTimeMillis() + 999) / 1000)}秒")
                        delay(minOf(500L, (until - System.currentTimeMillis()).coerceAtLeast(1L)))
                    }
                }
                val period = cfg.intervalSec * 1000L
                val start = System.currentTimeMillis()
                var slot = 0
                var frameNo = (tlFrames().lastOrNull()?.nameWithoutExtension?.toIntOrNull() ?: 0)
                while (cfg.unlimited || slot < cfg.count) {
                    val due = start + slot * period
                    _tl.update { it.copy(nextAtMs = due) }
                    tlStatus(if (cfg.unlimited) "撮影中（${slot + 1}回目）" else "撮影中（${slot + 1} / ${cfg.count}）")
                    while (System.currentTimeMillis() < due) delay(minOf(100L, due - System.currentTimeMillis()).coerceAtLeast(1L))
                    if (cfg.record) {
                        val jpeg = lastLiveJpeg
                        if (jpeg != null && _ui.value.liveViewRunning) {
                            frameNo++
                            val f = java.io.File(tlDir, "%06d.jpg".format(frameNo))
                            withContext(Dispatchers.IO) { f.writeBytes(jpeg) }
                            val th = withContext(Dispatchers.Default) { thumbOf(jpeg) }
                            _tl.update { u ->
                                u.copy(frames = u.frames + 1, strip = (if (th != null) listOf(frameNo to th) + u.strip else u.strip).take(8))
                            }
                        } else _tl.update { it.copy(skipped = it.skipped + 1) }
                    }
                    if (cfg.shoot) {
                        val r = try { withSession { d -> captureMaybeInLiveView(d) } } catch (e: CancellationException) { throw e } catch (e: Exception) { Rc.fail("(${e.message})") }
                        if (r.code == Rc.OK) {
                            consecutiveErr = 0
                            _tl.update { it.copy(shots = it.shots + 1) }
                            _shotTick.update { it + 1 }
                        } else {
                            consecutiveErr++
                            _tl.update { it.copy(errors = it.errors + 1) }
                            AppLog.w("タイムラプス撮影失敗: ${PtpNames.describe(r)}")
                            if (consecutiveErr >= 3) throw IllegalStateException("撮影が3回続けて失敗しました: ${PtpNames.describe(r)}")
                        }
                    }
                    slot++
                    // 処理が次の予定時刻を過ぎていたら、その回はスキップ（時刻はずらさない）
                    val passed = ((System.currentTimeMillis() - start) / period).toInt()
                    if (passed >= slot + 1) { _tl.update { it.copy(skipped = it.skipped + (passed - slot)) }; slot = passed }
                }
                tlStatus("完了: 撮影 ${_tl.value.shots}枚 / コマ ${_tl.value.frames}枚")
                notify("インターバル撮影が完了しました")
            } catch (e: CancellationException) {
                tlStatus("停止しました（撮影 ${_tl.value.shots}枚 / コマ ${_tl.value.frames}枚）")
                throw e
            } catch (e: Exception) {
                AppLog.e("タイムラプス失敗", e)
                tlStatus("停止: ${e.message}")
                notify("インターバル撮影が停止しました: ${e.message}")
            } finally {
                _tl.update { it.copy(running = false, nextAtMs = 0L) }
            }
        }
    }

    fun stopTimelapse() { tlJob?.cancel() }

    fun discardTimelapseFrames() {
        if (_tl.value.running || _tl.value.encoding) return
        viewModelScope.launch(Dispatchers.IO) {
            tlFrames().forEach { it.delete() }
            _tl.update { it.copy(frames = 0, strip = emptyList(), status = "コマを削除しました") }
        }
    }

    /** 保存したコマから MP4 を作り、「ムービー/CamBridge」に保存する。 */
    fun encodeTimelapse() {
        if (_tl.value.running || _tl.value.encoding) return
        val fps = _tlCfg.value.fps
        _tl.update { it.copy(encoding = true, encodeProgress = 0f) }
        encodeJob = viewModelScope.launch(Dispatchers.Default) {
            val files = tlFrames()
            var target: PendingFile? = null
            try {
                if (files.size < 2) throw IllegalStateException("コマが2枚以上必要です（現在 ${files.size}枚）")
                tlStatus("動画を作成中…（${files.size}コマ / ${fps}fps）")
                val name = "CamBridge_TL_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".mp4"
                val t = PlatformIo.create(getApplication(), SaveFolder.MOVIES, name, "video/mp4")
                target = t
                val job = coroutineContext[Job]
                com.example.d750bridge.timelapse.TimelapseEncoder.encode(files, fps, t,
                    onProgress = { p -> _tl.update { it.copy(encodeProgress = p) } },
                    isCancelled = { job?.isActive == false })
                t.publish()
                val sec = files.size.toFloat() / fps
                _tl.update { it.copy(lastVideo = t.id) }
                tlStatus("保存しました: ${SaveFolder.MOVIES.label}/$name（%.1f秒）".format(sec))
                notify("タイムラプス動画を保存しました（%.1f秒）".format(sec))
            } catch (e: Throwable) {
                target?.discard()
                if (e is CancellationException) { tlStatus("動画の作成を中止しました"); throw e }
                AppLog.e("タイムラプス動画の作成に失敗", e)
                tlStatus("動画の作成に失敗: ${e.message}")
                notify("動画の作成に失敗しました")
            } finally {
                _tl.update { it.copy(encoding = false) }
            }
        }
    }

    fun cancelEncode() { encodeJob?.cancel() }

    // ------------------------------------------------------------------
    // 露出設定（ISO / 絞り / シャッター速度 / 露出補正）。書き込み・照合は各ドライバーが行う。
    // ------------------------------------------------------------------
    private val pendingSettings = AtomicInteger(0)

    fun refreshExposure() {
        viewModelScope.launch {
            try {
                val exp = withSession { d -> d.readExposure() }
                _ui.update { it.copy(exposure = exp, settingStatus = "カメラの現在値を読み取りました") }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("露出情報の読み取り失敗", e)
                _ui.update { it.copy(settingStatus = "読み取り失敗: ${e.message}") }
            }
        }
    }

    /** 書き込み → カメラから読み直して画面に反映。 */
    private fun applyExposure(kind: ExpKind, value: Long) {
        pendingSettings.incrementAndGet()
        _ui.update { it.copy(settingBusy = true, settingStatus = "${kind.label}を変更中…") }
        viewModelScope.launch {
            try {
                val (msg, exp) = withSession { d -> d.setExposure(kind, value) to d.readExposure() }
                AppLog.i("設定: $msg")
                _ui.update { it.copy(settingStatus = msg, exposure = exp) }
                if (!msg.startsWith("OK")) notify(msg)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("${kind.label} 設定失敗", e)
                _ui.update { it.copy(settingStatus = "${kind.label} 設定失敗: ${e.message}") }
                notify("${kind.label} 設定失敗: ${e.message}")
            } finally {
                if (pendingSettings.decrementAndGet() == 0) _ui.update { it.copy(settingBusy = false) }
            }
        }
    }

    fun setIso(value: Long) = applyExposure(ExpKind.ISO, value)
    fun setAperture(valueTimes100: Long) = applyExposure(ExpKind.APERTURE, valueTimes100)
    /** raw はドライバー固有のキー（ShutterOption.raw）。 */
    fun setShutterRaw(raw: Long) = applyExposure(ExpKind.SHUTTER, raw)
    fun setBias(milli: Long) = applyExposure(ExpKind.BIAS, milli)

    /** 通常表記（1/250, 0.5, 2" など）で指定。カメラの選択肢のうち最も近いものを書き込む。 */
    fun setShutterText(text: String) {
        val sec = ShutterSpeed.parseSeconds(text)
        if (sec == null) {
            _ui.update { it.copy(settingStatus = "入力形式が不正: '$text'（例: 1/250, 0.5, 2\"）") }
            return
        }
        val opts = _ui.value.exposure.shutterOptions.filter { it.raw != ShutterSpeed.BULB && it.seconds > 0 }
        val key = opts.minByOrNull { abs(ln(it.seconds / sec)) }?.raw
            ?: if (_ui.value.brand == CameraBrand.NIKON) ShutterSpeed.toRaw(sec) else null
        if (key == null) { _ui.update { it.copy(settingStatus = "シャッター速度の選択肢を先に読み取ってください") }; return }
        setShutterRaw(key)
    }

    /** 絞り入力: "2.8" → 280, "280" → 280 */
    fun setApertureText(text: String) {
        val t = text.trim().removePrefix("F").removePrefix("f")
        val v = t.toDoubleOrNull()
        if (v == null || v <= 0) { _ui.update { it.copy(settingStatus = "絞りの入力形式が不正: '$text'（例: 2.8）") }; return }
        setAperture(if (v < 100) Math.round(v * 100) else v.toLong())
    }

    /** フォーカスモード（AF-S / AF-C / AF-A / MF）。 */
    fun setFocusKind(kind: FocusKind) {
        viewModelScope.launch {
            try {
                val (r, exp) = withSession { d -> d.setFocusMode(kind) to d.readExposure() }
                _ui.update { it.copy(settingStatus = r.msg, afStatus = r.msg, exposure = exp) }
                if (!r.ok) notify(r.msg)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("フォーカスモード設定失敗", e)
                _ui.update { it.copy(settingStatus = "フォーカスモード設定失敗: ${e.message}") }
            }
        }
    }

    // ---- ±ボタン（選択肢はカメラが返した値のみ） ----
    private fun <T : Comparable<T>> neighbor(sorted: List<T>, cur: T, dir: Int): T? {
        if (sorted.isEmpty()) return null
        val i = sorted.indexOf(cur)
        if (i >= 0) return sorted.getOrNull(i + dir)
        return if (dir > 0) sorted.firstOrNull { it > cur } else sorted.lastOrNull { it < cur }
    }

    private fun noStep(what: String) { _ui.update { it.copy(settingStatus = "$what: これ以上変更できません（選択肢の端、または選択肢未取得）") } }

    fun stepIso(dir: Int) {
        val e = _ui.value.exposure
        val cur = e.iso ?: return noStep("ISO")
        val next = neighbor(e.isoOptions.sorted(), cur, dir) ?: return noStep("ISO")
        setIso(next)
    }

    fun stepAperture(dir: Int) {
        val e = _ui.value.exposure
        val cur = e.apertureX100 ?: return noStep("絞り")
        val next = neighbor(e.apertureOptions.filter { it > 0 }.sorted(), cur, dir) ?: return noStep("絞り")
        setAperture(next)
    }

    /** slower=true で長い秒数側、false で短い（速い）側。 */
    fun stepShutter(slower: Boolean) {
        val e = _ui.value.exposure
        val cur = e.shutterRaw ?: return noStep("シャッター速度")
        val opts = e.shutterOptions.sortedBy { it.seconds }
        val i = opts.indexOfFirst { it.raw == cur }
        val next = (if (i >= 0) opts.getOrNull(i + if (slower) 1 else -1) else null) ?: return noStep("シャッター速度")
        setShutterRaw(next.raw)
    }

    fun stepBias(dir: Int) {
        val e = _ui.value.exposure
        val cur = e.biasMilli ?: return noStep("露出補正")
        val next = neighbor(e.biasOptions.sorted(), cur, dir) ?: return noStep("露出補正")
        setBias(next)
    }

    override fun onCleared() {
        liveViewJob?.cancel(); transferJob?.cancel(); thumbJob?.cancel(); shutterJob?.cancel(); keepAliveJob?.cancel(); tlJob?.cancel()
        sessionRef?.abort()
        detector?.close()
        stopCanonResponder()
        try { plateOcr?.close() } catch (_: Exception) {}
        wifi.disconnect()
        super.onCleared()
    }

    init {
        // Wi-Fiの段階が変わったときだけ反応する（SSID/IPの更新では反応しない）
        viewModelScope.launch {
            wifi.state.map { it.phase to it.message }.distinctUntilChanged().collect { (phase, msg) -> onWifiChanged(phase, msg) }
        }
        // 前回までに保存したタイムラプスのコマ数（アプリを閉じても残る）
        viewModelScope.launch(Dispatchers.IO) { val n = tlFrames().size; if (n > 0) _tl.update { it.copy(frames = n, status = "保存済みのコマ ${n}枚（動画にできます）") } }
    }
}
