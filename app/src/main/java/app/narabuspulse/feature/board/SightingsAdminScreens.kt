package app.narabuspulse.feature.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Session
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.ErrorText
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.predict.MiniHeader
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** 目撃情報マップ（Web: sightings.jsx + SightingMapCard + SightingMap） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SightingsScreen() {
    val nav = LocalNav.current
    var vehicle by remember { mutableStateOf("") }
    var garage by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf(listOf("", "", "", "")) }
    var picked by remember { mutableStateOf<JsonElement?>(null) }
    val q = rememberLoad("sightings", applied) { Api.rpc("board/server-fns#getVehicleSightings", jsonOf("vehicle" to applied[0], "garage" to applied[1], "from" to applied[2], "to" to applied[3])) }
    val points = q.data.items().filter { it.dbl("lat") != null && it.dbl("lon") != null }
    ScreenList {
        item { MiniHeader("目撃情報マップ", "投稿に位置情報が含まれる目撃情報だけを地図に表示します。位置情報のない投稿は地図に出ません。現在地を自動で付けることはありません。") }
        item {
            Panel {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(vehicle, { vehicle = it }, label = { Text("車両番号", fontSize = 12.sp) }, placeholder = { Text("12-5006") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(garage, { garage = it }, label = { Text("営業所", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Muted("開始日", 12); DateField(from, { from = it }, label = if (from.isBlank()) "未指定" else null)
                    Muted("終了日", 12); DateField(to, { to = it }, label = if (to.isBlank()) "未指定" else null)
                    if (from.isNotBlank() || to.isNotBlank()) TextButton(onClick = { from = ""; to = "" }) { Text("期間を解除", fontSize = 11.sp) }
                    SmallButton("地図に反映", { applied = listOf(vehicle, garage, from, to) }, icon = Icons.Outlined.Place, primary = true)
                }
            }
        }
        q.error?.let { item { ErrorText(it) } }
        item {
            Panel(title = "📍 位置情報が付いた投稿のみ表示しています（${points.size}件）") {
                if (points.isEmpty() && !q.loading) Muted("位置情報が付いた目撃情報がありません。", 12)
                else if (points.isNotEmpty()) OsmMap(Modifier.fillMaxWidth().height(320.dp),
                    pins = points.map { p -> MapPin(p.str("id").orEmpty(), p.dbl("lat")!!, p.dbl("lon")!!, p.str("vehicle_number")?.ifBlank { null } ?: p.str("title").orEmpty(), color = if (p.str("verify_status") == "confirmed") Brand.Success else Brand.Orange, size = 14.dp) },
                    center = points.map { it.dbl("lat")!! }.average() to points.map { it.dbl("lon")!! }.average(), zoom = if (points.size > 1) 11.0 else 15.0, fitKey = points.size,
                    onPinClick = { pin -> picked = points.firstOrNull { it.str("id") == pin.id } })
                if (q.loading) Muted("検索中…", 11)
                picked?.let { p ->
                    Column(Modifier.padding(top = 6.dp)) {
                        Text(p.str("title").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        p.str("vehicle_number")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = Brand.Orange) }
                        Muted("${CATEGORY_OPTIONS.firstOrNull { it.first == p.str("category") }?.second ?: "その他"}　投稿: ${fmtDateTime(p.long("at"))}${p.long("sighted_at")?.let { "　目撃: ${fmtDateTime(it)}" } ?: ""}", 10)
                        p.str("location_name")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
                        TextButton(onClick = { nav.go("/board/${p.str("id")}") }) { Text("投稿を見る", fontSize = 12.sp) }
                    }
                }
                points.take(12).forEach { p ->
                    RowDivider()
                    Column(Modifier.fillMaxWidth().clickable { nav.go("/board/${p.str("id")}") }.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text(p.str("title").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); VerifyBadge(p.str("verify_status")) }
                        Muted("${p.str("vehicle_number")?.ifBlank { null } ?: "車両番号なし"}・${fmtDateTime(p.long("sighted_at") ?: p.long("at"))}", 10)
                        p.str("location_name")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
                    }
                }
            }
        }
    }
}

private val FACT_LABEL = mapOf("garage" to "営業所", "vehicle_type" to "車両形式", "registration_number" to "登録番号", "manufacturer" to "メーカー")

/** 掲示板・車両情報の管理（Web: admin.board.jsx） */
@Composable
fun AdminBoardScreen() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gk = remember { guestKey(context) }
    val admin = Session.isAdmin
    val q = rememberLoad("admin-board", admin) { if (!admin) null else Api.rpc("board/server-fns#adminGetBoard", jsonOf("guestKey" to gk)) }
    var busyId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    fun act(id: String, block: suspend () -> Unit) {
        busyId = id; message = ""
        scope.launch { try { block(); q.reload() } catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "操作できませんでした" } } finally { busyId = "" } }
    }
    val onStatus: (String, String) -> Unit = { id, status -> act(id) { Api.rpc("board/server-fns#adminSetPostStatus", jsonOf("guestKey" to gk, "id" to id, "status" to status)) } }
    ScreenList {
        if (!admin) {
            item { MiniHeader("掲示板・車両情報の管理", "管理者専用の画面です。") }
            item { Panel(title = "🛡 管理者権限が必要です") { Muted("現在のアカウントには管理者権限がありません。権限はワークスペースの管理者が付与します。", 12) } }
            return@ScreenList
        }
        item { MiniHeader("掲示板・車両情報の管理", "投稿の非公開化、通報対応、AI・投稿から集めた情報の確認、スプレッドシート同期の実行と履歴をまとめて行います。") { SmallButton("車両一覧", { nav.go("/vehicle-db") }) } }
        if (message.isNotBlank()) item { ErrorText(message) }
        item { SyncPanel(gk) }
        if (q.loading && q.data == null) item { Muted("読み込み中…", 12) }
        q.error?.let { item { ErrorText(it) } }
        q.data?.let { d ->
            item { Panel(title = "通報された投稿", subtitle = "通報の多い順です。内容を確認し、必要に応じて非公開にしてください。") { AdminPosts(d.items("reported"), busyId, onStatus) } }
            item {
                Panel(title = "情報の確認（車両情報）", subtitle = "「確認済み」にした項目だけが車両の確定情報になります。矛盾がある場合は両方を確認してから判断してください。") {
                    val facts = d.items("facts")
                    if (facts.isEmpty()) Muted("確認待ちの情報はありません。", 12)
                    facts.forEachIndexed { i, f ->
                        if (i > 0) RowDivider()
                        Column(Modifier.padding(vertical = 6.dp)) {
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                Text(f.str("vehicle_number").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { nav.go("/vehicle-db/${android.net.Uri.encode(f.str("vehicle_number").orEmpty())}") })
                                Muted(FACT_LABEL[f.str("field_name")] ?: f.str("field_name").orEmpty()); Text(f.str("field_value").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                VerifyBadge(f.str("confidence")); Muted(fmtRelative(f.long("updated_at")), 10)
                            }
                            Text("根拠: ${f.str("source_label") ?: f.str("source_post_id").orEmpty()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { nav.go("/board/${f.str("source_post_id")}") })
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val id = f.str("id").orEmpty()
                                SmallButton("確認済み", { act(id) { Api.rpc("board/server-fns#adminReviewFact", jsonOf("guestKey" to gk, "factId" to id, "action" to "confirm")) } }, enabled = busyId != id, icon = Icons.Outlined.Check)
                                SmallButton("除外", { act(id) { Api.rpc("board/server-fns#adminReviewFact", jsonOf("guestKey" to gk, "factId" to id, "action" to "reject")) } }, enabled = busyId != id, icon = Icons.Outlined.Close)
                            }
                        }
                    }
                }
            }
            item { Panel(title = "最新の投稿") { AdminPosts(d.items("recent"), busyId, onStatus) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminPosts(posts: List<JsonElement>, busyId: String, onStatus: (String, String) -> Unit) {
    val nav = LocalNav.current
    if (posts.isEmpty()) { Muted("投稿はありません。", 12); return }
    posts.forEachIndexed { i, p ->
        if (i > 0) RowDivider()
        val id = p.str("id").orEmpty()
        Column(Modifier.padding(vertical = 6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(p.str("category")); VerifyBadge(p.str("verify_status"))
                if ((p.int("report_count") ?: 0) > 0) Tag("通報${p.int("report_count")}", Brand.Destructive)
                if (p.str("status") == "hidden") Tag("非公開", Brand.MutedFg)
                Muted("${fmtDateTime(p.long("at"))}　${p.str("author_name")?.ifBlank { null } ?: "ゲスト"}", 10)
            }
            Text(p.str("title").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { nav.go("/board/$id") })
            Muted("${p.str("vehicle_number")?.ifBlank { null } ?: "車両番号なし"}・いいね${p.int("like_count") ?: 0}・コメント${p.int("comment_count") ?: 0}", 10)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("開く", { nav.go("/board/$id") })
                if (p.str("status") == "published") SmallButton("非公開にする", { onStatus(id, "hidden") }, enabled = busyId != id)
                else SmallButton("公開に戻す", { onStatus(id, "published") }, enabled = busyId != id)
                SmallButton("削除", { onStatus(id, "deleted") }, enabled = busyId != id)
            }
        }
    }
}

/** 車両番号の同期（Web: components/board/SyncPanel.jsx） */
@Composable
private fun SyncPanel(gk: String) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<JsonElement?>(null) }
    var url by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf("") }
    var sheets by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    suspend fun refresh() {
        val next = Api.rpc("board/server-fns#getVehicleSyncState", jsonOf())
        state = next
        if (url.isBlank()) url = next.str("source.spreadsheet_id") ?: next.str("appSpreadsheetId").orEmpty()
        if (sheet.isBlank()) sheet = next.str("source.sheet_name").orEmpty()
    }
    LaunchedEffect(Unit) { try { refresh() } catch (e: Throwable) { if (e is CancellationException) throw e; message = false to e.userMessage() } }
    fun run(kind: String, block: suspend () -> Unit) {
        busy = kind; message = null
        scope.launch { try { block() } catch (e: Throwable) { if (e is CancellationException) throw e; message = false to e.userMessage().ifBlank { "操作できませんでした" }; if (kind == "sync") runCatching { refresh() } } finally { busy = "" } }
    }
    Panel(title = "🗄 車両番号の同期（Googleスプレッドシート D列）") {
        Muted("D2以降を文字列のまま読み取り、空白は無視・重複は統合します。元のシートは変更しません。新規の番号を追加し、シートから消えた番号は削除せず「非掲載（要確認）」にします。", 10)
        val s = state
        if (s == null) { Muted("読み込み中…", 11); message?.let { Text(it.second, fontSize = 11.sp, color = Brand.Destructive) }; return@Panel }
        OutlinedTextField(url, { url = it }, label = { Text("スプレッドシートURLまたはID", fontSize = 12.sp) }, placeholder = { Text("https://docs.google.com/spreadsheets/d/…", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (sheets.isNotEmpty()) SelectField(sheets.map { it to it }, sheet, { sheet = it }, label = "シート名", modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        else OutlinedTextField(sheet, { sheet = it }, label = { Text("シート名", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
            SmallButton(if (busy == "lookup") "取得中…" else "シート名を取得", {
                run("lookup") {
                    val r = Api.rpc("server-fns#listSheetTitles", jsonOf("spreadsheetId" to url.trim()))
                    r.str("error")?.let { throw IllegalStateException(it) }
                    sheets = r.list("sheets").map { it.text }; if (sheet !in sheets) sheet = sheets.firstOrNull().orEmpty()
                    message = true to "シートを${sheets.size}件取得しました"
                }
            }, enabled = url.isNotBlank() && busy != "lookup")
            SmallButton("同期元を保存", { run("save") { Api.rpc("board/server-fns#saveVehicleSource", jsonOf("guestKey" to gk, "spreadsheetId" to url.trim(), "sheetName" to sheet)); refresh(); message = true to "同期元を保存しました" } }, enabled = url.isNotBlank() && sheet.isNotBlank() && busy != "save")
            SmallButton(if (busy == "sync") "同期中…" else "今すぐ同期", {
                run("sync") {
                    val r = Api.rpc("board/server-fns#syncVehicles", jsonOf("guestKey" to gk, "trigger" to "manual")); refresh()
                    message = true to "同期完了：取得${r.int("unique_numbers") ?: 0}件（新規${r.int("added") ?: 0} / 重複${r.int("duplicates") ?: 0} / 非掲載${r.int("unlisted") ?: 0}）"
                }
            }, enabled = s.objAt("source") != null && busy != "sync", icon = Icons.Outlined.Refresh, primary = true)
        }
        message?.let { (ok, t) -> Text(t, fontSize = 11.sp, color = if (ok) Brand.Success else Brand.Destructive) }
        KeyValue("登録車両", "${s.int("totals") ?: 0}台"); KeyValue("同期元", s.str("source.sheet_name") ?: "未設定")
        KeyValue("最終同期", s.long("source.last_synced_at")?.let { fmtDateTime(it) } ?: "未実行"); KeyValue("対象列", "D列（2行目以降）")
        Text("同期履歴", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        val logs = s.items("logs")
        if (logs.isEmpty()) Muted("履歴はありません。", 11)
        logs.forEach { l ->
            val st = l.str("status")
            Column(Modifier.padding(vertical = 3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(when (st) { "ok" -> "成功"; "partial" -> "一部成功"; else -> "失敗" }, when (st) { "ok" -> Brand.Success; "partial" -> Brand.Warning; else -> Brand.Destructive })
                    Muted("${fmtDateTime(l.long("at"))}・${if (l.str("trigger") == "auto") "自動" else "手動"}", 10)
                }
                Muted("取得${l.int("unique_numbers") ?: 0}件 / 追加${l.int("added") ?: 0} / 重複${l.int("duplicates") ?: 0} / 非掲載${l.int("unlisted") ?: 0}", 10)
                l.str("error")?.let { Text(it, fontSize = 10.sp, color = Brand.Destructive) }
            }
        }
    }
}

