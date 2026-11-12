package app.narabuspulse.feature.wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Export
import app.narabuspulse.core.JST
import app.narabuspulse.core.Logic
import app.narabuspulse.core.bool
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

private val TABS = listOf("/wifi" to "接続と画像一覧", "/wifi/history" to "転送履歴・検証", "/wifi/setup" to "ブリッジ設定・対応表")

@Composable
private fun WifiScaffold(current: String, content: LazyListScope.() -> Unit) {
    val nav = LocalNav.current
    ScreenList {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Heading("Wi-Fi画像転送", "同じWi-Fi上のPC・端末で動くローカルブリッジ経由で、カメラ内のRAWを変換せずに取得します。取得したデータはそのまま保存し、サイズとSHA-256で検証します。")
                Tag("RAWは再圧縮・再現像・リサイズしません", Brand.MutedFg); ChipRow(TABS, current, { if (it != current) nav.go(it) })
            }
        }
        content()
    }
}

/** ブリッジの設定と稼働確認（Web: useBridge.js）。 */
class BridgeState(val client: BridgeClient, val url: String, val token: String, val health: String, val info: JsonElement?, val error: String, val check: () -> Unit, val save: (String, String) -> Unit) { val ready get() = health == "ok" }

@Composable
fun rememberBridge(): BridgeState {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { BridgeConfig.init(context) }
    val config by BridgeConfig.state.collectAsState()
    val client = remember(config) { BridgeClient(config.first, config.second) }
    var health by remember { mutableStateOf("idle") }; var info by remember { mutableStateOf<JsonElement?>(null) }; var error by remember { mutableStateOf("") }
    suspend fun check() { health = "checking"; info = null; error = ""; try { info = client.health(); health = "ok" } catch (e: Throwable) { if (e is CancellationException) throw e; health = "error"; error = e.message ?: "ブリッジに接続できません" } }
    LaunchedEffect(client) { if (client.configured) check() }
    return BridgeState(client, config.first, config.second, health, info, error, { scope.launch { check() } }, { u, t -> BridgeConfig.save(context, u, t) })
}

@Composable fun rememberMakers(): List<JsonElement> = produceState(emptyList<JsonElement>()) { value = Logic.constant("wifi/protocols", "MAKERS").items() }.value
private fun makerOf(makers: List<JsonElement>, id: String?) = makers.firstOrNull { it.str("id") == id } ?: makers.lastOrNull()
fun portSpecOf(camera: JsonElement?, makers: List<JsonElement>): String {
    camera.str("port_spec")?.takeIf { it.isNotBlank() }?.let { return it }
    val ip = camera.str("host")?.trim().orEmpty()
    return if (ip.isNotBlank()) (makerOf(makers, camera.str("maker")).str("bridgePort") ?: "ptpip:<カメラのIP>").replace("<カメラのIP>", ip) else "usb:"
}

private class Session(val state: String = "idle", val error: String = "", val model: String = "", val firmware: String = "", val resume: Boolean = false, val files: List<CamFile> = emptyList(), val port: String = "", val connectedAt: Long? = null)
private data class TransferJob(val file: CamFile, val status: String = "queued", val received: Long = 0, val total: Long? = null, val bps: Double = 0.0, val etaSec: Double? = null, val error: String = "", val sha256: String = "",
                               val resumeUsed: Boolean = false, val resumeSupported: Boolean = true, val sizeExact: Boolean = false)

private fun shotAt(value: String?): String = value?.takeIf { it.isNotBlank() }?.let { v -> v.toLongOrNull()?.let { BusFormat.dateTime(it) } ?: runCatching { BusFormat.dateTime(OffsetDateTime.parse(v).toInstant().toEpochMilli()) }.getOrElse { v.replace("T", " ").take(16) } } ?: "—"
private fun shotMillis(value: String?): Long? = value?.let { v -> v.toLongOrNull() ?: runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull() ?: runCatching { java.time.LocalDateTime.parse(v.take(19)).atZone(JST).toInstant().toEpochMilli() }.getOrNull() }

/** 接続と画像一覧（Web: wifi.index.jsx + CameraListCard / SessionStatusCard / CameraRegisterForm / FileFilterBar / FileTable / TransferQueueCard） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WifiScreen() {
    val context = LocalContext.current; val nav = LocalNav.current; val scope = rememberCoroutineScope()
    val bridge = rememberBridge(); val makers = rememberMakers()
    var cameras by remember { mutableStateOf<List<JsonElement>>(emptyList()) }; var loading by remember { mutableStateOf(true) }
    var activeId by remember { mutableStateOf("") }; var busyId by remember { mutableStateOf("") }; var session by remember { mutableStateOf(Session()) }
    var kind by remember { mutableStateOf("all") }; var from by remember { mutableStateOf("") }; var to by remember { mutableStateOf("") }; var minMb by remember { mutableStateOf("") }; var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var jobs by remember { mutableStateOf<List<TransferJob>>(emptyList()) }; var running by remember { mutableStateOf(false) }; var zipState by remember { mutableStateOf("") }; var zipError by remember { mutableStateOf("") }
    var runJob by remember { mutableStateOf<Job?>(null) }
    val activeCamera = cameras.firstOrNull { it.str("id") == activeId }

    LaunchedEffect(Unit) { try { cameras = Api.entityList("WifiCamera", sort = "-created_date", limit = 50) } catch (e: Throwable) { if (e is CancellationException) throw e; session = Session("error", e.userMessage()) } finally { loading = false } }
    fun connect(camera: JsonElement) {
        val port = portSpecOf(camera, makers); val id = camera.str("id").orEmpty()
        busyId = id; session = Session("connecting", model = camera.str("model").orEmpty(), port = port)
        scope.launch {
            try {
                val res = bridge.client.listFiles(port)
                session = Session("connected", "", res.model.ifBlank { camera.str("model").orEmpty() }, res.firmware, res.resume, res.files, port, System.currentTimeMillis()); activeId = id; selected = emptySet()
                runCatching { Api.entityUpdate("WifiCamera", id, jsonOf("last_status" to "connected", "last_error" to "", "last_connected_at" to System.currentTimeMillis(), "model" to res.model.ifBlank { camera.str("model").orEmpty() }, "firmware" to res.firmware.ifBlank { camera.str("firmware").orEmpty() })) }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                val msg = e.message ?: "接続できませんでした"
                session = Session("error", msg, camera.str("model").orEmpty(), port = port); activeId = id
                runCatching { Api.entityUpdate("WifiCamera", id, jsonOf("last_status" to "error", "last_error" to msg)) }
            } finally { busyId = "" }
        }
    }
    fun disconnect() { activeId = ""; session = Session() }

    fun patch(id: String, f: (TransferJob) -> TransferJob) { jobs = jobs.map { if (it.file.id == id) f(it) else it } }
    suspend fun record(file: CamFile, r: DownloadResult?, status: String, startedAt: Long, batchId: String, error: String = "") {
        runCatching {
            Api.entityCreate("WifiTransfer", jsonOf("camera_label" to (activeCamera.str("label") ?: "未選択"), "protocol" to (activeCamera.str("protocol") ?: "unknown"), "file_name" to file.name, "file_format" to file.format, "is_raw" to file.isRaw,
                "size_bytes" to (r?.total ?: file.size), "size_exact" to file.sizeExact, "received_bytes" to r?.bytes, "sha256" to (r?.sha256 ?: ""), "hash_source" to (if (r?.bridgeHash != null) "bridge" else "client"),
                "hash_match" to (if (r?.bridgeHash != null) (if (r.bridgeHash == r.sha256) "matched" else "mismatched") else "not_compared"), "size_match" to (r?.sizeMatch == true), "resume_used" to (r?.resumeUsed == true), "resume_supported" to (r?.resumeSupported != false),
                "status" to status, "error" to error, "started_at" to startedAt, "finished_at" to System.currentTimeMillis(), "duration_ms" to r?.durationMs, "avg_bps" to r?.avgBps, "batch_id" to batchId))
        }
    }
    fun start(files: List<CamFile>, zip: Boolean, zipName: String = "") {
        if (running || files.isEmpty()) return
        running = true; zipError = ""; jobs = files.map { TransferJob(it) }
        val batchId = "b${System.currentTimeMillis().toString(36)}"
        runJob = scope.launch {
            val done = mutableListOf<Pair<CamFile, DownloadResult>>()
            try {
                for (file in files) {
                    val startedAt = System.currentTimeMillis()
                    patch(file.id) { it.copy(status = "running", received = 0, error = "", sha256 = "") }
                    try {
                        val r = bridge.client.download(file, context.cacheDir) { p -> patch(file.id) { it.copy(received = p.received, total = p.total ?: file.size, bps = p.bps, etaSec = p.etaSec) } }
                        patch(file.id) { it.copy(status = "done", received = r.bytes, total = r.total, sha256 = r.sha256, resumeUsed = r.resumeUsed, resumeSupported = r.resumeSupported, sizeExact = r.sizeExact) }
                        record(file, r, "ok", startedAt, batchId)
                        if (zip) done += file to r else { Export.saveStream(context, file.name, "application/octet-stream") { out -> r.file.inputStream().use { it.copyTo(out) } }; r.file.delete() }
                    } catch (e: CancellationException) {
                        patch(file.id) { it.copy(status = "canceled", error = "中断しました") }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { record(file, null, "canceled", startedAt, batchId, "中断しました") }
                        throw e
                    } catch (e: Throwable) {
                        val msg = e.message ?: "転送に失敗しました"
                        patch(file.id) { it.copy(status = "error", error = msg) }; record(file, null, "error", startedAt, batchId, msg)
                    }
                }
            } finally {
                running = false
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    if (zip && done.isNotEmpty()) {
                        try {
                            zipState = done.first().first.name
                            // ZIP内のRAWは無圧縮（ストア）で、バイト列・ファイル名・拡張子を変えない
                            Export.saveStream(context, zipName.ifBlank { "raw-$batchId.zip" }, "application/zip") { out ->
                                ZipOutputStream(out).use { z -> z.setMethod(ZipOutputStream.STORED); done.forEach { (f, r) -> zipState = f.name; z.putNextEntry(ZipEntry(f.name).apply { method = ZipEntry.STORED; size = r.bytes; compressedSize = r.bytes; crc = r.crc32 }); r.file.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
                            }
                        } catch (e: Throwable) { zipError = e.message ?: "ZIPを作成できませんでした。個別にダウンロードしてください" } finally { zipState = "" }
                    }
                    done.forEach { it.second.file.delete() }
                    if (done.size < files.size && zip) zipError = zipError.ifBlank { "一部のファイルが取得できませんでした（成功分のみ保存しています）" }
                }
            }
        }
    }

    val allFiles = session.files
    val base = allFiles.filter { f ->
        if (from.isNotBlank() || to.isNotBlank()) { val at = shotMillis(f.capturedAt) ?: return@filter false
            if (from.isNotBlank() && at < LocalDate.parse(from).atStartOfDay(JST).toInstant().toEpochMilli()) return@filter false
            if (to.isNotBlank() && at >= LocalDate.parse(to).plusDays(1).atStartOfDay(JST).toInstant().toEpochMilli()) return@filter false }
        minMb.toDoubleOrNull()?.takeIf { it > 0 }?.let { mb -> if ((f.size ?: 0) < mb * 1024 * 1024) return@filter false }
        query.isBlank() || f.name.uppercase().contains(query.trim().uppercase())
    }
    val rawCount = base.count { it.isRaw }; val jpegCount = base.count { it.isJpeg }
    val files = base.filter { when (kind) { "raw" -> it.isRaw; "jpeg" -> it.isJpeg; else -> true } }
    val pairs = remember(allFiles) { allFiles.groupBy { it.pairKey } }
    val selectedFiles = files.filter { it.id in selected }; val connected = session.state == "connected"
    val today = LocalDate.now(JST).toString()

    WifiScaffold("/wifi") {
        if (!bridge.ready) item { Panel { Text("⚠ ブリッジに接続していません。PC・端末側でブリッジを起動し、接続先を設定してください。", fontSize = 12.sp, color = Brand.Warning); SmallButton("設定を開く", { nav.go("/wifi/setup") }) } }
        item {
            Panel(title = "登録したカメラ", subtitle = "${cameras.size}台。接続中のカメラの画像だけを一覧に表示します。", action = { SmallButton("一覧を更新", { activeCamera?.let(::connect) }, enabled = busyId.isBlank() && activeId.isNotBlank(), icon = Icons.Outlined.Refresh) }) {
                if (loading) Muted("読み込み中…", 11)
                if (!loading && cameras.isEmpty()) Muted("まだ登録がありません。下のフォームから追加してください。", 11)
                cameras.forEach { camera ->
                    val id = camera.str("id").orEmpty(); val active = id == activeId; val isConn = active && connected
                    val (st, sc) = if (isConn) "接続中" to Brand.Success else when (camera.str("last_status")) { "connected" -> "接続中" to Brand.Success; "error" -> "エラー" to Brand.Destructive; else -> "未接続" to Brand.MutedFg }
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) { Text(camera.str("label").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (active) Brand.Orange else Color.Unspecified); Muted("${makerOf(makers, camera.str("maker")).str("label").orEmpty()} ${camera.str("model").orEmpty()}", 11); Tag(st, sc) }
                        Muted("${camera.str("protocol")} ・ ${portSpecOf(camera, makers)} ・ Wi-Fi RAW：${camera.str("raw_over_wifi") ?: "unverified"}", 10)
                        camera.str("last_error")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 10.sp, color = Brand.Destructive) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isConn) SmallButton("切断", { disconnect() }, icon = Icons.Outlined.LinkOff) else SmallButton(if (busyId == id) "接続中…" else "接続する", { connect(camera) }, enabled = busyId.isBlank(), icon = Icons.Outlined.Link, primary = true)
                            SmallButton("削除", { scope.launch { runCatching { Api.entityDelete("WifiCamera", id) }; cameras = cameras.filter { it.str("id") != id }; if (activeId == id) disconnect() } }, enabled = busyId.isBlank(), icon = Icons.Outlined.Delete)
                        }
                    }
                }
            }
        }
        item {
            Panel(title = "接続状態", subtitle = if (connected) "カメラが返した情報のみを表示しています。" else "未接続のときは取得できる情報がありません。", action = { if (connected) SmallButton("再接続", { activeCamera?.let(::connect) }, icon = Icons.Outlined.Refresh) }) {
                when (session.state) {
                    "idle" -> Muted("登録したカメラの「接続する」を押してください。", 11)
                    "connecting" -> Muted("カメラに問い合わせています…", 11)
                    "error" -> { Text("⚠ 接続できませんでした", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.Destructive); Text(session.error, fontSize = 11.sp)
                        Muted("確認すること：カメラのWi-Fiが有効か、ブリッジがカメラと同じネットワークにいるか、ブリッジが起動しているか、接続指定（${session.port.ifBlank { "usb:" }}）が正しいか。", 10); SmallButton("再接続を試す", { activeCamera?.let(::connect) }, icon = Icons.Outlined.Link) }
                    else -> {
                        KeyValue("カメラ", activeCamera.str("label")); KeyValue("メーカー", makerOf(makers, activeCamera.str("maker")).str("label")); KeyValue("機種（取得値）", session.model.ifBlank { "取得できませんでした" }); KeyValue("ファームウェア", session.firmware.ifBlank { "取得できませんでした" })
                        KeyValue("接続方式", session.port); KeyValue("再接続（Range）", if (session.resume) "対応" else "非対応")
                        Muted("RAWは受信したバイト列をそのまま保存します。サイズ一致とSHA-256を記録し、取得元のハッシュと比較できた場合だけ「一致」と表示します。", 10); SmallButton("切断する", { disconnect() })
                    }
                }
            }
        }
        item {
            var label by remember { mutableStateOf("") }; var maker by remember { mutableStateOf("nikon") }; var model by remember { mutableStateOf("") }; var host by remember { mutableStateOf("") }; var saving by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
            val m = makerOf(makers, maker)
            Panel(title = "カメラを登録", subtitle = "同じネットワーク上のカメラを手動で追加します。IPアドレスは任意です。") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(label, { label = it }, label = { Text("呼び名", fontSize = 10.sp) }, placeholder = { Text("例 D750 実機", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f)); SelectField(makers.map { it.str("id").orEmpty() to it.str("label").orEmpty() }, maker, { maker = it }, label = "メーカー") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedTextField(model, { model = it }, label = { Text("機種名", fontSize = 10.sp) }, placeholder = { Text("例 D750 / EOS R5", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f)); OutlinedTextField(host, { host = it }, label = { Text("IPアドレス（任意）", fontSize = 10.sp) }, placeholder = { Text("例 192.168.1.2", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f)) }
                Muted("接続方式：${m.str("wifi").orEmpty()}（${m.str("protocol").orEmpty()}）／ブリッジ経由で接続します。IPが空ならUSB接続として扱います。", 10)
                if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
                SmallButton(if (saving) "登録中…" else "登録する", {
                    if (label.isBlank() && model.isBlank()) { error = "呼び名か機種名を入力してください"; return@SmallButton }
                    saving = true; error = ""
                    scope.launch {
                        try { val row = Api.entityCreate("WifiCamera", jsonOf("label" to label.trim().ifBlank { model.trim() }, "maker" to maker, "model" to model, "host" to host, "protocol" to m.str("protocol"), "port_spec" to "", "raw_over_wifi" to m.str("rawOverWifi"), "note" to "", "enabled" to true, "last_status" to "idle"))
                            cameras = listOf(row) + cameras; label = ""; model = ""; host = "" }
                        catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "登録できませんでした" } } finally { saving = false }
                    }
                }, enabled = !saving, icon = Icons.Outlined.Add, primary = true)
            }
        }
        if (connected) {
            item {
                Panel(title = "カメラ内の画像（${files.size}件）", subtitle = "RAW/JPEGを区別して表示します") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton("選択を取得（${selectedFiles.size}）", { start(selectedFiles, selectedFiles.size > 1, "selected-$today.zip") }, enabled = selectedFiles.isNotEmpty() && !running, icon = Icons.Outlined.Download, primary = true)
                        SmallButton("全RAW（$rawCount）", { start(files.filter { it.isRaw }, true, "raw-all-$today.zip") }, enabled = rawCount > 0 && !running, icon = Icons.Outlined.FolderZip)
                    }
                    ChipRow(listOf("all" to "すべて (${base.size})", "raw" to "RAWのみ ($rawCount)", "jpeg" to "JPEGのみ ($jpegCount)"), kind, { kind = it })
                    Muted("RAW合計 ${formatBytes(base.filter { it.isRaw }.sumOf { it.size ?: 0 })}", 10)
                    OutlinedTextField(query, { query = it }, placeholder = { Text("ファイル名で絞り込み", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        DateField(from, { from = it }, label = "撮影日"); DateField(to, { to = it }, label = "まで"); if (from.isNotBlank() || to.isNotBlank()) SmallButton("日付を解除", { from = ""; to = "" })
                        OutlinedTextField(minMb, { minMb = it }, label = { Text("最小サイズ MB", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(0.4f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    if (files.isEmpty()) Muted("条件に合うファイルがありません。接続状態と絞り込みを確認してください。", 11)
                    else Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(files.all { it.id in selected }, { on -> selected = if (on) files.map { it.id }.toSet() else emptySet() }); Text("表示中の${files.size}件を選択", fontSize = 12.sp) }
                    Muted("複数選択・全RAWの取得はZIPにまとめます。ZIP内のRAWは無圧縮（ストア）で、バイト列・ファイル名・拡張子を変えません。", 10)
                }
            }
            files.forEach { file ->
                item(key = "f-${file.id}") {
                    val pair = pairs[file.pairKey].orEmpty(); val hasPair = pair.size > 1
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(file.id in selected, { selected = if (file.id in selected) selected - file.id else selected + file.id })
                        Column(Modifier.weight(1f)) {
                            Text(file.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                Tag(file.kind, when (file.kind) { "RAW" -> Brand.Orange; "JPEG" -> INFO; else -> Brand.MutedFg }); Muted(file.format, 10); Muted(file.size?.let { formatBytes(it) } ?: "サイズ未取得", 10); Muted(shotAt(file.capturedAt), 10)
                                if (file.model.isNotBlank()) Muted(file.model, 10); Muted("No.${file.index}", 10); if (hasPair) Muted(pair.joinToString(" + ") { it.format }, 10)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            SmallButton("取得", { start(listOf(file), false) }, enabled = !running, icon = Icons.Outlined.Download)
                            if (hasPair) SmallButton("RAW+JPEG", { val p = pair.filter { it.isRaw || it.isJpeg }; if (p.isNotEmpty()) start(p, p.size > 1, "${file.pairKey}.zip") }, enabled = !running)
                        }
                    }
                }
            }
        }
        if (jobs.isNotEmpty() || zipState.isNotBlank() || zipError.isNotBlank()) item {
            Panel(title = "転送状況", subtitle = if (running) "1件ずつ順番に処理しています" else null, action = { if (running) SmallButton("中断", { runJob?.cancel() }, icon = Icons.Outlined.Block) else SmallButton("表示を消す", { jobs = emptyList(); zipError = "" }) }) {
                if (zipState.isNotBlank()) Muted("ZIPを作成しています：$zipState — RAWは無圧縮のまま格納します", 11)
                if (zipError.isNotBlank()) Text(zipError, fontSize = 11.sp, color = Brand.Warning)
                jobs.forEach { job ->
                    val (st, sc) = when (job.status) { "running" -> "転送中" to INFO; "done" -> "完了" to Brand.Success; "error" -> "失敗" to Brand.Destructive; "canceled" -> "中断" to Brand.Warning; else -> "待機" to Brand.MutedFg }
                    val total = job.total ?: job.file.size; val pct = total?.takeIf { it > 0 }?.let { minOf(100, (job.received * 100 / it).toInt()) }
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text(job.file.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)); Tag(st, sc); if (job.status == "error") SmallButton("再試行", { start(listOf(job.file), false) }, enabled = !running, icon = Icons.Outlined.Refresh) }
                        if (job.status == "running" || job.status == "queued") LinearProgressIndicator({ (pct ?: 5) / 100f }, Modifier.fillMaxWidth().height(4.dp))
                        Muted(listOfNotNull("${formatBytes(job.received)} / ${total?.let { formatBytes(it) } ?: "不明"}${pct?.let { "（$it%）" } ?: ""}", if (job.status == "running") "${formatBytes(job.bps.toLong())}/s　残り ${formatEta(job.etaSec)}" else null,
                            if (!job.sizeExact && !job.file.sizeExact && job.status != "queued") "サイズは概算値" else null, if (job.resumeUsed) "再開を使用" else null, if (!job.resumeSupported) "再開に非対応（やり直し）" else null, job.sha256.takeIf { it.isNotBlank() }?.let { "SHA-256 ${it.take(12)}…" }).joinToString("　"), 10)
                        if (job.error.isNotBlank()) Text(job.error, fontSize = 10.sp, color = Brand.Destructive)
                    }
                }
            }
        }
    }
}

/** 転送履歴・検証（Web: wifi.history.jsx + TransferHistoryCard.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WifiHistoryScreen() {
    var rows by remember { mutableStateOf<List<JsonElement>>(emptyList()) }; var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { rows = try { Api.entityList("WifiTransfer", sort = "-started_at", limit = 60) } catch (e: Throwable) { if (e is CancellationException) throw e; emptyList() }; loading = false }
    WifiScaffold("/wifi/history") {
        item {
            Panel(title = "転送履歴", subtitle = "${rows.size}件を記録。サイズとハッシュの両方が確認できた${rows.count { it.str("status") == "ok" && it.bool("size_match") == true && it.str("hash_match") == "matched" }}件だけを「検証済み」として扱います。") {
                if (loading) Muted("読み込み中…", 11)
                if (!loading && rows.isEmpty()) Muted("まだ転送の記録がありません。", 11)
            }
        }
        rows.forEachIndexed { i, row ->
            item(key = "h-${row.str("id") ?: i}") {
                val (st, sc) = when (row.str("status")) { "ok" -> "完了" to Brand.Success; "incomplete" -> "不完全" to Brand.Warning; "canceled" -> "中断" to Brand.MutedFg; else -> "失敗" to Brand.Destructive }
                val (ht, hc) = when (row.str("hash_match")) { "matched" -> "ハッシュ一致" to Brand.Success; "mismatched" -> "ハッシュ不一致" to Brand.Destructive; else -> "ハッシュ未比較" to Brand.MutedFg }
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(row.str("file_name").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)); Tag(st, sc) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Muted(row.str("file_format").orEmpty(), 10); if (row.bool("is_raw") == true) Tag("RAW", Brand.Orange); Muted(formatBytes(row.long("received_bytes") ?: row.long("size_bytes")), 10); Muted(BusFormat.dateTime(row.long("finished_at") ?: row.long("started_at")), 10); Muted(row.str("camera_label").orEmpty(), 10)
                        if (row.bool("resume_used") == true) Muted("再開使用", 10); if (row.bool("resume_supported") == false) Muted("再開非対応", 10)
                        Tag(if (row.bool("size_match") == true) "サイズ一致" else "サイズ未照合", if (row.bool("size_match") == true) Brand.Success else Brand.MutedFg); Tag(ht, hc)
                    }
                    row.str("sha256")?.takeIf { it.isNotBlank() }?.let { Text("SHA-256 $it", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Brand.MutedFg) }
                    row.str("error")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 10.sp, color = Brand.Destructive) }
                }
            }
        }
    }
}

/** ブリッジの接続先（Web: BridgeSetupCard.jsx）。カメラ統合コンソールからも使う。 */
@Composable
fun BridgeSetupCard(bridge: BridgeState) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var url by remember(bridge.url) { mutableStateOf(bridge.url) }; var token by remember(bridge.token) { mutableStateOf(bridge.token) }; var saved by remember { mutableStateOf("") }
    val (st, sc) = when (bridge.health) { "checking" -> "確認中…" to INFO; "ok" -> "接続OK" to Brand.Success; "error" -> "未接続" to Brand.Destructive; else -> "未確認" to Brand.MutedFg }
    Panel(title = "ローカルブリッジ", subtitle = "同じWi-Fi上のPC・端末で動かす中継です。カメラの実プロトコルで通信し、RAWをそのまま取得します。", action = { Tag(st, sc) }) {
        OutlinedTextField(url, { url = it }, label = { Text("ブリッジのURL", fontSize = 10.sp) }, placeholder = { Text("http://192.168.1.5:8765", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(token, { token = it }, label = { Text("トークン（任意）", fontSize = 10.sp) }, placeholder = { Text("未設定なら空欄", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("保存して接続確認", { if (url.trim() == bridge.url && token == bridge.token) bridge.check() else bridge.save(url, token) }, primary = true); SmallButton("再確認", { bridge.check() })
            SmallButton("ブリッジ本体を取得", { scope.launch { runCatching { saved = Export.text(context, "raw-bridge.js", "text/javascript", Logic.constant("wifi/bridgeScript", "BRIDGE_SCRIPT").text) + " に保存しました" } } }, icon = Icons.Outlined.Download)
        }
        if (saved.isNotBlank()) Muted(saved, 10)
        if (bridge.error.isNotBlank()) Text(bridge.error, fontSize = 11.sp, color = Brand.Destructive)
        bridge.info?.let { Muted("ブリッジ稼働中：${it.str("bridge").orEmpty()} / gphoto2 ${it.str("gphoto2") ?: "不明"}", 11) }
        Muted("このアプリはスマートフォン上で動くため、URLの 127.0.0.1 は端末自身を指します。PCでブリッジを動かす場合は、PCのIPアドレス（例 http://192.168.1.5:8765）を入力してください。", 10)
        Muted("取得したRAWは再圧縮・変換せず、ファイル名と拡張子も変えません。ブリッジ側でもファイル内容を書き換えません。配布スクリプトは動作未検証の範囲を含みます。", 10)
    }
}

/** ブリッジ設定・対応表（Web: wifi.setup.jsx + ProtocolMatrixCard.jsx） */
@Composable
fun WifiSetupScreen() {
    val bridge = rememberBridge(); val makers = rememberMakers()
    val findings = produceState(emptyList<JsonElement>()) { value = Logic.constant("wifi/protocols", "D750_FINDINGS").items() }.value
    WifiScaffold("/wifi/setup") {
        item { BridgeSetupCard(bridge) }
        item {
            Panel(title = "ブリッジの準備（PC側）", subtitle = "配布スクリプトをPCで実行します。通信はすべてカメラの実プロトコルで行われます。") {
                listOf("libgphoto2 の gphoto2 をインストールします（macOS: brew / Windows: winget・WSL / Linux: apt）。", "「ブリッジ本体を取得」で raw-bridge.js を保存してPCへ移し、node raw-bridge.js で起動します（既定 ポート8765）。",
                    "カメラをPCと同じWi-Fi（またはUSB）でつなぎ、この画面にPCのURLを入れて「保存して接続確認」を押します。", "接続先の指定はメーカー表の例に従います（例 Nikon のPTP/IP：ptpip:192.168.1.2 ／ USB：usb:）。").forEachIndexed { i, t -> Muted("${i + 1}. $t", 11) }
                Muted("カメラの独自プロトコル（PTP/IP 15740 など）はブリッジが担当し、アプリはブリッジのHTTP窓口だけを使います。", 10)
            }
        }
        item {
            Panel(title = "メーカー別の通信方式", subtitle = "カメラへはブリッジ経由で接続します。") {
                makers.forEach { m ->
                    val (wt, wc) = when (m.str("rawOverWifi")) { "supported" -> "取得可（実機確認済み）" to Brand.Success; "limited" -> "機種により制限" to Brand.Warning; "unsupported" -> "取得不可" to Brand.Destructive; else -> "未検証" to Brand.MutedFg }
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Text(m.str("label").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold); Tag(m.str("protocol").orEmpty(), Brand.MutedFg); Tag("Wi-Fi RAW：$wt", wc) }
                        Muted("無線方式：${m.str("wifi")} ／ 公開状況：${m.str("open")}", 10); Text(m.str("detail").orEmpty(), fontSize = 11.sp, lineHeight = 16.sp)
                        Muted("接続指定の例：${m.str("bridgePort")}　RAW拡張子：${m.list("rawExt").joinToString(" / ") { it.text }}", 10)
                    }
                }
            }
        }
        item { Panel(title = "Nikon D750 の内蔵Wi-Fi（調査結果）", subtitle = "実機で確認できていない項目は「未検証」のまま表示します。") { findings.forEach { f -> Column(Modifier.padding(vertical = 3.dp)) { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text(f.str("topic").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold); Tag(f.str("verified").orEmpty(), if (Regex("未検証|未対応").containsMatchIn(f.str("verified").orEmpty())) Brand.Warning else Brand.MutedFg) }; Muted(f.str("fact").orEmpty(), 11) } } } }
        item {
            Panel(title = "未検証の範囲", subtitle = "実機で確認できていない内容は対応済みとして扱いません。") {
                listOf("D750のWi-Fi（PTP/IP）経由での NEF 取得、および切断後の再接続。", "大容量RAW（数百MB）での長時間転送と、回線断からの Range 再開。", "Canon CCAPI・Sony Camera Remote API の Wi-Fi 経由でのRAW取得（ブリッジは gphoto2 経由のみ実装）。", "ZIP一括ダウンロードの実ファイルでの検証（無圧縮で格納する実装のみ確認）。").forEach { Muted("・$it", 11) }
            }
        }
    }
}
