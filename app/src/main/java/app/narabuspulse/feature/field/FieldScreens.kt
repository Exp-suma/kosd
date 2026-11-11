package app.narabuspulse.feature.field

import android.os.StatFs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Export
import app.narabuspulse.core.Logic
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.offline.OfflineStore
import app.narabuspulse.core.rememberLocationRequest
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.Col
import app.narabuspulse.feature.analytics.DataTable
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.analytics.fmtNum
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.settings.SettingsHeader
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.MapCircle
import app.narabuspulse.ui.kit.MapLine
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberConfirmer
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val TABS = listOf("/field" to "現地モード", "/field/records" to "現地記録", "/field/map" to "保存地図", "/field/data" to "保存データ", "/field/conflicts" to "同期競合")

@Composable
private fun FieldHeader(current: String) {
    val nav = LocalNav.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        app.narabuspulse.feature.data.Heading("現地活動モード", "電波の弱い場所でも使える画面です。保存した地図・地点・車両・気象を表示し、メモ・写真・訪問を端末に記録します。通信が戻ると自動で同期します。")
        ChipRow(TABS, current, { if (it != current) nav.go(it) })
    }
}

private fun savedPlans(): List<JsonElement> = OfflineStore.part("plans")?.objAt("rows").items("plans")
private fun savedSpots(): List<JsonElement> = OfflineStore.part("spots").items("rows")

/** 現地モード（Web: field.index.jsx） */
@Composable
fun FieldHomeScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val online by OfflineStore.online.collectAsState()
    val stats by OfflineStore.stats.collectAsState()
    val settings by OfflineStore.settings.collectAsState()
    val sync by OfflineStore.sync.collectAsState()
    val records by OfflineStore.records.collectAsState()
    val parts by OfflineStore.parts.collectAsState()
    val tiles by OfflineStore.tiles.collectAsState()
    val savedAt = parts.maxOfOrNull { it.long("savedAt") ?: 0 } ?: 0
    ScreenList {
        item { FieldHeader("/field") }
        item {
            Panel(title = if (online) "📶 オンライン｜通常の機能も使えます" else "📵 オフライン｜保存済みデータで動作中", subtitle = if (online) "保存したデータはそのまま使えます。現地で記録した内容は自動で同期します。" else "新しい車両位置や気象は取得できません。保存した内容の閲覧と、メモ・写真の記録ができます。") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("手動同期", { scope.launch { OfflineStore.syncNow(force = true) } }, enabled = online && !sync.running, icon = Icons.Outlined.Refresh)
                    SmallButton("データを保存", { nav.go("/settings/offline") }, icon = Icons.Outlined.CloudDownload)
                }
                KeyValue("同期待ち", "${stats.pending}件"); KeyValue("同期失敗", "${stats.failed}件")
                KeyValue("最終同期", if (settings.lastSyncAt > 0) BusFormat.dateTime(settings.lastSyncAt) else "—"); KeyValue("保存データ更新", if (savedAt > 0) BusFormat.dateTime(savedAt) else "未保存")
                if (stats.conflict > 0) Row(verticalAlignment = Alignment.CenterVertically) { Text("⚠ 他の端末の変更と競合している記録が${stats.conflict}件あります。", fontSize = 11.sp, color = Brand.Destructive, modifier = Modifier.weight(1f)); TextButton(onClick = { nav.go("/field/conflicts") }) { Text("選んで解決する", fontSize = 11.sp) } }
                if (parts.isEmpty()) Muted("まだデータを保存していません。オフライン設定から、出かける前に対象の地域を保存してください。", 11)
            }
        }
        item { Panel(title = "📝 現地で記録する", subtitle = "オフラインでも保存できます。位置・日時が取れない場合は空欄のまま記録します。") { FieldNoteForm(null, savedSpots(), savedPlans(), onSaved = {}) } }
        item {
            Panel(title = "最近の記録", action = { TextButton(onClick = { nav.go("/field/records") }) { Text("すべて見る", fontSize = 11.sp) } }) {
                FieldNoteList(records.filter { it.bool("deleted") != true }.sortedByDescending { it.long("createdAt") ?: 0 }.take(6), emptyText = "まだ記録はありません。上の入力欄から記録できます。")
            }
        }
        item {
            Panel(title = "保存済みの内容", subtitle = if (savedAt > 0) "最終更新 ${BusFormat.dateTime(savedAt)}" else "まだ保存していません", action = { SmallButton("開く", { nav.go("/field/data") }) }) {
                if (parts.isEmpty()) Muted("保存すると、ここに種類と件数が表示されます。", 11)
                parts.forEach { p -> Row(Modifier.fillMaxWidth()) { Text(p.str("label").orEmpty(), fontSize = 12.sp, modifier = Modifier.weight(1f)); Muted("${p.int("count") ?: 0}件・${formatBytes(p.long("bytes"))}", 11) } }
            }
        }
        item {
            Panel(title = "保存済みの地図", subtitle = "地図タイル ${tiles.size}枚（${formatBytes(tiles.values.sumOf { it.bytes })}）", action = { SmallButton("開く", { nav.go("/field/map") }) }) {
                Muted("保存した範囲の地図、保存済みの停留所・撮影地点、端末の現在地を表示します。地図の新規範囲は通信が必要です。", 11)
            }
        }
    }
}

private val FILTERS = listOf("all" to "すべて", "pending" to "未同期", "failed" to "同期失敗", "conflict" to "競合", "synced" to "同期済み")

/** 現地記録（Web: field.records.jsx） */
@Composable
fun FieldRecordsScreen() {
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    val records by OfflineStore.records.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var message by remember { mutableStateOf("") }
    val visible = records.filter { it.bool("deleted") != true }.sortedByDescending { it.long("createdAt") ?: 0 }
    val filtered = visible.filter { r ->
        val st = r.str("syncState")
        val okFilter = when (filter) { "pending" -> st != OfflineStore.SYNC.synced; "failed" -> st == OfflineStore.SYNC.failed; "conflict" -> st == OfflineStore.SYNC.conflict; "synced" -> st == OfflineStore.SYNC.synced; else -> true }
        okFilter && (query.isBlank() || listOf("content", "vehicle", "vehicleType", "stopName", "spotName", "planName").joinToString(" ") { r.str(it).orEmpty() }.contains(query.trim()))
    }
    ScreenList {
        item { FieldHeader("/field/records") }
        item {
            Panel(title = "現地記録", subtitle = "端末に保存した記録の一覧です。未同期の記録は通信が戻ると自動で送信します。", action = { SmallButton(if (creating) "入力を閉じる" else "新しく記録", { creating = !creating; editing = null }, icon = Icons.Outlined.Add, primary = true) }) {
                if (creating || editing != null) {
                    Text(if (editing != null) "記録を編集" else "新しい記録", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    FieldNoteForm(editing, savedSpots(), savedPlans(), onSaved = { creating = false; editing = null; message = "端末に保存しました。" }, onCancel = { creating = false; editing = null })
                    editing?.let { e ->
                        val cur = records.firstOrNull { it.str("id") == e.str("id") } ?: e
                        RecordPhotoStrip(cur) { pid -> scope.launch { OfflineStore.removePhoto(e.str("id").orEmpty(), pid) } }
                        Muted("写真は追加できます。送信前の写真は端末に残しています。", 10)
                    }
                    RowDivider()
                }
                OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("本文・車番・停留所・撮影地点で絞り込み", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { FILTERS.forEach { (k, l) -> FilterChip(filter == k, { filter = k }, label = { Text(l, fontSize = 11.sp) }) } }
                if (message.isNotBlank()) Text(message, fontSize = 11.sp, color = Brand.Success)
                FieldNoteList(filtered, onEdit = { editing = it; creating = false }, onDelete = { r ->
                    confirm.ask("この記録を削除しますか？（${r.str("content")?.take(30)?.ifBlank { null } ?: "本文なし"}）${if (r.str("serverId") != null) "\nサーバー側の記録も、次の同期で削除します。" else ""}") {
                        scope.launch { val mode = OfflineStore.deleteRecord(r.str("id").orEmpty()); message = if (mode == "queued") "削除待ちとして保存しました。通信が戻るとサーバー側も削除します。" else "端末から削除しました。" }
                    }
                }, emptyText = if (visible.isNotEmpty()) "条件に合う記録がありません。" else "まだ記録はありません。")
            }
        }
    }
}

private val CONFLICT_FIELDS = listOf("content" to "本文", "kind" to "種類", "occurredAt" to "記録日時", "lat" to "緯度", "lon" to "経度", "vehicle" to "車両", "stopName" to "停留所", "spotName" to "撮影地点", "planName" to "遠征計画", "photoCount" to "写真の枚数")
private fun cell(v: JsonElement?): String { val s = v.text; val n = s.toLongOrNull(); return when { s.isBlank() -> "—"; n != null && n > 1_000_000_000_000 -> BusFormat.dateTime(n); else -> s } }

/** 同期競合の解決（Web: field.conflicts.jsx + ConflictPanel.jsx） */
@Composable
fun FieldConflictsScreen() {
    val scope = rememberCoroutineScope()
    val online by OfflineStore.online.collectAsState()
    val conflicts by OfflineStore.conflicts.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val selected = conflicts.firstOrNull { it.str("id") == selectedId } ?: conflicts.firstOrNull()
    ScreenList {
        item { FieldHeader("/field/conflicts") }
        item {
            Panel(title = "⚠ 同期競合の解決", subtitle = "端末とサーバーの両方で同じ記録が変更された場合、勝手に上書きせずにここで選んでもらいます。どちらを選んでも、もう一方の内容を消さない選択肢（両方を残す）があります。") {
                if (conflicts.isEmpty()) { Muted("競合はありません。端末側とサーバー側の両方で変更された記録があると、ここに表示されます。", 12); return@Panel }
                Text("競合 ${conflicts.size}件", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                conflicts.forEach { c ->
                    Column(Modifier.fillMaxWidth().clickable { selectedId = c.str("id") }.padding(vertical = 4.dp)) {
                        Text(c.objAt("local").str("content")?.ifBlank { null } ?: "（本文なし）", fontSize = 12.sp, fontWeight = if (c == selected) FontWeight.Bold else FontWeight.Normal, color = if (c == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Muted("端末 ${c.objAt("local").long("updatedAt")?.let { BusFormat.dateTime(it) } ?: "—"} / サーバー ${c.objAt("server").long("updatedAt")?.let { BusFormat.dateTime(it) } ?: "—"}", 10)
                    }
                }
                selected?.let { s ->
                    RowDivider()
                    Text("同じ記録が端末とサーバーの両方で変更されています", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.Destructive)
                    if (!online) Muted("オフライン中は選べません。通信が戻ってから選んでください（どちらの内容も削除していません）。", 11)
                    val local = s.objAt("local"); val server = s.objAt("server")
                    DataTable(listOf(Col("項目", 100.dp), Col("📱 端末側", 150.dp), Col("🖥 サーバー側", 150.dp)),
                        listOf(listOf("最終編集", cell(local?.get("updatedAt")), cell(server?.get("updatedAt")))) + CONFLICT_FIELDS.map { (k, l) ->
                            val diff = k in setOf("content", "occurredAt", "lat", "lon", "vehicle", "stopName", "spotName", "planName") && local?.get(k).text != server?.get(k).text
                            listOf(l + if (diff) "（変更あり）" else "", cell(local?.get(k)), cell(server?.get(k)))
                        })
                    fun resolve(choice: String) {
                        busy = true; message = ""
                        scope.launch {
                            try {
                                OfflineStore.resolveConflict(s.str("id").orEmpty(), choice)
                                message = when (choice) { "local" -> "端末側の内容を採用し、サーバーへ保存しました。"; "server" -> "サーバー側の内容を採用しました。端末側の内容は破棄していません（写真はそのまま残ります）。"; else -> "両方を残しました。端末側は新しい記録として保存し、この記録はサーバー側の内容にしました。" }
                            } catch (e: Throwable) { if (e is CancellationException) throw e; message = e.message ?: "解決できませんでした" } finally { busy = false }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        SmallButton("端末側を採用", { resolve("local") }, enabled = !busy && online, primary = true)
                        SmallButton("サーバー側を採用", { resolve("server") }, enabled = !busy && online)
                        SmallButton("両方を残す", { resolve("both") }, enabled = !busy && online)
                    }
                    listOf("端末側を採用：端末の内容でサーバーを更新します。", "サーバー側を採用：端末の本文などをサーバーの内容に合わせます（端末に残っている写真は削除しません）。", "両方を残す：端末側を新しい記録として保存し、この記録はサーバー側の内容にします。").forEach { Muted("・$it", 10) }
                    if (message.isNotBlank()) Text(message, fontSize = 11.sp, color = Brand.Success)
                }
            }
        }
    }
}

/** 保存済みデータ（Web: field.data.jsx + Saved*） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldDataScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val online by OfflineStore.online.collectAsState()
    val parts by OfflineStore.parts.collectAsState()
    val byKey = parts.associateBy { it.str("key") }
    val savedAt = parts.maxOfOrNull { it.long("savedAt") ?: 0 } ?: 0
    @Composable fun Section(part: JsonObject?, title: String, fallback: String, content: @Composable () -> Unit) {
        Panel(title = title, subtitle = part?.let { "保存 ${BusFormat.dateTime(it.long("savedAt"))}・${it.int("count") ?: 0}件・${formatBytes(it.long("bytes"))}${it.str("note")?.takeIf { n -> n.isNotBlank() }?.let { n -> "（$n）" } ?: ""}" } ?: "保存されていません") {
            if (part != null) content() else Muted(fallback, 12)
        }
    }
    ScreenList {
        item { FieldHeader("/field/data") }
        item {
            Panel(title = "保存済みデータ", subtitle = if (savedAt > 0) "最終データ更新 ${BusFormat.dateTime(savedAt)}／保存した時点の内容です。取得日時より新しい情報は表示できません。" else "まだ保存していません。出発前にオフライン設定から保存してください。", action = { SmallButton("保存・更新", { nav.go("/settings/offline") }) }) {
                Muted(if (online) "オンラインでも、保存した内容はそのまま確認できます。" else "オフライン中です。ここに表示されるのは保存済みの内容のみで、リアルタイムの車両位置は取得できません。", 11)
                if (parts.isEmpty()) Muted("「保存・更新」から、地域・範囲・期間・種類を指定してデータを保存してください。", 11)
            }
        }
        item {
            Section(byKey["vehicles"], "🚌 車両一覧・基本情報・最終位置", "車両情報が保存されていません。保存すると、車番・登録番号・型式と最後に取得した位置を表示します。") {
                var q by remember { mutableStateOf("") }
                var memoFor by remember { mutableStateOf<String?>(null) }
                var memo by remember { mutableStateOf("") }
                val rows = byKey["vehicles"]?.objAt("rows")
                val registry = rows.items("registry"); val current = rows.items("current").associateBy { it.str("car_code") }
                val gpsRows = byKey["gps"].items("rows")
                OutlinedTextField(q, { q = it }, placeholder = { Text("車番・登録番号・型式で検索", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Muted("登録 ${registry.size}台・最終位置 ${current.size}台・GPS履歴 ${gpsRows.size}件", 10)
                registry.filter { r -> q.isBlank() || listOf("car_code", "name", "plate", "model", "note").joinToString(" ") { r.str(it).orEmpty() }.contains(q.trim()) }.take(80).forEach { r ->
                    val code = r.str("car_code"); val g = current[code]
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${code ?: "車番なし"} ${r.str("name").orEmpty()} ${r.str("plate").orEmpty()}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { memoFor = code }) { Text("メモ", fontSize = 11.sp) }
                        }
                        val at = g.long("regist_at")
                        Muted("${r.str("model") ?: "型式なし"}　" + if (g?.dbl("lat") != null) "位置 ${"%.5f".format(g.dbl("lat"))}, ${"%.5f".format(g.dbl("lon"))}（${at?.let { BusFormat.dateTime(it) } ?: g.str("regist_time") ?: "時刻不明"} 取得${if (at != null && System.currentTimeMillis() - at < 30 * 60_000) "" else "・最新ではありません"}）" else "位置情報なし", 10)
                        r.str("note")?.takeIf { it.isNotBlank() }?.let { Muted("メモ：$it", 10) }
                        if (memoFor == code) Column {
                            OutlinedTextField(memo, { memo = it }, placeholder = { Text("この車両についてのメモ（オフラインでも保存できます）", fontSize = 12.sp) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                            Row {
                                SmallButton("記録する", { scope.launch { OfflineStore.createRecord(mapOf("kind" to "vehicle", "vehicle" to (code ?: r.str("name")), "content" to memo.trim())); memo = ""; memoFor = null; OfflineStore.autoSync("after-record") } }, enabled = memo.isNotBlank(), primary = true)
                                TextButton(onClick = { memoFor = null }) { Text("やめる", fontSize = 11.sp) }
                            }
                        }
                    }
                }
                if (gpsRows.isNotEmpty()) {
                    Text("GPS履歴（保存分・直近40件）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    gpsRows.takeLast(40).reversed().forEach { g -> Muted("${g.long("at")?.let { BusFormat.dateTime(it) } ?: "時刻不明"}　${g.str("vehicle").orEmpty()}　${g.str("stop") ?: g.str("route").orEmpty()}　${g.dbl("lat")?.let { "%.4f, %.4f".format(it, g.dbl("lon")) } ?: "位置なし"}", 10) }
                }
            }
        }
        item {
            Section(byKey["stops"] ?: byKey["spots"], "📍 停留所・撮影地点の検索", "地点情報が保存されていません。") {
                var q by remember { mutableStateOf("") }
                var tab by remember { mutableStateOf("all") }
                val stops = byKey["stops"].items("rows"); val spots = byKey["spots"].items("rows")
                val base = spots.firstOrNull() ?: stops.firstOrNull()
                val sun = produceState<JsonElement?>(null, base) { value = Logic.call("bus/solar", "solarPosition", System.currentTimeMillis(), base.dbl("lat") ?: 34.6837, base.dbl("lon") ?: 135.827) }.value
                val sunCompass = produceState("", sun) { value = sun.dbl("azimuth")?.let { Logic.call("bus/solar", "compass", it).text }.orEmpty() }.value
                val all = spots.map { Triple("撮影地点", it, it.str("note") ?: it.str("place_name").orEmpty()) } + stops.map { Triple("停留所", it, it.str("code")?.let { c -> "コード $c" }.orEmpty()) }
                OutlinedTextField(q, { q = it }, placeholder = { Text("停留所名・撮影地点名で検索", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("all" to "すべて ${all.size}", "spots" to "撮影地点 ${spots.size}", "stops" to "停留所 ${stops.size}").forEach { (k, l) -> FilterChip(tab == k, { tab = k }, label = { Text(l, fontSize = 11.sp) }) } }
                val rows = all.filter { (k, r, n) -> (tab == "all" || k == if (tab == "spots") "撮影地点" else "停留所") && (q.isBlank() || "${r.str("name")} $n".contains(q.trim())) }
                if (rows.isEmpty()) Muted("該当する地点がありません（保存した範囲のみ表示しています）。", 11)
                rows.take(200).forEach { (k, r, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { Text(r.str("name").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold); Tag(k, if (k == "撮影地点") INFO else Brand.MutedFg) }
                            Muted("${n}　${r.dbl("lat")?.let { "%.4f, %.4f".format(it, r.dbl("lon")) } ?: "座標なし"}", 10)
                        }
                        if (k == "撮影地点" && r.dbl("azimuth") != null && sun != null) {
                            val light = produceState<JsonElement?>(null, sun, r) { value = Logic.call("bus/solar", "lightingState", jsonOf("sunAzimuth" to sun.dbl("azimuth"), "sunAltitude" to sun.dbl("altitude"), "busHeading" to r.dbl("azimuth"))) }.value
                            light?.str("label")?.let { Tag(it, when (light.str("tone")) { "success" -> Brand.Success; "warning" -> Brand.Warning; "danger" -> Brand.Destructive; "info" -> INFO; else -> Brand.MutedFg }) }
                        }
                    }
                }
                if (spots.isNotEmpty() && sun != null) Muted("☀ 現在の太陽：${sunCompass}（方位 ${sun.dbl("azimuth")?.let { "%.0f".format(it) }}°）・高度 ${sun.dbl("altitude")?.let { "%.1f".format(it) }}°。撮影地点の順光判定は、保存時のカメラ方位と端末内で計算した太陽位置の比較による推定です（建物・地形による日陰は判定できません）。", 10)
                Muted("保存した範囲（半径内）の地点のみです。範囲外は保存されていません。", 10)
            }
        }
        item {
            Section(byKey["plans"], "📅 遠征計画・予定", "遠征計画が保存されていません。") {
                val plans = byKey["plans"]?.objAt("rows").items("plans"); val days = byKey["plans"]?.objAt("rows").items("days")
                if (plans.isEmpty()) Muted("保存された遠征計画がありません。", 12)
                plans.forEach { p ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Text(p.str("title").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold); Tag(when (p.str("status")) { "confirmed" -> "確定"; "done" -> "実施済み"; else -> "予定" }, INFO) }
                        Muted("${p.str("start_date")} 〜 ${p.str("end_date")}${p.str("depart_time")?.let { "・出発 $it" } ?: ""}${p.str("return_time")?.let { "・帰着 $it" } ?: ""}${p.int("nights")?.let { "・${it}泊" } ?: ""}", 10)
                        p.str("lodging_name")?.takeIf { it.isNotBlank() }?.let { Muted("宿泊：$it${p.str("lodging_note")?.takeIf { n -> n.isNotBlank() }?.let { n -> "（$n）" } ?: ""}", 10) }
                        p.str("note")?.takeIf { it.isNotBlank() }?.let { Muted("メモ：$it", 10) }
                        days.filter { it.str("plan_id") == p.str("id") }.forEach { d ->
                            val lines = runCatching { (app.narabuspulse.core.Json.parse(d.str("itinerary") ?: "[]") as JsonArray).map { r -> r.str("time")?.let { t -> "$t ${r.str("title") ?: r.str("label") ?: r.str("name") ?: r.str("text").orEmpty()}" } ?: r.text } }.getOrDefault(listOfNotNull(d.str("itinerary")))
                            Muted("${d.str("date")}${d.str("weekday")?.let { "（$it）" } ?: ""}${d.str("note")?.let { "　$it" } ?: ""}${if (lines.isNotEmpty()) "　" + lines.take(4).joinToString(" / ") else ""}", 10)
                        }
                    }
                }
            }
        }
        item {
            Section(byKey["weather"] ?: byKey["radar"], "⛅ 保存時点の気象", "気象情報が保存されていません。") {
                val rows = byKey["weather"]?.objAt("rows"); val radar = byKey["radar"]?.objAt("rows")
                val fetchedAt = rows.long("fetchedAt") ?: 0
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Muted("取得日時 ${if (fetchedAt > 0) BusFormat.dateTime(fetchedAt) else "不明"}", 11)
                    if (fetchedAt == 0L || System.currentTimeMillis() - fetchedAt > 6 * 3_600_000) Tag("保存時点の情報です（現在の天気ではありません）", Brand.Warning)
                }
                fun v(x: Double?, u: String) = x?.let { "${fmtNum(it)}$u" } ?: "—"
                if (rows == null) Muted("保存された天気予報がありません（保存時に取得できなかった可能性があります）。", 11)
                else {
                    Muted("地点 ${rows.dbl("center.lat")?.let { "%.4f".format(it) }}, ${rows.dbl("center.lon")?.let { "%.4f".format(it) }}${rows.dbl("elevation")?.let { "・標高 ${fmtNum(it)}m" } ?: ""}${rows.str("source")?.let { "・$it" } ?: ""}", 10)
                    rows.objAt("current")?.let { c -> Muted("気温 ${v(c.dbl("temp"), "℃")}　風速 ${v(c.dbl("wind"), "m/s")}　積雪深 ${v(c.dbl("snowDepth"), "cm")}　降水確率 ${v(c.dbl("pop"), "%")}", 11) }
                    DataTable(listOf(Col("日付", 90.dp), Col("最高", 52.dp, true), Col("最低", 52.dp, true), Col("降水", 52.dp, true), Col("風", 60.dp, true), Col("降雪", 52.dp, true), Col("積雪", 52.dp, true)),
                        rows.items("days").take(14).map { d -> listOf(d.str("date").orEmpty(), v(d.dbl("tmax"), "℃"), v(d.dbl("tmin"), "℃"), v(d.dbl("pop"), "%"), v(d.dbl("wind"), "m/s"), v(d.dbl("snow"), "cm"), v(d.dbl("snowDepth"), "cm")) }, boldFirst = false)
                    Muted("「—」は取得できなかった項目です（推測値では埋めていません）。雨雲・降雪の面的な情報は保存していません。", 10)
                }
                Text("保存時点の雨雲・雲のコマ", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                if (radar == null) Muted("保存されていません。", 11)
                else {
                    Muted("記録 ${radar.long("fetchedAt")?.let { BusFormat.dateTime(it) } ?: "不明"}・雨雲 ${radar.list("rain.frames").size}コマ・雲 ${radar.list("cloud.frames").size}コマ", 10)
                    Muted("雨雲：${if (radar.bool("rain.ok") == true) radar.list("rain.frames").firstOrNull().long("at")?.let { BusFormat.dateTime(it) } ?: "時刻なし" else radar.str("rain.error") ?: "取得できませんでした"}　雲：${if (radar.bool("cloud.ok") == true) radar.list("cloud.frames").firstOrNull().long("at")?.let { BusFormat.dateTime(it) } ?: "時刻なし" else radar.str("cloud.error") ?: "取得できませんでした"}", 10)
                    Muted("画像（タイル）は保存していません。オフライン中はレーダー画像を表示できません（コマの時刻のみ）。", 10)
                }
            }
        }
    }
}

/** 保存済みの地図（Web: field.map.jsx + OfflineTileMap）。保存したタイルは端末の地図キャッシュから表示する。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldMapScreen() {
    val nav = LocalNav.current
    val online by OfflineStore.online.collectAsState()
    val parts by OfflineStore.parts.collectAsState()
    val areas by OfflineStore.areas.collectAsState()
    val byKey = parts.associateBy { it.str("key") }
    var center by remember { mutableStateOf(areas.firstOrNull()?.objAt("center")?.let { Triple(it.dbl("lat") ?: 34.6837, it.dbl("lon") ?: 135.827, it.str("name").orEmpty()) } ?: Triple(34.6837, 135.827, "近鉄奈良駅")) }
    var device by remember { mutableStateOf<Triple<Double, Double, Float?>?>(null) }
    var deviceAt by remember { mutableStateOf(0L) }
    var geoBusy by remember { mutableStateOf(false) }
    var geoError by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Triple<String, String, Pair<Double, Double>>?>(null) }
    var showRoutes by remember { mutableStateOf(true) }
    val locate = rememberLocationRequest { loc -> geoBusy = false; if (loc == null) geoError = "現在地を取得できませんでした（位置情報が許可されていないか、GPSが使えない状態です）。" else { device = Triple(loc.lat, loc.lon, loc.accuracy); deviceAt = System.currentTimeMillis(); center = Triple(loc.lat, loc.lon, "端末の現在地") } }
    val stops = byKey["stops"].items("rows").filter { it.dbl("lat") != null && it.dbl("lon") != null }
    val spots = byKey["spots"].items("rows").filter { it.dbl("lat") != null && it.dbl("lon") != null }
    val routes = remember(byKey["routes"]) {
        byKey["routes"].items("rows").take(80).mapNotNull { r -> runCatching { (app.narabuspulse.core.Json.parse(r.str("path") ?: "[]") as JsonArray).take(260).mapNotNull { p -> (p as? JsonArray)?.let { a -> a.getOrNull(0)?.text?.toDoubleOrNull()?.let { la -> a.getOrNull(1)?.text?.toDoubleOrNull()?.let { lo -> la to lo } } } ?: p.dbl("lat")?.let { la -> p.dbl("lon")?.let { lo -> la to lo } } } }.getOrNull()?.takeIf { it.size > 1 } }
    }
    ScreenList {
        item { FieldHeader("/field/map") }
        item {
            Panel(title = "保存済みの地図", subtitle = "端末に保存したタイルを優先して表示します。保存していない範囲はオフラインでは表示されません。", action = {
                Column(horizontalAlignment = Alignment.End) { SmallButton(if (geoBusy) "取得中…" else "現在地", { geoBusy = true; geoError = ""; locate() }, enabled = !geoBusy, icon = Icons.Outlined.MyLocation); SmallButton("地図を保存", { nav.go("/settings/offline") }, modifier = Modifier.padding(top = 4.dp)) }
            }) {
                if (areas.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    areas.forEach { a -> val c = a.objAt("center"); FilterChip(center.third == a.str("name"), { center = Triple(c.dbl("lat") ?: 34.68, c.dbl("lon") ?: 135.82, a.str("name").orEmpty()) }, label = { Text("${a.str("name")}（タイル${a.int("tileCount") ?: 0}枚）", fontSize = 10.sp) }) }
                }
                val pins = stops.mapIndexed { i, s -> MapPin("s$i", s.dbl("lat")!!, s.dbl("lon")!!, s.str("name").orEmpty(), "停留所", color = INFO, size = 9.dp) } +
                    spots.mapIndexed { i, s -> MapPin("p$i", s.dbl("lat")!!, s.dbl("lon")!!, s.str("name").orEmpty(), "撮影地点", color = Brand.Orange, size = 12.dp) } +
                    listOfNotNull(device?.let { MapPin("me", it.first, it.second, "端末の現在地", color = Color(0xFF2E7D32), size = 14.dp) })
                OsmMap(Modifier.fillMaxWidth().height(360.dp), pins = pins, lines = if (showRoutes) routes.map { MapLine(it, INFO, 5f) } else emptyList(),
                    circles = listOfNotNull(device?.third?.let { acc -> MapCircle(device!!.first, device!!.second, acc.toDouble(), Color(0xFF2E7D32)) }),
                    center = center.first to center.second, zoom = 14.0, fitKey = center,
                    onPinClick = { p -> val kind = if (p.id.startsWith("s")) "停留所" else if (p.id.startsWith("p")) "撮影地点" else return@OsmMap; selected = Triple(kind, p.title, p.lat to p.lon) })
                Text("端末の現在地", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                device?.let { d -> Muted("${"%.5f".format(d.first)}, ${"%.5f".format(d.second)}・取得 ${BusFormat.dateTime(deviceAt)}${d.third?.let { "・精度 約${Math.round(it)}m" } ?: ""}", 11) } ?: Muted(geoError.ifBlank { "「現在地」を押すと端末のGPSで取得します。インターネット通信は使いません。" }, 11)
                Text("📏 地点間の距離", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                selected?.let { (k, n, ll) ->
                    val km = device?.let { OfflineStore.distanceKm(it.first, it.second, ll.first, ll.second) }
                    Muted("$k「$n」まで ${km?.let { if (it < 1) "${Math.round(it * 1000)}m" else "%.2fkm".format(it) } ?: "現在地を取得すると表示されます"}${device?.let { "（直線距離・${"%.4f".format(it.first)}, ${"%.4f".format(it.second)} から）" } ?: ""}", 11)
                } ?: Muted("地図上の停留所・撮影地点をタップすると距離を表示します。", 11)
                if (routes.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(showRoutes, { showRoutes = it }); Text("保存済みの走行経路（${routes.size}区間）を表示", fontSize = 12.sp) }
                Muted("保存済み：停留所 ${stops.size}件・撮影地点 ${spots.size}件・経路 ${routes.size}区間。表示されない地点は保存されていません。${if (online) "" else " オフライン中のため、新しい地図の取得はできません。"}", 10)
            }
        }
    }
}

private val RADIUS = listOf(1, 2, 3, 5, 10)
private val DAYS = listOf(7, 14, 30, 60)
private val TYPES = listOf("map" to "地図・地点（停留所・撮影地点）", "bus" to "バス（車両・運行記録・GPS履歴）", "plans" to "遠征・予定", "weather" to "気象（予報・雨雲の時刻）")

private fun countRows(rows: JsonElement?): Int = when (rows) { is JsonArray -> rows.size; is JsonObject -> rows.values.sumOf { (it as? JsonArray)?.size ?: 0 }; else -> 0 }

/** オフライン現地活動モードの設定（Web: settings.offline.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfflineSettingsScreen() {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    val online by OfflineStore.online.collectAsState()
    val settings by OfflineStore.settings.collectAsState()
    val stats by OfflineStore.stats.collectAsState()
    val sync by OfflineStore.sync.collectAsState()
    val parts by OfflineStore.parts.collectAsState()
    val areas by OfflineStore.areas.collectAsState()
    val tiles by OfflineStore.tiles.collectAsState()
    val records by OfflineStore.records.collectAsState()
    val usage = remember(parts, tiles, records) { OfflineStore.usage() }
    val stat = remember { runCatching { StatFs(context.filesDir.path) }.getOrNull() }
    val avail = stat?.availableBytes; val totalBytes = stat?.totalBytes
    val usedPct = if (avail != null && totalBytes != null && totalBytes > 0) ((totalBytes - avail) * 100 / totalBytes).toInt() else 0
    val savedAt = parts.maxOfOrNull { it.long("savedAt") ?: 0 } ?: 0
    // 事前ダウンロードの状態
    var cLat by remember { mutableStateOf(34.6837) }; var cLon by remember { mutableStateOf(135.827) }; var cName by remember { mutableStateOf("近鉄奈良駅") }
    var radiusKm by remember { mutableStateOf(2) }; var days by remember { mutableStateOf(14) }
    var types by remember { mutableStateOf(TYPES.map { it.first }.toSet()) }
    var q by remember { mutableStateOf("") }; var results by remember { mutableStateOf(listOf<JsonElement>()) }; var searchErr by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<JsonElement?>(null) }
    var phase by remember { mutableStateOf("idle") }
    var progress by remember { mutableStateOf(Triple(0, "", "")) }
    var failed by remember { mutableStateOf(listOf<Triple<String, String, String>>()) }
    var message by remember { mutableStateOf("") }
    var tileInfo by remember { mutableStateOf<OfflineStore.TileResult?>(null) }
    var stop by remember { mutableStateOf(false) }
    val busy = phase == "estimating" || phase == "downloading"
    fun opts() = jsonOf("center" to jsonOf("lat" to cLat, "lon" to cLon, "name" to cName), "radiusKm" to radiusKm, "days" to days, "types" to types.toList())
    suspend fun savePartFrom(res: JsonElement) {
        OfflineStore.savePart(jsonOf("key" to res.str("key"), "label" to res.str("label"), "group" to res.str("group"), "rows" to (res as? JsonObject)?.get("rows"), "bytes" to (res.long("bytes") ?: 0),
            "count" to countRows((res as? JsonObject)?.get("rows")), "savedAt" to (res.long("savedAt") ?: System.currentTimeMillis()), "note" to res.str("note").orEmpty(),
            "center" to jsonOf("lat" to cLat, "lon" to cLon, "name" to cName), "radiusKm" to radiusKm, "days" to days))
    }
    fun run() {
        if (!online) { phase = "error"; message = "オフラインのため保存できません。通信できる場所で保存してください。"; return }
        if (types.isEmpty()) { message = "保存するデータの種類を選んでください。"; return }
        stop = false; message = ""; failed = emptyList(); phase = "estimating"
        scope.launch {
            val p = try { Api.rpc("offline/server-fns#getOfflinePlan", opts()).also { plan = it } } catch (e: Throwable) { if (e is CancellationException) throw e; phase = "error"; message = e.userMessage().ifBlank { "保存するデータを確認できませんでした" }; return@launch }
            val available = p.items("parts").filter { it.bool("available") == true }
            val withTiles = "map" in types
            val total = available.size + if (withTiles) 1 else 0
            var done = 0; var savedBytes = 0L; val savedKeys = mutableListOf<String>(); val fails = mutableListOf<Triple<String, String, String>>()
            phase = "downloading"
            for (part in available) {
                if (stop) break
                progress = Triple(done * 100 / total, "${part.str("label")} を取得しています", "${done + 1}/$total")
                try {
                    val res = Api.rpc("offline/server-fns#getOfflinePart", jsonOf("key" to part.str("key"), "center" to jsonOf("lat" to cLat, "lon" to cLon, "name" to cName), "radiusKm" to radiusKm, "days" to days))
                    savePartFrom(res); savedBytes += res.long("bytes") ?: 0; savedKeys += res.str("key").orEmpty()
                } catch (e: Throwable) { if (e is CancellationException) throw e; fails += Triple(part.str("key").orEmpty(), part.str("label").orEmpty(), e.userMessage().ifBlank { "取得できませんでした" }) }
                done++
            }
            val areaId = "area-${System.currentTimeMillis()}"
            var t: OfflineStore.TileResult? = null
            if (withTiles && !stop) {
                t = OfflineStore.prefetchTiles(cLat, cLon, minOf(radiusKm, 3).toDouble(), listOf(12, 13, 14), settings.tileMaxTiles, areaId, { stop }) { r, i -> progress = Triple(minOf(99, ((done + i.toDouble() / maxOf(1, r.total)) / total * 100).toInt()), "地図タイル ${r.saved + r.skipped}/${r.total}", "${done + 1}/$total") }
                tileInfo = t; savedBytes += t.bytes; done++
            }
            OfflineStore.saveArea(jsonOf("id" to areaId, "name" to cName.ifBlank { "%.4f, %.4f".format(cLat, cLon) }, "center" to jsonOf("lat" to cLat, "lon" to cLon, "name" to cName), "radiusKm" to radiusKm, "days" to days, "types" to types.toList(),
                "savedAt" to System.currentTimeMillis(), "bytes" to savedBytes, "count" to p.items("parts").filter { it.str("key") in savedKeys }.sumOf { it.int("count") ?: 0 },
                "tileCount" to (t?.let { it.saved + it.skipped } ?: 0), "tileBytes" to (t?.bytes ?: 0), "tileFailed" to (t?.failed ?: 0)))
            failed = fails
            when {
                stop -> { phase = "paused"; message = "保存を一時停止しました。再開すると続きから保存します（保存済みのタイルは取り直しません）。" }
                fails.isNotEmpty() -> { phase = "error"; message = "${fails.size}種類のデータを取得できませんでした。端末に保存できた分はそのまま使えます。" }
                else -> { phase = "done"; message = "保存しました（${formatBytes(savedBytes)}・${BusFormat.dateTime(System.currentTimeMillis())}）。オフラインでもこの内容を表示できます。" }
            }
        }
    }
    ScreenList {
        item { SettingsHeader("/settings/offline") }
        item {
            Panel(title = "オフライン現地活動モード", subtitle = "山間部・トンネル・電波の弱い場所でも、保存したデータの閲覧と現地記録ができます。記録は端末に保存し、通信が戻ると自動で同期します。", action = {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(if (settings.enabled) "ON" else "OFF", fontSize = 12.sp, fontWeight = FontWeight.Bold); Switch(settings.enabled, { OfflineStore.saveSettings(settings.copy(enabled = it)) }) }
            }) {
                Text(if (online) "📶 オンライン" else "📵 オフライン", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (online) Brand.Success else Brand.Warning)
                Muted(if (online) "通常のオンライン動作です。" else "保存済みの地図・車両・地点・気象のみ利用できます。新しい位置情報は取得できません。", 11)
                KeyValue("最終同期成功", if (settings.lastSyncAt > 0) BusFormat.dateTime(settings.lastSyncAt) else "まだ同期していません")
                KeyValue("未同期（同期待ち）", "${stats.pending}件"); KeyValue("同期失敗", "${stats.failed}件"); KeyValue("同期競合", "${stats.conflict}件")
                KeyValue("未同期の写真", formatBytes(stats.pendingBytes)); KeyValue("最終データ更新", if (savedAt > 0) BusFormat.dateTime(savedAt) else "未保存")
                Text("💾 端末の保存容量", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                KeyValue("保存済み地図", formatBytes(usage.tileBytes)); KeyValue("保存済みデータ", formatBytes(usage.partBytes)); KeyValue("現地記録（文字）", formatBytes(usage.recordBytes)); KeyValue("写真（未送信）", formatBytes(usage.photoBytes))
                KeyValue("端末の空き容量", avail?.let { formatBytes(it) } ?: "取得できません")
                if (totalBytes != null) { LinearProgressIndicator(progress = { usedPct / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)); Muted(if (usedPct >= settings.warnPercent) "端末容量の使用率が${usedPct}%です（警告のしきい値 ${settings.warnPercent}%）。不要なキャッシュを削除してください。" else "使用率 ${usedPct}%（警告は ${settings.warnPercent}%）", 10) }
            }
        }
        item {
            Panel(title = "同期", subtitle = "通信が戻ると、端末に保存した記録を1件ずつ順番に送ります。失敗した記録は端末に残し、間隔をあけて再試行します。") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("手動同期", { scope.launch { OfflineStore.syncNow(force = true) } }, enabled = online && !sync.running, icon = Icons.Outlined.Refresh)
                    if (settings.paused) SmallButton("再開", { OfflineStore.setPaused(false) }, icon = Icons.Outlined.PlayArrow) else SmallButton("一時停止", { OfflineStore.setPaused(true) }, icon = Icons.Outlined.Pause)
                }
                KeyValue("同期待ち", "${stats.pending}件"); KeyValue("失敗", "${stats.failed}件"); KeyValue("競合", "${stats.conflict}件"); KeyValue("最終同期", if (settings.lastSyncAt > 0) BusFormat.dateTime(settings.lastSyncAt) else "—")
                if (sync.running) { LinearProgressIndicator(progress = { if (sync.total > 0) sync.index.toFloat() / sync.total else 0f }, modifier = Modifier.fillMaxWidth()); Muted("同期中 ${sync.index}/${sync.total}${if (sync.label.isNotBlank()) "：${sync.label}" else ""}", 10) }
                (sync.lastError.ifBlank { settings.lastSyncError }).takeIf { it.isNotBlank() && !sync.running }?.let { Text("⚠ $it", fontSize = 11.sp, color = Brand.Warning) }
                if (!online) Muted("オフラインのため同期は行いません。記録は端末に保存され、通信が戻ると自動で送信されます。", 10)
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(settings.autoSync, { OfflineStore.saveSettings(settings.copy(autoSync = it)) }); Text("  通信復旧後に自動で同期する", fontSize = 12.sp) }
            }
        }
        item {
            Panel(title = "事前ダウンロード", subtitle = "現地へ出発する前に、指定した範囲のデータを端末へ保存します。保存した内容だけがオフラインで使えます。", action = {
                SmallButton(if (phase == "estimating") "見積り中…" else "容量を見積る", {
                    phase = "estimating"; message = ""; failed = emptyList()
                    scope.launch { try { plan = Api.rpc("offline/server-fns#getOfflinePlan", opts()); phase = "idle" } catch (e: Throwable) { if (e is CancellationException) throw e; phase = "error"; message = e.userMessage().ifBlank { "容量の見積りに失敗しました" } } }
                }, enabled = !busy)
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it }, label = { Text("地名・駅名・停留所名から指定", fontSize = 11.sp) }, placeholder = { Text("例：近鉄奈良駅、八木駅、薬師寺", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
                    SmallButton("検索", {
                        searchErr = ""
                        scope.launch { try { results = Api.rpc("offline/server-fns#searchOfflinePlaces", jsonOf("q" to q.trim())).items(); if (results.isEmpty()) searchErr = "該当する地点が見つかりませんでした（保存済みの停留所・撮影地点から探します）" } catch (e: Throwable) { if (e is CancellationException) throw e; searchErr = e.userMessage().ifBlank { "地点を検索できませんでした" } } }
                    }, enabled = !busy && q.isNotBlank(), icon = Icons.Outlined.Search, modifier = Modifier.padding(start = 6.dp))
                }
                if (searchErr.isNotBlank()) Text(searchErr, fontSize = 11.sp, color = Brand.Warning)
                results.forEach { r -> Text("${r.str("kind")}　${r.str("name")}　${"%.4f, %.4f".format(r.dbl("lat") ?: 0.0, r.dbl("lon") ?: 0.0)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().clickable { cLat = r.dbl("lat") ?: cLat; cLon = r.dbl("lon") ?: cLon; cName = r.str("name").orEmpty(); results = emptyList() }.padding(vertical = 4.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SelectField(RADIUS.map { it to "範囲 ${it}km" }, radiusKm, { radiusKm = it }); SelectField(DAYS.map { it to "期間 ${it}日分" }, days, { days = it }) }
                Muted("📍 地図から指定（地図をタップ）— ${cName.ifBlank { "中心未設定" }}（${"%.4f".format(cLat)}, ${"%.4f".format(cLon)}）", 11)
                OsmMap(Modifier.fillMaxWidth().height(220.dp), pins = listOf(MapPin("c", cLat, cLon, cName, color = Brand.Orange, size = 14.dp)), circles = listOf(MapCircle(cLat, cLon, radiusKm * 1000.0, Brand.Orange)),
                    center = cLat to cLon, zoom = 12.0, fitKey = cLat to cLon, onMapClick = { la, lo -> if (!busy) { cLat = la; cLon = lo; cName = "" } })
                Text("保存するデータ", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                TYPES.forEach { (k, l) -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(k in types, { types = if (it) types + k else types - k }, enabled = !busy); Text(l, fontSize = 12.sp) } }
                plan?.let { p ->
                    DataTable(listOf(Col("保存する内容", 160.dp), Col("件数", 64.dp, true), Col("容量", 72.dp, true), Col("状態", 200.dp)),
                        p.items("parts").map { pt -> val ok = pt.bool("available") == true; listOf(pt.str("label").orEmpty(), if (ok) "${pt.int("count") ?: 0}件" else "—", if (ok) formatBytes(pt.long("bytes")) else "—", if (ok) pt.str("note").orEmpty() else pt.str("note") ?: "取得できません") } +
                            (if ("map" in types) listOf(listOf("地図タイル（${minOf(radiusKm, 3)}km・ズーム14まで）", "最大${settings.tileMaxTiles}枚", "約${formatBytes(settings.tileMaxTiles * 15L * 1024)}", "利用者が指定した範囲のみ")) else emptyList()) +
                            listOf(listOf("合計（推定）", "${p.int("totals.count") ?: 0}件", formatBytes(p.long("totals.bytes")), "")), boldFirst = false)
                }
                if (phase == "downloading") { LinearProgressIndicator(progress = { progress.first / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)); Muted("${progress.third}　${progress.second}（${progress.first}%）", 10) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    SmallButton(if (busy) "保存中…" else "保存する／保存済みデータを更新", { run() }, enabled = !busy && online, icon = Icons.Outlined.Download, primary = true)
                    if (phase == "downloading") SmallButton("一時停止", { stop = true; phase = "paused" }, icon = Icons.Outlined.Pause)
                    if (phase == "paused" || phase == "error") SmallButton("再開（続きから）", { run() }, enabled = online, icon = Icons.Outlined.PlayArrow)
                    if (failed.isNotEmpty()) SmallButton("失敗した種類を再試行", {
                        phase = "downloading"
                        scope.launch {
                            val remain = mutableListOf<Triple<String, String, String>>()
                            failed.forEach { (k, l, _) -> try { savePartFrom(Api.rpc("offline/server-fns#getOfflinePart", jsonOf("key" to k, "center" to jsonOf("lat" to cLat, "lon" to cLon, "name" to cName), "radiusKm" to radiusKm, "days" to days))) } catch (e: Throwable) { if (e is CancellationException) throw e; remain += Triple(k, l, e.userMessage()) } }
                            failed = remain; phase = if (remain.isEmpty()) "done" else "error"; message = if (remain.isEmpty()) "失敗した種類を保存し直しました。" else "${remain.size}種類は取得できませんでした。"
                        }
                    }, enabled = online)
                    if (!online) Text("✕ オフライン中は保存できません", fontSize = 11.sp, color = Brand.Destructive)
                }
                if (message.isNotBlank()) Text(message, fontSize = 11.sp, color = if (phase == "error") Brand.Destructive else Brand.Success)
                failed.forEach { (_, l, e) -> Muted("・$l：$e", 10) }
                tileInfo?.let { Muted("地図タイル：保存 ${it.saved}枚 / 既に保存済み ${it.skipped}枚 / 失敗 ${it.failed}枚", 10) }
                Muted("地図タイルは OpenStreetMap の配信を利用しています。同サービスの利用規約では一括・大量のダウンロードが制限されているため、保存は「利用者が指定した狭い範囲・1回あたり最大${settings.tileMaxTiles}枚・ズーム14まで・間隔をあけた順次取得」に限定しています。より広い範囲をオフラインで使うには、ライセンスされたタイル配信の導入が必要です（未対応）。", 10)
            }
        }
        item {
            Panel(title = "端末の保存容量", subtitle = "オフライン用に保存したデータの量です。削除する前に、対象と容量を確認してください。未同期の記録は削除しません。", action = {
                SmallButton("未同期データをバックアップ", { scope.launch { runCatching { snackbar.showSnackbar("${Export.text(context, "offline-backup-${System.currentTimeMillis()}.json", "application/json", OfflineStore.backupJson())} に保存しました（写真の実体は含みません）") } } }, icon = Icons.Outlined.Backup)
            }) {
                KeyValue("地図データ", "${usage.tiles}枚・${formatBytes(usage.tileBytes)}"); KeyValue("保存済みデータ", "${usage.parts}種類・${formatBytes(usage.partBytes)}")
                KeyValue("現地記録", "${usage.records}件（未同期${stats.pending + stats.failed}件）・${formatBytes(usage.recordBytes)}"); KeyValue("写真（未送信）", "${usage.photos}枚・${formatBytes(usage.photoBytes)}")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    @Composable fun Num(label: String, v: Int, onSet: (Int) -> Unit) = OutlinedTextField(v.toString(), { s -> s.filter(Char::isDigit).toIntOrNull()?.let(onSet) }, label = { Text(label, fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Num("保存期間（日）", settings.retentionDays) { OfflineStore.saveSettings(settings.copy(retentionDays = it)) }
                    Num("容量警告（％）", settings.warnPercent) { OfflineStore.saveSettings(settings.copy(warnPercent = it)) }
                    Num("タイル上限（枚）", settings.tileMaxTiles) { OfflineStore.saveSettings(settings.copy(tileMaxTiles = it)) }
                }
                Text("保存済みの地域", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                if (areas.isEmpty()) Muted("まだ保存していません。上の「事前ダウンロード」から地域を指定してください。", 11)
                areas.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Column(Modifier.weight(1f)) { Text(a.str("name").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted("${a.int("radiusKm")}km・${a.int("days")}日分・${a.int("count") ?: 0}件・タイル${a.int("tileCount") ?: 0}枚・${formatBytes(a.long("bytes"))}・保存${BusFormat.dateTime(a.long("savedAt"))}", 10) }
                        SmallButton("削除", { confirm.ask("「${a.str("name")}」の地図タイル ${a.int("tileCount") ?: 0}枚（${formatBytes(a.long("tileBytes"))}）を削除しますか") { scope.launch { OfflineStore.deleteArea(a.str("id").orEmpty()); message = "「${a.str("name")}」を削除しました。" } } })
                    }
                }
                listOf(
                    Triple("retention", "保存期間を過ぎたデータを整理", "${settings.retentionDays}日より古い地図タイルと同期済みの記録（未同期の記録は消しません）"),
                    Triple("tiles", "地図タイルをすべて削除", "保存済みの地図を表示できなくなります（${usage.tiles}枚・${formatBytes(usage.tileBytes)}）"),
                    Triple("parts", "保存済みデータをすべて削除", "車両・地点・気象などのオフライン用データ（${usage.parts}種類・${formatBytes(usage.partBytes)}）"),
                ).forEach { (k, l, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Column(Modifier.weight(1f)) { Text(l, fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted(n, 10) }
                        SmallButton("選ぶ", { confirm.ask("$l\n$n") {
                            scope.launch {
                                message = when (k) {
                                    "retention" -> { val t = OfflineStore.pruneTiles(settings.retentionDays); val r = OfflineStore.retentionCleanup(settings.retentionDays); "保存期間（${settings.retentionDays}日）を過ぎた地図タイル ${t}枚と同期済みの記録 ${r}件を整理しました。" }
                                    else -> { OfflineStore.clearCategory(k); "${l.removeSuffix("をすべて削除")}を削除しました。" }
                                }
                            }
                        } })
                    }
                }
                if (message.isNotBlank()) Text(message, fontSize = 11.sp, color = Brand.Success)
                Muted("写真はアップロードが完了するまで端末に残します。未同期の記録・写真は、上の操作では削除されません（同期済みの古い記録のみ対象）。", 10)
            }
        }
        item { FeatureMatrix() }
        item {
            Panel(title = "保存方式と制限", subtitle = "この端末に保存される内容と、実装上の制限です。") {
                listOf("保存先はアプリの端末内領域です。記録・写真・地図タイル・保存済みデータを、アプリを閉じても残る形で保持します。",
                    "保存済みデータは「保存した時点の内容」です。車両の位置や気象は取得日時を必ず表示し、現在の情報として扱いません。",
                    "地図タイルは OpenStreetMap の利用規約に従い、指定範囲・上限枚数・順次取得に限定しています。広域のオフライン地図は未対応です。",
                    "気象警報・注意報は取得処理自体が未実装のため、オフラインでも表示できません。雨雲・雲の画像は保存せず、コマの時刻のみ保存します。",
                    "アプリのデータ消去や端末の空き容量不足では、未同期の記録が失われる可能性があります。同期またはバックアップを行ってください。",
                    "現在の設定：保存期間 ${settings.retentionDays}日 ／ 地図タイル上限 ${settings.tileMaxTiles}枚 ／ 容量警告 ${settings.warnPercent}%").forEach { Muted("・$it", 10) }
            }
        }
    }
}

private val FM_STATE = mapOf("offline" to ("オフライン利用可能" to Brand.Success), "saved" to ("保存済み情報のみ利用可能" to Brand.Warning), "online" to ("オンライン接続が必要" to INFO), "none" to ("未対応" to Brand.MutedFg))
private val FM_GROUPS = listOf(
    "🗺 地図・地点" to listOf(Triple("保存済み地図の表示", "offline", "保存した範囲のみ（タイル保存分）"), Triple("現在地の表示（端末GPS）", "offline", "端末のGPSで取得。通信は使わない"), Triple("保存済み地点・停留所の検索", "offline", "保存した停留所・撮影地点から検索"),
        Triple("保存済みの走行経路の表示", "offline", "保存した道路形状（経路）を地図に描画"), Triple("地点間の距離", "offline", "端末内で計算"), Triple("地図の新規範囲の読み込み", "online", "オンラインでのみ取得")),
    "🚌 バス・車両" to listOf(Triple("保存済み車両一覧・基本情報", "saved", "保存した時点の内容"), Triple("過去の運行記録・GPS履歴", "saved", "保存した期間分"), Triple("保存済み停留所一覧", "saved", "保存した範囲内"),
        Triple("リアルタイムの車両位置", "online", "奈良交通APIへの通信が必要"), Triple("車両の最新の運用（系統・行先）", "online", "シートの最新内容が必要")),
    "⛅ 気象" to listOf(Triple("保存地点の天気予報", "saved", "取得日時を表示し、古い場合は明示"), Triple("保存時点の雨雲・雲の時刻", "saved", "画像は保存しない（時刻のみ）"), Triple("気象警報・注意報", "none", "取得処理が未実装"), Triple("最新のレーダー画像", "online", "気象庁の配信を取得")),
    "💬 記録・支援" to listOf(Triple("メモ・写真・訪問・チェックリスト", "offline", "端末に保存し、復旧後に同期"), Triple("撮影地点のメモ・条件の記録", "offline", "端末に保存"), Triple("保存済みデータでの順光・方位の計算", "offline", "太陽位置は端末内で計算"),
        Triple("遠征計画の閲覧", "saved", "保存した計画・日別予定"), Triple("AI相談・AI検索", "online", "サーバーでの推論が必要")),
)

@Composable
private fun FeatureMatrix() {
    Panel(title = "オフラインで使える機能", subtitle = "通信が切れている間でも使える機能と、通信が必要な機能を区別して表示します。") {
        FM_GROUPS.forEach { (title, rows) ->
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            rows.forEach { (l, s, n) -> val (sl, sc) = FM_STATE.getValue(s); Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) { Tag(sl, sc); Column(Modifier.padding(start = 6.dp)) { Text(l, fontSize = 11.sp); Muted(n, 10) } } }
        }
        listOf("保存したデータには取得日時を付け、現在の情報として表示しません。", "端末のGPSが使えない場合、最後に取得した位置を現在地として表示しません。", "取得できなかった項目は「データなし」と表示し、推測値で補いません。").forEach { Muted("・$it", 10) }
    }
}

@Suppress("unused") private fun keepW() = Modifier.width(1.dp)
