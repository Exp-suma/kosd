package app.narabuspulse.feature.camera

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Export
import app.narabuspulse.core.Json
import app.narabuspulse.core.Logic
import app.narabuspulse.core.bool
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.wifi.BridgeClient
import app.narabuspulse.feature.wifi.BridgeError
import app.narabuspulse.feature.wifi.BridgeSetupCard
import app.narabuspulse.feature.wifi.BridgeState
import app.narabuspulse.feature.wifi.CamFile
import app.narabuspulse.feature.wifi.portSpecOf
import app.narabuspulse.feature.wifi.rememberBridge
import app.narabuspulse.feature.wifi.rememberMakers
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

private const val PREFS_KEY = "camera-console-v1"
private fun store(context: Context) = context.getSharedPreferences("logic_storage", Context.MODE_PRIVATE)
private fun readAll(context: Context): JsonObject = store(context).getString(PREFS_KEY, null)?.let { runCatching { Json.parse(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())
private fun writeAll(context: Context, value: JsonObject) = store(context).edit().putString(PREFS_KEY, value.toString()).apply()
private val UNSUPPORTED = Regex("対応していません|非対応|NotSupported|0x2005", RegexOption.IGNORE_CASE)
private fun statusText(s: String) = when (s) { "connected" -> "接続中"; "connecting" -> "接続しています"; "error" -> "エラー"; "lost" -> "切断されました"; else -> "未接続" }
private fun statusColor(s: String) = when (s) { "connected" -> Brand.Success; "connecting" -> Brand.Warning; "error", "lost" -> Brand.Destructive; else -> Brand.MutedFg }
private fun toneOf(status: String?) = when (status) { "supported" -> Brand.Success; "unverified" -> Brand.Warning; "error" -> Brand.Destructive; else -> Brand.MutedFg }
private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private class TransferRow(val name: String, val role: String, val ok: Boolean, val bytes: Long = 0, val sha256: String = "", val sizeMatch: Boolean? = null, val resumeUsed: Boolean = false, val error: String = "")

/** USB と Wi-Fi（ブリッジ）を同じ操作で扱う状態（Web: useCameraConsole.js + useCamera.js + useWifiCameraControl.js）。 */
@Composable
private fun rememberConsole(bridge: BridgeState, makers: List<JsonElement>): CameraConsole {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val snackbar = LocalSnackbar.current
    val c = remember { CameraConsole(context.applicationContext) }
    c.bridge = bridge; c.makers = makers; c.toast = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    LaunchedEffect(Unit) { c.loadCaptures(); c.loadWifiCameras() }
    LaunchedEffect(c.transport, c.usbInfo, c.report, c.device, c.overrides, c.status) { c.recomputeCapabilities() }
    DisposableEffect(Unit) { onDispose { c.dispose() } }
    return c
}

private class CameraConsole(private val context: Context) {
    lateinit var bridge: BridgeState; var makers: List<JsonElement> = emptyList(); var toast: (String) -> Unit = {}
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    var prefs by mutableStateOf(jsonOf("transport" to "usb", "wifiId" to "", "burstCount" to 3, "burstIntervalSec" to 2, "transferMode" to "jpeg").let { d -> JsonObject(d + (readAll(context).objAt("prefs") as? JsonObject ?: emptyMap())) })
    var transport by mutableStateOf(if (prefs.str("transport") == "wifi") "wifi" else "usb")
    var status by mutableStateOf("idle"); var error by mutableStateOf(""); var busy by mutableStateOf("")
    var device by mutableStateOf<JsonElement?>(null); var usbInfo by mutableStateOf<JsonElement?>(null); var report by mutableStateOf<JsonElement?>(null)
    var props by mutableStateOf<List<JsonElement>>(emptyList()); var images by mutableStateOf<List<CamFile>>(emptyList())
    var overrides by mutableStateOf(JsonObject(emptyMap())); var caps by mutableStateOf<JsonElement?>(null)
    var captures by mutableStateOf<List<JsonElement>>(emptyList()); var wifiCameras by mutableStateOf<List<JsonElement>>(emptyList()); var wifiLoading by mutableStateOf(true)
    var shooting by mutableStateOf(false); var lastShot by mutableStateOf<JsonElement?>(null); var lastShotJpeg by mutableStateOf(false)
    var transferResults by mutableStateOf<List<TransferRow>>(emptyList())
    var liveFrame by mutableStateOf<ImageBitmap?>(null); var liveRunning by mutableStateOf(false); var liveError by mutableStateOf("")
    private var ptp: PtpCamera? = null; private var liveJob: Job? = null
    private var client: BridgeClient? = null; private var port = ""

    val identity get() = "$transport:${device.str("serial")?.ifBlank { null } ?: device.str("model")?.ifBlank { null } ?: device.str("port")?.ifBlank { null } ?: "none"}"
    val usbSupported get() = PtpUsbTransport.supported(context)
    fun entry(id: String) = caps.objAt("entries.$id")
    val focusRow get() = props.firstOrNull { it.str("key") == "focusMode" }
    var manualFocus by mutableStateOf(false)

    fun updatePrefs(vararg pairs: Pair<String, Any?>) { prefs = JsonObject(prefs + jsonOf(*pairs)); writeAll(context, JsonObject(readAll(context) + ("prefs" to prefs))) }
    fun mark(id: String, st: String, reason: String = "") { if (id.isNotBlank()) overrides = JsonObject(overrides + (id to jsonOf("status" to st, "reason" to reason))) }
    private fun markError(id: String, e: Throwable) = mark(id, if (UNSUPPORTED.containsMatchIn(e.message.orEmpty())) "unsupported" else "error", e.message.orEmpty())

    suspend fun recomputeCapabilities() {
        val all = readAll(context); val persisted = all.objAt("statuses.$identity") ?: (all.objAt("statuses") as? JsonObject)?.get(identity)
        val out = Logic.call("glue/camera", "capabilities", jsonOf("transport" to transport, "ptpInfo" to usbInfo.takeIf { status == "connected" }, "report" to report.takeIf { status == "connected" }, "device" to device, "persisted" to persisted, "overrides" to overrides))
        caps = out
        if (status == "connected") out.objAt("map")?.let { map -> val a = readAll(context); writeAll(context, JsonObject(a + ("statuses" to JsonObject(((a["statuses"] as? JsonObject) ?: emptyMap()) + (identity to map))))) }
        val f = focusRow
        val mf = f.bool("supported") == true && (if (transport == "usb") f.int("current") == 1 else Logic.call("glue/camera", "manualFocusText", f.str("current") ?: f.str("valueText").orEmpty()).let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" })
        if (mf && !manualFocus) toast("MF（マニュアルフォーカス）です。AFは効きません。ピントを手動で合わせてください。")
        manualFocus = mf
    }
    suspend fun loadCaptures() { runCatching { captures = Api.entityList("CameraCapture", sort = "-shot_at", limit = 24) } }
    suspend fun loadWifiCameras() { wifiLoading = true; runCatching { wifiCameras = Api.entityList("WifiCamera", sort = "-created_date", limit = 50) }; wifiLoading = false }

    fun setTransportTo(next: String) { if (next == transport) return; scope.launch { disconnect(); transport = next; updatePrefs("transport" to next); lastShot = null; transferResults = emptyList(); overrides = JsonObject(emptyMap()) } }
    private fun reset() { status = "idle"; error = ""; device = null; usbInfo = null; report = null; props = emptyList(); images = emptyList(); liveFrame = null; liveRunning = false; liveError = "" }
    suspend fun disconnect() { liveJob?.cancel(); liveJob = null; ptp?.let { p -> withContext(Dispatchers.IO) { runCatching { p.close() } } }; ptp = null; client = null; port = ""; reset() }
    fun dispose() { scope.launch { disconnect(); scope.coroutineContext[Job]?.cancel() } }

    // ---- USB（PTP） ----
    fun connectUsb() = scope.launch {
        status = "connecting"; error = ""; busy = "USBでカメラに接続しています"
        try {
            val dev = PtpUsbTransport.cameras(context).firstOrNull() ?: throw java.io.IOException("USBでつながったカメラが見つかりません。USB OTG ケーブルで接続し、カメラのUSB設定を PTP にしてください")
            val cam = PtpCamera(PtpUsbTransport.open(context, dev))
            val info = Logic.call("glue/camera", "deviceInfo", cam.openSession().toHex())
            cam.operations = info.list("operations").mapNotNull { it.text.toIntOrNull() }.toSet(); cam.properties = info.list("properties").mapNotNull { it.text.toIntOrNull() }.toSet(); cam.events = info.list("events").mapNotNull { it.text.toIntOrNull() }.toSet()
            ptp = cam; usbInfo = info
            device = jsonOf("transport" to "usb", "maker" to (info.str("manufacturer")?.ifBlank { null } ?: dev.manufacturerName.orEmpty()), "model" to (info.str("model")?.ifBlank { null } ?: dev.productName.orEmpty()), "serial" to info.str("serialNumber").orEmpty(), "firmware" to info.str("deviceVersion").orEmpty(), "port" to "USB", "label" to (dev.productName ?: info.str("model").orEmpty()))
            loadUsbProps(); status = "connected"; updatePrefs("lastUsb" to device)
        } catch (e: Throwable) { if (e is CancellationException) throw e; ptp = null; reset(); status = "error"; error = e.message ?: "カメラに接続できませんでした" } finally { busy = "" }
    }
    private suspend fun loadUsbProps() {
        val cam = ptp ?: return
        val descs = Logic.call("glue/camera", "meta").items("props").mapNotNull { def -> val code = def.int("code") ?: return@mapNotNull null; if (code !in cam.properties) null else try { jsonOf("code" to code, "hex" to cam.propDesc(code).toHex()) } catch (e: Throwable) { if (e is CancellationException) throw e; jsonOf("code" to code, "error" to (e.message ?: "読み取れませんでした")) } }
        props = Logic.call("glue/camera", "usbPropRows", JsonArray(descs)).items()
    }

    // ---- Wi-Fi（ブリッジ） ----
    fun connectWifi(camera: JsonElement) = scope.launch {
        val cl = bridge.client
        if (!cl.configured) { status = "error"; error = "ブリッジの接続先が未設定です。Wi-Fiの設定でURLを入力してください"; return@launch }
        client = cl; port = portSpecOf(camera, makers); status = "connecting"; error = ""; busy = "ブリッジ経由でカメラに接続しています"
        try {
            var info = try { cl.status(port) } catch (e: Throwable) { if (e is CancellationException) throw e; val list = cl.listFiles(port)
                jsonOf("model" to list.model.ifBlank { camera.str("model").orEmpty() }, "serial" to "", "firmware" to list.firmware, "settings" to emptyList<String>(), "legacy" to true, "capabilities" to jsonOf("files" to true, "config" to false, "capture" to false, "liveview" to false)) }
            val rows = if (info.bool("capabilities.config") == true) runCatching { Logic.call("glue/camera", "wifiRows", JsonArray(cl.config(port))).items() }.getOrDefault(emptyList()) else emptyList()
            if (info.list("settings").isEmpty() && rows.isNotEmpty()) info = JsonObject((info as JsonObject) + ("settings" to JsonArray(rows.mapNotNull { r -> Logic.call("glue/camera", "wifiSettingName", r.str("key").orEmpty()).text.ifBlank { null }?.let { kotlinx.serialization.json.JsonPrimitive(it) } })))
            report = info; props = rows
            device = jsonOf("transport" to "wifi", "maker" to camera.str("maker").orEmpty(), "label" to camera.str("label").orEmpty(), "model" to (info.str("model")?.ifBlank { null } ?: camera.str("model")?.ifBlank { null } ?: camera.str("label").orEmpty()), "serial" to info.str("serial").orEmpty(),
                "firmware" to info.str("firmware").orEmpty(), "port" to (camera.str("host")?.ifBlank { null } ?: camera.str("port_spec")?.ifBlank { null } ?: port), "legacy" to (info.bool("legacy") == true))
            status = "connected"; camera.str("id")?.let { updatePrefs("wifiId" to it) }
        } catch (e: Throwable) { if (e is CancellationException) throw e; reset(); status = "error"; error = e.message ?: "カメラに接続できませんでした" } finally { busy = "" }
    }

    fun refreshSettings() = scope.launch { busy = "設定を再取得しています"; try { if (transport == "usb") loadUsbProps() else client?.let { props = Logic.call("glue/camera", "wifiRows", JsonArray(it.config(port))).items() } } catch (e: Throwable) { if (e is CancellationException) throw e; toast(e.message ?: "再取得できませんでした") } finally { busy = "" } }

    suspend fun changeSetting(row: JsonElement, value: String): Boolean {
        val key = row.str("key").orEmpty(); val label = row.str("label").orEmpty()
        busy = "$label を変更しています"
        val (ok, message) = try {
            if (row.bool("writable") != true) false to "この項目はカメラが変更を受け付けません"
            else if (transport == "usb") {
                val cam = ptp ?: throw java.io.IOException("カメラが接続されていません"); val code = row.int("code") ?: 0; val type = row.int("dataType") ?: 0
                cam.setProp(code, Logic.call("glue/camera", "encodeValue", type, value.toLongOrNull() ?: 0L).text.hexToBytes()); loadUsbProps()
                if (props.firstOrNull { it.str("key") == key }.str("current") == value) true to "$label を変更し、再取得で反映を確認しました" else false to "$label は値を受け付けましたが、再取得した値が一致しません"
            } else {
                val cl = client ?: throw java.io.IOException("カメラが接続されていません")
                val after = cl.setConfig(port, Logic.call("glue/camera", "wifiSettingName", key).text, value).objAt("config"); props = Logic.call("glue/camera", "wifiRows", JsonArray(cl.config(port))).items()
                if (after.str("current") == null || after.str("current") == value) true to "$label を変更し、再取得で反映を確認しました" else false to "$label は値を受け付けましたが、再取得した値が一致しません"
            }
        } catch (e: Throwable) { if (e is CancellationException) throw e; false to (e.message ?: "変更できませんでした") } finally { busy = "" }
        mark(Logic.call("glue/camera", "featureForSetting", key).text, if (ok) "supported" else "error", if (ok) "変更して反映を確認しました" else message); toast(message)
        return ok
    }

    private suspend fun captureOnce() {
        val name: String; val bytes: ByteArray; var width: Int? = null; var height: Int? = null
        if (transport == "usb") {
            val cam = ptp ?: throw java.io.IOException("カメラが接続されていません")
            val handle = cam.capture { busy = it }
            busy = "画像情報を取得しています"; val info = Logic.call("glue/camera", "objectInfo", cam.objectInfo(handle).toHex())
            busy = "画像を取り込んでいます"; bytes = cam.download(handle, info.long("compressedSize") ?: 0); name = info.str("filename")?.ifBlank { null } ?: "capture-$handle.jpg"; width = info.int("imageWidth"); height = info.int("imageHeight")
        } else {
            val cl = client ?: throw java.io.IOException("カメラが接続されていません")
            busy = "撮影前のファイル一覧を確認しています"; val known = cl.listFiles(port).files.map { it.name }.toSet()
            busy = "シャッターを切っています"; cl.capture(port)
            busy = "保存された画像を確認しています"; var fresh: CamFile? = null; var tries = 0
            while (fresh == null && tries < 8) { tries++; delay(700); fresh = cl.listFiles(port).files.filter { it.name !in known }.maxByOrNull { it.index } }
            val f = fresh ?: throw java.io.IOException("撮影は実行されましたが、新しいファイルを確認できませんでした")
            busy = "画像を取り込んでいます"; val r = cl.download(f, context.cacheDir) {}; bytes = withContext(Dispatchers.IO) { r.file.readBytes().also { r.file.delete() } }; name = f.name
        }
        val jpeg = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte(); val mime = if (jpeg) "image/jpeg" else "application/octet-stream"
        busy = "画像を保存しています"
        val uri = Api.uploadPrivateFile(name, mime, bytes)
        lastShot = Api.entityCreate("CameraCapture", jsonOf("device_model" to device.str("model").orEmpty(), "device_serial" to device.str("serial").orEmpty(), "filename" to name, "shot_at" to System.currentTimeMillis(), "captured_at" to "", "file_uri" to uri, "mime" to mime, "bytes" to bytes.size,
            "width" to width, "height" to height, "exif" to "{}", "settings" to JsonObject(props.filter { !it.str("raw").isNullOrBlank() }.associate { it.str("key").orEmpty() to (it as JsonObject)["raw"]!! }).toString()))
        lastShotJpeg = jpeg
    }
    fun capture() = scope.launch {
        val count = maxOf(1, prefs.int("burstCount") ?: 1); val interval = maxOf(0, prefs.int("burstIntervalSec") ?: 0); var done = 0
        shooting = true
        try {
            repeat(count) { i -> if (i > 0 && interval > 0) { busy = "連続撮影しています"; delay(interval * 1000L) }; captureOnce(); done++; mark("shutterRelease", "supported", "撮影して取り込みました"); mark("captureReview", "supported", "撮影した画像を確認できます") }
            loadCaptures(); toast(if (count > 1) "${done}枚を撮影して取り込みました" else "撮影して取り込みました")
        } catch (e: Throwable) { if (e is CancellationException) throw e; markError("shutterRelease", e); toast(e.message ?: "撮影できませんでした") } finally { shooting = false; busy = "" }
    }
    fun focusDrive() = scope.launch {
        val cam = ptp; if (transport != "usb" || cam == null) { toast("この接続方法ではAFを作動できません（Wi-Fi経由は未対応です）"); return@launch }
        try { cam.afDrive(); mark("focusDrive", "supported", "AFを作動させました"); toast("AFを作動させました") } catch (e: Throwable) { if (e is CancellationException) throw e; markError("focusDrive", e); toast(e.message ?: "AFを作動できませんでした") }
    }
    fun startLiveView() { val cam = ptp ?: return; liveJob?.cancel(); liveError = ""
        liveJob = scope.launch {
            try { cam.startLiveView(); liveRunning = true; mark("liveView", "supported", "映像を取得しました")
                while (isActive) { val frame = cam.liveFrame(); if (frame != null) withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(frame, 0, frame.size) }?.let { liveFrame = it.asImageBitmap() }; delay(120) } }
            catch (e: Throwable) { if (e is CancellationException) throw e; liveError = e.message ?: "ライブビューを開始できませんでした"; mark("liveView", "error", liveError) } finally { liveRunning = false }
        }
    }
    fun stopLiveView() { liveJob?.cancel(); liveJob = null; scope.launch { ptp?.stopLiveView(); liveRunning = false } }

    fun listImages() = scope.launch {
        busy = "カメラ内の画像一覧を取得しています"
        try {
            images = if (transport == "usb") { val cam = ptp ?: return@launch
                cam.listHandles().takeLast(300).reversed().mapIndexedNotNull { i, h -> val info = runCatching { Logic.call("glue/camera", "objectInfo", cam.objectInfo(h).toHex()) }.getOrNull() ?: return@mapIndexedNotNull null
                    if (info.int("objectFormat") == 0x3001) return@mapIndexedNotNull null
                    val name = info.str("filename").orEmpty(); val ext = name.substringAfterLast('.', "").uppercase(); val isJpeg = ext in setOf("JPG", "JPEG"); val isRaw = !isJpeg && ext in setOf("NEF", "NRW", "CR2", "CR3", "ARW", "RAF", "RW2", "ORF", "PEF", "DNG")
                    CamFile("$h", i + 1, name, "", ext.ifBlank { "—" }, if (isRaw) "RAW" else if (isJpeg) "JPEG" else "その他", isRaw, isJpeg, info.long("compressedSize"), true, info.long("captureDate")?.toString(), "", name.substringBeforeLast('.').uppercase(), "usb") }
            } else client?.listFiles(port)?.files.orEmpty()
            mark("fileList", "supported", "${images.size}件のファイルを取得しました")
        } catch (e: Throwable) { if (e is CancellationException) throw e; markError("fileList", e); toast(e.message ?: "一覧を取得できませんでした") } finally { busy = "" }
    }
    suspend fun transfer(entries: List<Pair<CamFile, String>>): List<TransferRow> {
        val out = mutableListOf<TransferRow>()
        for ((item, role) in entries) {
            try {
                if (transport == "usb") { val cam = ptp ?: throw java.io.IOException("カメラが接続されていません"); busy = "${item.name} を取り込んでいます"
                    val bytes = cam.download(item.id.toInt(), item.size ?: 0); Export.save(context, item.name, "application/octet-stream", bytes)
                    out += TransferRow(item.name, role, true, bytes.size.toLong(), withContext(Dispatchers.Default) { sha256(bytes) }, item.size?.let { it == bytes.size.toLong() })
                } else { val cl = client ?: throw java.io.IOException("カメラが接続されていません"); busy = "${item.name} を取り込んでいます"
                    val r = cl.download(item, context.cacheDir) {}; Export.saveStream(context, item.name, "application/octet-stream") { o -> r.file.inputStream().use { it.copyTo(o) } }; r.file.delete()
                    out += TransferRow(item.name, role, true, r.bytes, r.sha256, r.sizeMatch, r.resumeUsed) }
                mark(if (item.isRaw) "rawTransfer" else "jpegTransfer", "supported", "${item.name} をバイト列のまま取得しました")
                if (item.isRaw || role == "raw+jpeg") mark("rawJpegTransfer", "supported", "RAWとJPEGをまとめて取得しました")
            } catch (e: Throwable) { if (e is CancellationException) throw e; out += TransferRow(item.name, role, false, error = e.message ?: "取得できませんでした"); markError(if (item.isRaw) "rawTransfer" else "jpegTransfer", e) }
        }
        busy = ""; transferResults = out; return out
    }
}

/** カメラ統合コンソール（Web: camera.jsx + components/camera/unified の各カード） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraConsoleScreen() {
    val nav = LocalNav.current; val scope = rememberCoroutineScope()
    val bridge = rememberBridge(); val makers = rememberMakers(); val cam = rememberConsole(bridge, makers)
    val meta = produceState<JsonElement?>(null) { value = Logic.call("glue/camera", "meta") }.value
    val connected = cam.status == "connected"; val connecting = cam.status == "connecting"
    ScreenList {
        item {
            Heading("カメラ統合コンソール", "USB接続とWi-Fi接続を同じ画面・同じ操作で扱います。カメラが対応を報告した機能だけを使い、未確認の機能は「未検証」、対応していない機能は「非対応」と表示します。")
            Muted("USB: USB OTG でカメラを接続し、カメラ側を PTP に設定　／　Wi-Fi: PC・端末でブリッジを起動", 10)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("usb", "USB接続", "USB Host / PTP（直接）"), Triple("wifi", "Wi-Fi接続", "ローカルブリッジ経由")).forEach { (id, label, note) ->
                    val selected = cam.transport == id; val st = if (selected) cam.status else "idle"
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (selected) Brand.Orange.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface).clickable { cam.setTransportTo(id) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Icon(if (id == "usb") Icons.Outlined.Usb else Icons.Outlined.Wifi, null, Modifier.size(20.dp))
                        Column { Row(verticalAlignment = Alignment.CenterVertically) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text(" ●", fontSize = 9.sp, color = statusColor(st)) }; Muted("$note・${statusText(st)}", 10) }
                    }
                }
            }
        }
        item { CamBridgeLauncher() }
        if (cam.manualFocus) item { Panel { Text("⚠ MF（マニュアルフォーカス）です", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Brand.Warning); Muted("AFは作動しません。ピントは手動で合わせてください。${cam.focusRow.str("valueText")?.let { "（カメラの現在値: $it）" } ?: ""}", 11) } }
        if (cam.busy.isNotBlank()) item { Muted("⏳ ${cam.busy}", 11) }
        item {
            Panel(title = "接続", subtitle = if (cam.transport == "usb") "USB（USB Host API）でカメラの PTP 通信口に直接つなぎます。" else "PC・端末で動くブリッジ経由でカメラにつなぎます。", action = { Tag(statusText(cam.status), statusColor(cam.status)) }) {
                if (cam.transport == "usb") {
                    if (!cam.usbSupported) Text("この端末は USB ホスト機能に対応していません。USB OTG 対応端末と変換アダプタを使ってください。", fontSize = 11.sp, color = Brand.Warning)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton(if (connecting) "接続中…" else if (connected) "再接続" else "カメラに接続", { scope.launch { cam.disconnect(); cam.connectUsb() } }, enabled = !connecting && cam.usbSupported, icon = Icons.Outlined.Usb, primary = !connected)
                        if (connected) SmallButton("切断", { scope.launch { cam.disconnect() } })
                    }
                    cam.prefs.objAt("lastUsb")?.takeIf { !connected }?.let { Muted("前回のカメラ: ${it.str("label")?.ifBlank { null } ?: it.str("model").orEmpty()}（USB）。登録内容は保持されています。", 10) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag("ブリッジ ${when (bridge.health) { "ok" -> "接続OK"; "error" -> "応答なし"; "checking" -> "確認中"; else -> "未設定" }}", when (bridge.health) { "ok" -> Brand.Success; "error" -> Brand.Destructive; else -> Brand.MutedFg })
                        Text(bridge.url, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1); SmallButton("接続先の設定", { nav.go("/wifi/setup") })
                    }
                    if (bridge.error.isNotBlank()) Text(bridge.error, fontSize = 10.sp, color = Brand.Destructive)
                    if (cam.wifiLoading) Muted("登録済みのカメラを読み込んでいます…", 11)
                    else if (cam.wifiCameras.isEmpty()) { Muted("登録済みのWi-Fiカメラがありません。", 11); SmallButton("Wi-Fiカメラを登録する", { nav.go("/wifi") }) }
                    else cam.wifiCameras.forEach { camera ->
                        val active = connected && cam.device.str("label") == camera.str("label")
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(camera.str("label").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted(listOfNotNull(camera.str("maker"), camera.str("model"), camera.str("host")?.ifBlank { null } ?: camera.str("port_spec")).filter { it.isNotBlank() }.joinToString(" / ") + if (camera.str("last_status") == "error") "・前回エラー" else "", 10) }
                            if (active) SmallButton("切断", { scope.launch { cam.disconnect() } }) else SmallButton(if (connecting) "接続中…" else "接続", { cam.connectWifi(camera) }, enabled = !connecting, icon = Icons.Outlined.Wifi)
                        }
                    }
                    if (cam.report.bool("legacy") == true) Muted("旧ブリッジのため、一覧と転送だけ利用できます。ブリッジを更新してください。", 10)
                }
                if (cam.error.isNotBlank()) Text(cam.error, fontSize = 11.sp, color = Brand.Destructive)
                cam.device?.let { d -> KeyValue("メーカー", d.str("maker")); KeyValue("機種", d.str("model")); KeyValue("接続方法", if (cam.transport == "usb") "USB（USB Host）" else "Wi-Fi（ブリッジ）"); KeyValue("シリアル", d.str("serial")); KeyValue("ファームウェア", d.str("firmware")); KeyValue("ポート・接続先", d.str("port")) }
            }
        }
        item {
            val summary = cam.caps.objAt("summary")
            Panel(title = "機能の対応状況", subtitle = if (cam.transport == "usb") "判定の根拠: カメラの GetDeviceInfo（対応操作・対応プロパティ）" else "判定の根拠: ブリッジが取得できた設定と操作の可否") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { meta.list("statusIds").forEach { id -> Tag("${meta.str("status.${id.text}.label")} ${summary.int(id.text) ?: 0}", toneOf(id.text)) } }
                meta.items("groups").forEach { g ->
                    Text(g.str("label").orEmpty(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg, modifier = Modifier.padding(top = 6.dp))
                    meta.items("features").filter { it.str("group") == g.str("id") }.forEach { f ->
                        val e = cam.entry(f.str("id").orEmpty())
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(f.str("label").orEmpty(), fontSize = 12.sp); Muted(e.str("reason")?.ifBlank { null } ?: f.str("note").orEmpty(), 10) }; Tag(meta.str("status.${e.str("status") ?: "unverified"}.label") ?: "未検証", toneOf(e.str("status"))) }
                    }
                }
                Muted("「対応」はカメラまたはブリッジが実際に返した内容で確認できた機能だけです。接続方法を切り替えても、最後に確認した状態を機種ごとに保持します。", 10)
            }
        }
        if (cam.transport == "wifi") item { WifiNetworkSection(cam, bridge, makers) }
        item {
            var pending by remember { mutableStateOf("") }
            Panel(title = "カメラ設定", subtitle = "取得・変更とも、カメラ（またはブリッジ）が返した値だけを扱います。", action = { SmallButton("再取得", { cam.refreshSettings() }, enabled = connected, icon = Icons.Outlined.Refresh) }) {
                if (!connected) Muted("カメラを接続すると設定を読み取ります。", 11)
                else if (cam.props.isEmpty()) Muted("この接続方法では設定を取得できませんでした（機種が公開していないか、ブリッジが未対応です）。", 11)
                else cam.props.forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Text(row.str("label").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold); if (row.str("key") == "focusMode" && cam.manualFocus) Tag("MF", Brand.Warning) }
                            Muted(if (row.bool("supported") == true) row.str("unit").orEmpty() else row.str("error")?.ifBlank { null } ?: "このカメラは対応を報告していません", 10)
                            Text("${row.str("valueText")}${row.str("raw")?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}", fontSize = 12.sp)
                        }
                        val options = row.items("options")
                        if (row.bool("writable") == true && options.isNotEmpty()) SelectField(options.map { it.str("value").orEmpty() to it.str("text").orEmpty() }, row.str("current").orEmpty(), { v -> pending = row.str("key").orEmpty(); scope.launch { cam.changeSetting(row, v); pending = "" } })
                        if (pending == row.str("key")) Muted(" …", 11)
                    }
                }
                Muted("変更後は必ず読み直し、実際に反映されたかを確認します。反映されない場合は「一致しません」と表示します。", 10)
            }
        }
        item {
            val shot = cam.entry("shutterRelease"); val burst = (cam.prefs.int("burstCount") ?: 1) > 1; val record = cam.lastShot
            Panel(title = "撮影", subtitle = "リモートシャッター・連写・撮影後の確認。撮影した画像は非公開ストレージに保存します。") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    SmallButton("AF作動", { cam.focusDrive() }, enabled = connected && !cam.shooting, icon = Icons.Outlined.CenterFocusStrong)
                    SelectField(listOf(1, 2, 3, 5, 10).map { "$it" to "${it}枚" }, "${cam.prefs.int("burstCount") ?: 1}", { cam.updatePrefs("burstCount" to it.toInt()) }, label = "撮影枚数")
                    SelectField(listOf(0, 1, 2, 3, 5, 10).map { "$it" to "${it}秒" }, "${cam.prefs.int("burstIntervalSec") ?: 0}", { cam.updatePrefs("burstIntervalSec" to it.toInt()) }, label = "間隔")
                    SmallButton(if (cam.shooting) "撮影中…" else if (burst) "連写撮影" else "シャッター", { cam.capture() }, enabled = connected && !cam.shooting, icon = Icons.Outlined.CameraAlt, primary = true)
                }
                if (shot.str("status") != "supported") shot.str("reason")?.let { Muted(it, 10) }
                if (cam.manualFocus) Text("MF（マニュアルフォーカス）です。ピントは手動で合わせてください。", fontSize = 11.sp, color = Brand.Warning)
                if (record != null) {
                    val url = produceState("", record.str("file_uri")) { value = record.str("file_uri")?.let { runCatching { Api.signedUrl(it) }.getOrNull() }.orEmpty() }.value
                    if (cam.lastShotJpeg && url.isNotBlank()) AsyncImage(url, record.str("filename") ?: "撮影した画像", Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth)
                    Muted("${record.str("filename")}　${if (cam.lastShotJpeg) "JPEG" else "RAW（バイト列そのまま）"}・${(record.long("bytes") ?: 0) / 1024}KB", 11)
                    SaveToCatalog(record, !cam.lastShotJpeg) { scope.launch { cam.loadCaptures() } }
                }
            }
        }
        item {
            val e = cam.entry("liveView"); val available = e.str("status") == "supported" && connected && cam.transport == "usb"
            Panel(title = "ライブビュー", subtitle = if (available) "カメラが返した映像を表示します。" else e.str("reason")?.ifBlank { null } ?: "未確認です。", action = { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { SmallButton("開始", { cam.startLiveView() }, enabled = connected && cam.transport == "usb" && !cam.liveRunning, icon = Icons.Outlined.PlayArrow); SmallButton("停止", { cam.stopLiveView() }, enabled = cam.liveRunning, icon = Icons.Outlined.Stop) } }) {
                val frame = cam.liveFrame
                if (frame != null) Image(frame, "ライブビュー", Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth)
                else Box(Modifier.fillMaxWidth().aspectRatio(3f / 2f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Muted(if (available || (connected && cam.transport == "usb")) (if (cam.liveRunning) "映像を待っています…" else "「開始」を押すとカメラの映像を表示します。") else e.str("reason")?.ifBlank { null } ?: "この接続方法ではライブビューを利用できません。", 11) }
                if (cam.liveError.isNotBlank()) Text(cam.liveError, fontSize = 11.sp, color = Brand.Destructive)
            }
        }
        item {
            var selected by remember { mutableStateOf(setOf<String>()) }; var mode by remember { mutableStateOf(cam.prefs.str("transferMode") ?: "jpeg") }; var busy by remember { mutableStateOf(false) }
            val files = cam.images; val chosen = files.filter { it.id in selected }
            Panel(title = "カメラ内の画像と転送", subtitle = "JPEG・RAW・RAW+JPEGを、元のファイル名とバイト列のまま取得します。", action = { SmallButton("一覧取得", { selected = emptySet(); cam.listImages() }, enabled = connected, icon = Icons.Outlined.Image) }) {
                ChipRow(listOf("jpeg" to "JPEGのみ", "raw" to "RAWのみ", "raw+jpeg" to "RAW+JPEG"), mode, { mode = it })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Muted("選択 ${chosen.size} 件", 11)
                    SmallButton(if (busy) "転送中…" else "転送", {
                        val entries = buildList { val seen = mutableSetOf<String>(); fun add(f: CamFile?, role: String) { if (f != null && seen.add(f.id)) add(f to role) }
                            chosen.forEach { f -> when { mode == "jpeg" && f.isJpeg -> add(f, "jpeg"); mode == "raw" && f.isRaw -> add(f, "raw"); mode == "raw+jpeg" && f.isRaw -> { add(f, "raw+jpeg"); add(chosen.firstOrNull { it.isJpeg && it.pairKey == f.pairKey }, "raw+jpeg") }; mode == "raw+jpeg" && f.isJpeg -> add(f, "jpeg") } } }
                        if (entries.isEmpty()) { cam.toast("選択したファイルに、この転送方法で取得できるものがありません"); return@SmallButton }
                        busy = true; cam.updatePrefs("transferMode" to mode)
                        scope.launch { try { val r = cam.transfer(entries); val ok = r.count { it.ok }; cam.toast(if (ok == r.size) "${ok}件を元のファイル名のまま保存しました（RAWは変換していません）" else "${ok}件を保存、${r.size - ok}件は取得できませんでした") } finally { busy = false } }
                    }, enabled = connected && chosen.isNotEmpty() && !busy, icon = Icons.Outlined.Download, primary = true)
                }
                if (mode == "raw" && cam.entry("rawTransfer").str("status") != "supported") cam.entry("rawTransfer").str("reason")?.let { Muted(it, 10) }
                if (files.isEmpty()) Muted(if (cam.entry("fileList").str("status") == "supported" || connected) "「一覧取得」でカメラ内の画像を読み取ります。" else cam.entry("fileList").str("reason")?.ifBlank { null } ?: "この接続方法では一覧を取得できません。", 11)
                else {
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(selected.size == files.size, { on -> selected = if (on) files.map { it.id }.toSet() else emptySet() }); Muted("すべて選択（${files.size}件）", 11) }
                    files.take(200).forEach { f ->
                        Row(Modifier.fillMaxWidth().clickable { selected = if (f.id in selected) selected - f.id else selected + f.id }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(f.id in selected, { selected = if (f.id in selected) selected - f.id else selected + f.id })
                            Text(f.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1)
                            Muted("${if (f.isRaw) "RAW" else if (f.isJpeg) "JPEG" else f.format}　${f.size?.let { "%.1fMB".format(it / 1048576.0) } ?: "サイズ不明"}　${f.capturedAt?.toLongOrNull()?.let { BusFormat.dateTime(it) } ?: f.capturedAt?.replace("T", " ")?.take(16) ?: "—"}", 10)
                        }
                    }
                }
                cam.transferResults.forEach { r ->
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Text(r.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1); Tag(if (r.ok) "取得済み" else "失敗", if (r.ok) Brand.Success else Brand.Destructive); Muted(if (r.ok) (if (r.bytes >= 1048576) "%.1fMB".format(r.bytes / 1048576.0) else "${r.bytes / 1024}KB") else r.error, 10) }
                        if (r.ok) Text("SHA-256 ${r.sha256}${when (r.sizeMatch) { true -> "・サイズ一致"; false -> "・サイズ不一致"; else -> "・サイズ未確認" }}${if (r.resumeUsed) "・再開して取得" else ""}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Brand.MutedFg)
                    }
                }
                Muted("RAWは変換・再圧縮・メタデータ削除を行いません。取得後にサイズ照合とSHA-256を表示します（比較できない項目は「一致」にしません）。保存先は端末の「ダウンロード」です。", 10)
            }
        }
        item {
            Panel(title = "撮影履歴（${cam.captures.size} 件）", subtitle = "このアカウントで取り込んだ写真です。") {
                if (cam.captures.isEmpty()) Muted("まだ履歴はありません。", 11)
                cam.captures.chunked(3).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                        rowItems.forEach { row ->
                            Column(Modifier.weight(1f)) {
                                val url = produceState("", row.str("file_uri")) { value = row.str("file_uri")?.takeIf { row.str("mime") == "image/jpeg" }?.let { runCatching { Api.signedUrl(it) }.getOrNull() }.orEmpty() }.value
                                Box(Modifier.fillMaxWidth().aspectRatio(3f / 2f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) { if (url.isNotBlank()) AsyncImage(url, row.str("filename") ?: "撮影した写真", Modifier.fillMaxWidth().aspectRatio(3f / 2f), contentScale = ContentScale.Crop) }
                                Text(row.str("filename") ?: "—", fontSize = 10.sp, maxLines = 1); Muted(BusFormat.dateTime(row.long("shot_at")), 9); Muted(row.str("vehicle_number")?.takeIf { it.isNotBlank() }?.let { "図鑑登録：$it" } ?: "図鑑未登録", 9)
                            }
                        }
                        repeat(3 - rowItems.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** 取り込んだ写真を車両図鑑へ登録する（Web: CameraSaveToCatalog.jsx）。 */
@Composable
private fun SaveToCatalog(record: JsonElement, disabled: Boolean, onRegistered: () -> Unit) {
    val scope = rememberCoroutineScope(); val snackbar = LocalSnackbar.current
    var word by remember { mutableStateOf("") }; var results by remember { mutableStateOf<List<JsonElement>>(emptyList()) }; var picked by remember { mutableStateOf("") }; var type by remember { mutableStateOf("side") }; var busy by remember { mutableStateOf(false) }
    Text("車両図鑑へ登録${record.str("vehicle_number")?.takeIf { it.isNotBlank() }?.let { "（登録済み：$it）" } ?: ""}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    if (disabled) { Muted("JPEG 以外の形式（RAW など）は登録できません。カメラの画質設定を JPEG にして撮影してください。", 10); return }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(word, { word = it }, placeholder = { Text("車番の一部（例：奈良200か30）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
        SmallButton("検索", { busy = true; scope.launch { try { results = Api.rpc("camera/fns#searchCameraVehicles", jsonOf("q" to word.trim())).items(); if (results.isEmpty()) snackbar.showSnackbar("該当する車番が車両図鑑にありません") } catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "車両を検索できませんでした" }) } finally { busy = false } } }, enabled = !busy && word.isNotBlank(), icon = Icons.Outlined.Search)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (results.isNotEmpty()) SelectField(listOf("" to "車両を選択（${results.size} 件）") + results.map { it.str("id").orEmpty() to listOfNotNull(it.str("vehicle_number"), it.str("garage")?.ifBlank { null }, it.str("vehicle_type")?.ifBlank { null }).joinToString("／") }, picked, { picked = it })
        SelectField(listOf("front" to "前面", "side" to "側面", "rear" to "後部", "interior" to "車内", "other" to "その他"), type, { type = it })
        SmallButton("登録", {
            val v = results.firstOrNull { it.str("id") == picked } ?: return@SmallButton
            busy = true
            scope.launch {
                try {
                    val day = record.str("captured_at")?.take(10)?.ifBlank { null } ?: BusFormat.dayKey(record.long("shot_at") ?: System.currentTimeMillis())
                    Api.rpc("catalog/fns#addCatalogPhoto", jsonOf("vehicleId" to picked, "fileUri" to record.str("file_uri"), "imageType" to type, "description" to "${record.str("device_model")?.ifBlank { null } ?: "カメラ"} から取り込み", "photographedAt" to day))
                    Api.entityUpdate("CameraCapture", record.str("id").orEmpty(), jsonOf("vehicle_id" to picked, "vehicle_number" to v.str("vehicle_number"), "catalog_registered_at" to System.currentTimeMillis()))
                    snackbar.showSnackbar("${v.str("vehicle_number")} の写真として車両図鑑に登録しました"); results = emptyList(); word = ""; picked = ""; onRegistered()
                } catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "車両図鑑に登録できませんでした" }) } finally { busy = false }
            }
        }, enabled = picked.isNotBlank() && !busy, primary = true)
    }
}

/** ネットワーク情報・カメラIPの検出と接続テスト（Web: WifiNetworkSection + NetworkInfoCard + CameraIpCard + useWifiNetwork.js） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WifiNetworkSection(cam: CameraConsole, bridge: BridgeState, makers: List<JsonElement>) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val nav = LocalNav.current
    val camera = cam.wifiCameras.firstOrNull { it.str("id") == cam.prefs.str("wifiId") } ?: cam.wifiCameras.firstOrNull()
    val cameraId = camera.str("id") ?: "__manual"
    var net by remember { mutableStateOf<JsonElement?>(null) }; var netState by remember { mutableStateOf("idle") }; var netError by remember { mutableStateOf("") }
    var manualIp by remember(cameraId) { mutableStateOf(store(context).getString("camera-net-v1", null)?.let { runCatching { Json.parse(it).str(cameraId) }.getOrNull() }.orEmpty()) }
    var draft by remember(manualIp) { mutableStateOf(manualIp) }
    var detectState by remember { mutableStateOf("idle") }; var detectMsg by remember { mutableStateOf("") }; var results by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var probeState by remember { mutableStateOf("idle") }; var probeMsg by remember { mutableStateOf("") }; var lastAt by remember { mutableStateOf(0L) }
    fun saveManual(v: String) { manualIp = v.trim(); val all = store(context).getString("camera-net-v1", null)?.let { runCatching { Json.parse(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        store(context).edit().putString("camera-net-v1", JsonObject(if (manualIp.isBlank()) all - cameraId else all + (cameraId to kotlinx.serialization.json.JsonPrimitive(manualIp))).toString()).apply(); detectState = "idle"; results = emptyList(); probeState = "idle" }
    suspend fun refresh() {
        if (!bridge.client.configured) { netState = "error"; netError = "ブリッジのURLが未設定です。「接続先の設定」から入力してください。"; return }
        if (netState != "ok") netState = "loading"
        try { val next = Logic.call("glue/camera", "netinfo", bridge.client.netinfo()); if (net.str("phoneIp") != null && next.str("phoneIp") != net.str("phoneIp")) { detectState = "idle"; results = emptyList(); detectMsg = "接続先が変わったため、検出結果を破棄しました"; probeState = "idle" }; net = next; netState = "ok"; netError = ""; lastAt = System.currentTimeMillis() }
        catch (e: Throwable) { if (e is CancellationException) throw e; netState = if ((e as? BridgeError)?.kind == "unsupported") "unsupported" else "error"; netError = e.message ?: "ネットワーク情報を取得できませんでした" }
    }
    LaunchedEffect(bridge.client) { while (true) { refresh(); delay(10000) } }
    val detected = results.firstOrNull { it.bool("camera") == true && it.str("ip") != net.str("phoneIp") }
    val cameraIp = detected.str("ip") ?: manualIp; val ipSource = if (detected != null) "auto" else if (manualIp.isNotBlank()) "manual" else "none"
    fun portFor(ip: String) = if (camera != null) portSpecOf(JsonObject((camera as JsonObject) + jsonOf("host" to ip, "port_spec" to "")), makers) else ""
    fun autoDetect() = scope.launch {
        detectState = "running"; detectMsg = ""; results = emptyList()
        val cands = Logic.call("glue/camera", "candidates", jsonOf("phoneIp" to net.str("phoneIp"), "gateway" to net.str("gateway"), "extra" to (if (manualIp.isNotBlank()) listOf(jsonOf("ip" to manualIp)) else emptyList<JsonElement>()))).items().mapNotNull { it.str("ip") }
        if (cands.isEmpty()) { detectState = "empty"; detectMsg = "検出対象がありません。Wi-Fi情報を再取得するか、カメラのIPを手動で入力してください。"; return@launch }
        try { val data = bridge.client.discover(camera.str("protocol") ?: "ptpip", cands); results = data.items("results").filter { it.str("ip") != net.str("phoneIp") }; detectState = if (results.any { it.bool("camera") == true }) "found" else "none"; detectMsg = data.str("reason").orEmpty(); lastAt = System.currentTimeMillis() }
        catch (e: Throwable) { if (e is CancellationException) throw e; detectState = if ((e as? BridgeError)?.kind == "unsupported") "unsupported" else "error"; detectMsg = e.message ?: "自動検出できませんでした" }
    }
    fun test(ip: String) = scope.launch {
        val target = ip.trim(); val problem = Logic.call("glue/camera", "ipProblem", target, net.str("phoneIp").orEmpty()).text
        if (problem.isNotBlank()) { probeState = "error"; probeMsg = problem; return@launch }
        if (camera == null) { probeState = "error"; probeMsg = "先にWi-Fiカメラを登録してください（機種が決まらないと通信方式を選べません）"; return@launch }
        probeState = "running"; probeMsg = ""
        try { val res = bridge.client.probe(portFor(target)); probeState = "ok"; probeMsg = res.str("model")?.takeIf { it.isNotBlank() }?.let { "応答あり（$it）" } ?: "応答あり"; lastAt = System.currentTimeMillis() }
        catch (e: Throwable) { if (e is CancellationException) throw e; probeState = if ((e as? BridgeError)?.kind == "unsupported") "unsupported" else "error"; probeMsg = e.message ?: "カメラから応答がありません" }
    }
    val busy = netState == "loading" || detectState == "running" || probeState == "running"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(title = "ネットワーク情報", subtitle = "ブリッジを動かしている端末が取得した内容です。端末のIPとカメラのIPは別の値です。", action = { Tag("Wi-Fi情報 ${when (netState) { "loading" -> "取得中"; "ok" -> "取得済み"; "error" -> "取得できません"; "unsupported" -> "ブリッジ未対応"; else -> "未取得" }}", when (netState) { "ok" -> Brand.Success; "loading" -> Brand.Warning; "idle" -> Brand.MutedFg; else -> Brand.Destructive }) }) {
            val raw = cam.entry("rawTransfer").str("status")
            KeyValue("接続方式", "Wi-Fi（選択中）"); KeyValue("端末のIP", net.str("phoneIp")?.let { "$it（自動取得）${net.str("iface")?.takeIf { i -> i.isNotBlank() }?.let { i -> "・$i" } ?: ""}" })
            KeyValue("カメラのIP", cameraIp.takeIf { it.isNotBlank() }?.let { "$it（${if (ipSource == "auto") "自動検出" else "手動入力"}）" }); KeyValue("ゲートウェイ", net.str("gateway")); KeyValue("サブネット", net.str("netmask")?.let { "$it${net.int("prefix")?.let { p -> " /$p" } ?: ""}" })
            KeyValue("Wi-Fi接続状態", net.str("ssid")?.ifBlank { null } ?: if (net.bool("ok") == true) "接続中${if (net.bool("ssidSupported") == false) "（SSIDは取得不可）" else ""}" else null)
            KeyValue("カメラ検出状態", "${when (detectState) { "running" -> "検出中"; "found" -> "検出済み"; "none" -> "応答なし"; "empty" -> "候補なし"; "error" -> "失敗"; "unsupported" -> "未対応"; else -> "未実行" }}${if (results.isNotEmpty()) "（${results.size}件確認）" else ""}")
            KeyValue("カメラ機種", cam.device.str("model")?.ifBlank { null } ?: camera.str("model")); KeyValue("接続状態", when (cam.status) { "connecting" -> "接続しています"; "connected" -> "接続中"; "error" -> "接続エラー"; else -> "未接続" })
            KeyValue("通信方式", camera?.let { c -> makers.firstOrNull { it.str("id") == c.str("maker") }.str("wifi") })
            KeyValue("RAW転送", "${when (raw) { "supported" -> "対応（ブリッジ報告）"; "unsupported" -> "非対応"; "error" -> "エラー"; else -> "未検証" }}（Wi-Fi）${if (cam.prefs.str("transferMode") == "raw") "・RAW優先" else ""}"); KeyValue("最終通信", if (lastAt > 0) "${BusFormat.dateTime(lastAt)} JST" else null)
            (netError.ifBlank { detectMsg }).takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 10.sp, color = Brand.Warning) }
        }
        Panel(title = "カメラIPの検出・接続テスト", subtitle = "自動検出できないときは手動で入力できます。端末自身のIPはカメラのIPとして使いません。") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallButton("Wi-Fi情報を再取得", { scope.launch { refresh() } }, enabled = !busy, icon = Icons.Outlined.Refresh); SmallButton(if (detectState == "running") "検出中…" else "カメラを自動検出", { autoDetect() }, enabled = !busy, icon = Icons.Outlined.Search)
                SmallButton(if (probeState == "running") "確認中…" else "接続テスト", { test(cameraIp) }, enabled = !busy && cameraIp.isNotBlank()); SmallButton("このIPで接続", { if (camera != null && cameraIp.isNotBlank()) cam.connectWifi(JsonObject((camera as JsonObject) + jsonOf("host" to cameraIp, "port_spec" to ""))) }, enabled = camera != null && cameraIp.isNotBlank(), primary = true)
            }
            Muted("${when (detectState) { "running" -> "候補にだけ問い合わせています（範囲走査はしません）。"; "found" -> "応答した機器をカメラとして表示しています。"; "none" -> "候補からPTP/IPの応答がありませんでした。"; "empty" -> "検出する候補がありません。"; "error" -> "自動検出できませんでした。"; "unsupported" -> "このブリッジは自動検出に対応していません。"; else -> "まだ検出していません。" }} $detectMsg", 10)
            results.filter { it.bool("camera") == true }.forEach { row -> Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(row.str("ip").orEmpty(), fontSize = 12.sp, fontFamily = FontFamily.Monospace); Muted("応答あり${row.str("name")?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}・自動検出", 10) }; SmallButton("このIPで接続", { val ip = row.str("ip").orEmpty(); saveManual(ip); if (camera != null) cam.connectWifi(JsonObject((camera as JsonObject) + jsonOf("host" to ip, "port_spec" to ""))) }) } }
            results.filter { it.bool("camera") != true }.forEach { row -> Muted("${row.str("ip")}：${row.str("reason")?.ifBlank { null } ?: "応答なし"}（カメラとしては扱いません）", 10) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(draft, { draft = it }, label = { Text("カメラのIPアドレス（手動入力）", fontSize = 10.sp) }, placeholder = { Text("192.168.1.10", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                SmallButton("保存", { saveManual(draft); if (draft.isNotBlank()) test(draft) }); if (manualIp.isNotBlank()) SmallButton("解除", { saveManual("") })
            }
            Muted("現在のカメラIP: ${cameraIp.ifBlank { "未設定" }}（${when (ipSource) { "auto" -> "自動検出"; "manual" -> "手動入力"; else -> "未取得" }}）${cameraIp.takeIf { it.isNotBlank() }?.let { portFor(it) }?.takeIf { it.isNotBlank() }?.let { " ／ 接続先 $it" } ?: ""}", 10)
            if (camera == null) { Muted("Wi-Fiカメラが未登録です。機種が決まらないと通信方式を選べないため、先に登録してください。", 10); SmallButton("Wi-Fiカメラを登録", { nav.go("/wifi") }) }
            if (probeState != "idle") Text("接続テスト: ${probeMsg.ifBlank { "確認中…" }}", fontSize = 11.sp, color = when (probeState) { "ok" -> Brand.Success; "running" -> Brand.MutedFg; else -> Brand.Destructive })
            Muted("D750の内蔵Wi-Fiは Nikon WMU 向けで、PTP/IPでの応答は確認できていません。RAW（NEF）はUSB接続で取得してください。", 10); SmallButton("ブリッジ仕様と検証状況", { nav.go("/camera-bridge") })
        }
        if (!bridge.ready) BridgeSetupCard(bridge)
    }
}

private val ENDPOINTS = listOf(Triple("GET", "/health", "稼働確認（ブリッジ名と gphoto2 / カメラ層の版）"), Triple("GET", "/netinfo", "端末自身のIP・サブネット・ゲートウェイ（カメラのIPは返さない）"), Triple("GET", "/discover", "渡した候補IPにだけ PTP/IP で問い合わせ、応答した機器を返す"),
    Triple("GET", "/camera/probe", "接続先が本当にカメラか確認（接続テスト）"), Triple("GET", "/camera/detect", "接続できるカメラの一覧（USB / PTP/IP）"), Triple("GET", "/camera/status", "機種・シリアル・ファーム・使える設定名"), Triple("GET", "/camera/config", "ISO・シャッター・絞り・露出補正・WB・AFモード"),
    Triple("POST", "/camera/set-config", "設定を1つ変更し、読み直した値を返す"), Triple("POST", "/camera/capture", "シャッター（保存はカメラ内）"), Triple("GET", "/files", "カメラ内の一覧（RAW/JPEGの区別・サイズ）"), Triple("GET", "/download", "元ファイルをそのまま返す（Range で再開可・SHA-256 を併記）"))
private val BRIDGE_NOTES = listOf("端末側は Kotlin で、Foreground Service（常駐通知つき）として動かし、画面を開いている間だけ通信を受け付けます。", "USB は UsbManager（USB Host API）で検出し、ユーザーの許可を得てから PTP を開きます。Wi-Fi は WifiManager / ConnectivityManager で IP・サブネット・ゲートウェイを取得します。",
    "自動検出は「ゲートウェイ」と「手動入力のIP」だけを対象にし、PTP/IP の標準ポート 15740 に初期化パケットを送って応答（Init Command Ack）があった機器だけをカメラとして扱います。範囲走査はしません。", "端末自身のIPはカメラのIPとして返しません。検出結果にも含めません。",
    "/camera/probe と /download は時間のかかる処理なので、タイムアウトと中断（Range 再開）を必ず実装します。", "APIキーやトークンは起動時の環境変数／設定から読み、画面・ログ・URL に出しません。アプリ側は x-bridge-token で認証します。", "接続先が変わったら、以前のセッション・検出結果・一覧を破棄します。")
private const val NETINFO_SAMPLE = "{\n  \"ok\": true,\n  \"source\": \"android\",\n  \"ssidSupported\": true,\n  \"active\": {\n    \"iface\": \"wlan0\",\n    \"ssid\": \"NIKON_D750\",\n    \"ip\": \"192.168.1.23\",\n    \"netmask\": \"255.255.255.0\",\n    \"gateway\": \"192.168.1.1\"\n  }\n}"
private const val DISCOVER_SAMPLE = "GET /discover?protocol=ptpip&candidates=192.168.1.1,192.168.1.10\n{\n  \"ok\": true,\n  \"results\": [\n    { \"ip\": \"192.168.1.1\", \"camera\": false, \"reason\": \"PTP/IPの応答ではありません\" },\n    { \"ip\": \"192.168.1.10\", \"camera\": true, \"name\": \"NIKON D750\" }\n  ]\n}"

/** Androidブリッジ仕様（Web: camera-bridge.jsx） */
@Composable
fun CameraBridgeScreen() {
    val findings = produceState(emptyList<JsonElement>()) { value = Logic.constant("wifi/protocols", "D750_FINDINGS").items() }.value
    ScreenList {
        item { Heading("Androidブリッジ仕様", "スマートフォンをローカルブリッジとして使うための通信契約です。Wi-Fi接続ではカメラのバイナリ手順をブリッジが担当し、このアプリはブリッジの窓口だけを使います。") }
        item { Panel(title = "Nikon D750 の検証状況", subtitle = "実機で確かめていない項目は「未検証」のままにしています。対応済みとしては扱いません。") { findings.forEach { f -> val v = f.str("verified").orEmpty(); Column(Modifier.padding(vertical = 3.dp)) { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text(f.str("topic").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold); Tag(v, if (Regex("未検証|未対応").containsMatchIn(v)) Brand.Warning else Brand.MutedFg) }; Muted(f.str("fact").orEmpty(), 11) } } } }
        item { Panel(title = "ブリッジが提供する窓口", subtitle = "アプリはこの窓口だけを使います。") { ENDPOINTS.forEach { (m, p, n) -> Column(Modifier.padding(vertical = 2.dp)) { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Tag(m, Brand.MutedFg); Text(p, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }; Muted(n, 10) } } } }
        item { Panel(title = "Android側の実装要件", subtitle = "安全側に倒すための決まりごとです。") { BRIDGE_NOTES.forEach { Muted("・$it", 11) } } }
        item { Panel(title = "ネットワーク情報の返却例", subtitle = "/netinfo は端末自身の情報だけを返します。") { Text(NETINFO_SAMPLE, fontSize = 10.sp, fontFamily = FontFamily.Monospace) } }
        item { Panel(title = "自動検出の返却例", subtitle = "応答しなかった候補は理由つきで返し、カメラとして扱いません。") { Text(DISCOVER_SAMPLE, fontSize = 10.sp, fontFamily = FontFamily.Monospace) } }
        item { Panel(title = "この画面の範囲") { Muted("ここで定めているのはアプリとブリッジの間の契約です。USB接続はこのアプリが USB Host API で直接カメラの PTP を扱います。Wi-Fi接続に使うブリッジのURLとトークンは「Wi-Fi画像転送 → 設定」で入力してください。", 11) } }
    }
}

/** カメラ Wi-Fi 接続（CamBridge）を開く。CamBridge はカメラの Wi-Fi に直接つなぐ専用画面で、Android 12 以上が必要。 */
@Composable
private fun CamBridgeLauncher() {
    val context = LocalContext.current
    Panel(title = "カメラ Wi-Fi 接続（CamBridge）", subtitle = "スマートフォンをカメラの Wi-Fi に直接つなぎ、ライブビュー・リモート撮影・AF・画像の取り込みを行います（Canon / Sony / Nikon / LUMIX）。ブリッジは不要です。") {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            SmallButton("CamBridge を開く", { context.startActivity(android.content.Intent(context, com.example.d750bridge.MainActivity::class.java)) }, icon = Icons.Outlined.Wifi, primary = true)
        } else {
            Text("この機能は Android 12 以上で使えます（この端末は Android ${android.os.Build.VERSION.RELEASE}）。下の USB 接続・ブリッジ経由の接続は使えます。", fontSize = 11.sp, color = Brand.Warning)
        }
    }
}
