package app.narabuspulse.feature.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Session
import app.narabuspulse.core.bool
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
import app.narabuspulse.feature.research.rememberRunner
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.Markdown
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

private val STATUS = mapOf("active" to ("掲載中" to Brand.Success), "unlisted" to ("非掲載（要確認）" to Brand.Warning), "pending" to ("未確認" to Brand.MutedFg))

/** 車両データベース（Web: vehicle-db.index.jsx + VehicleTable.jsx） */
@Composable
fun VehicleDbScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("number") }
    var items by remember { mutableStateOf(listOf<JsonElement>()) }
    var cursor by remember { mutableStateOf<JsonElement?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<JsonElement?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(query, status, sort) {
        loading = true; error = ""; delay(300)
        try { val r = Api.rpc("board/server-fns#getVehicleList", jsonOf("query" to query, "status" to status, "sort" to sort)); items = r.items("items"); cursor = (r as? JsonObject)?.get("nextCursor")?.takeIf { it !is JsonNull }; hasMore = r.bool("hasMore") == true; source = r.objAt("source") }
        catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "読み込めませんでした" } } finally { loading = false }
    }
    ScreenList {
        item {
            MiniHeader("車両データベース", "GoogleスプレッドシートD列の車両番号から作った車両一覧です。掲示板の投稿は、ここに登録された車両に紐付きます。") {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallButton("目撃マップ", { nav.go("/sightings") })
                    if (Session.isAdmin) SmallButton("管理・同期", { nav.go("/admin/board") }, primary = true)
                }
            }
        }
        item {
            Panel {
                OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("車両番号・営業所で検索（例 12-5006）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SelectField(listOf("all" to "すべて", "active" to "掲載中", "unlisted" to "非掲載（要確認）", "pending" to "未確認"), status, { status = it })
                    SelectField(listOf("number" to "番号順", "posts" to "投稿が多い順", "updated" to "更新が新しい順"), sort, { sort = it })
                }
                Muted(if (source?.bool("configured") == true) "同期元: ${source.str("sheetName").orEmpty()}・最終同期 ${source.long("lastSyncedAt")?.let { fmtDateTime(it) } ?: "未実行"}" else "同期元のスプレッドシートが未設定です。管理者が「管理・同期」から設定できます。", 10)
            }
        }
        if (error.isNotBlank()) item { ErrorText(error) }
        if (loading && items.isEmpty()) item { Muted("読み込み中…", 12) }
        if (!loading && items.isEmpty() && error.isBlank()) item { Muted("該当する車両がありません。", 12) }
        if (items.isNotEmpty()) item {
            Panel {
                items.forEachIndexed { i, v ->
                    if (i > 0) RowDivider()
                    val (sl, sc) = STATUS[v.str("status")] ?: STATUS.getValue("pending")
                    val filled = listOf("vehicle_type", "garage", "vehicle_category").count { !v.str(it).isNullOrBlank() }
                    Row(Modifier.fillMaxWidth().clickable { nav.go("/vehicle-db/${android.net.Uri.encode(v.str("vehicle_number").orEmpty())}") }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.str("vehicle_number").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Muted("${v.int("post_count") ?: 0}件・${v.long("last_info_at")?.let { fmtRelative(it) } ?: "情報なし"}・${if (filled > 0) "確認 ${filled}項目" else "未確認"}", 10)
                        }
                        Tag(sl, sc)
                    }
                }
            }
        }
        if (hasMore) item {
            SmallButton(if (loading) "読み込み中…" else "さらに読み込む", {
                val c = cursor ?: return@SmallButton
                loading = true
                scope.launch {
                    try { val r = Api.rpc("board/server-fns#getVehicleList", jsonOf("query" to query, "status" to status, "sort" to sort, "cursor" to c)); items = items + r.items("items"); cursor = (r as? JsonObject)?.get("nextCursor")?.takeIf { it !is JsonNull }; hasMore = r.bool("hasMore") == true }
                    catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "読み込めませんでした" } } finally { loading = false }
                }
            }, enabled = !loading)
        }
    }
}

private val KIND_LABEL = mapOf("sighting" to "目撃", "fact" to "情報", "transfer" to "転属", "retire" to "廃車", "introduce" to "導入", "operation" to "運用")

/** 車両詳細（Web: vehicle-db.$vehicleNumber.jsx） */
@Composable
fun VehicleDbDetailScreen(number: String) {
    val nav = LocalNav.current
    val q = rememberLoad("vehicle-db-detail", number) { Api.rpc("board/server-fns#getVehicleDetail", jsonOf("number" to number)) }
    val v = q.data?.objAt("vehicle")
    ScreenList {
        if (q.loading && q.data == null) item { Muted("読み込み中…", 12) }
        q.error?.let { item { ErrorText(it) } }
        if (q.data != null && v == null) item { Column { Muted("$number は車両一覧に登録されていません。スプレッドシートの同期状況を管理者が確認できます。", 12); TextButton(onClick = { nav.go("/vehicle-db") }) { Text("車両一覧へ") } } }
        if (v != null) {
            val confirmed = runCatching { (Json.parseToJsonElement(v.str("confirmed_fields") ?: "[]") as JsonArray).map { it.text } }.getOrDefault(emptyList())
            item {
                MiniHeader(v.str("vehicle_number").orEmpty(), "車両区分: ${v.str("vehicle_category") ?: "未確認"}／形式: ${v.str("vehicle_type") ?: "未確認"}／営業所: ${v.str("garage") ?: "未確認"}") {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SmallButton("← 一覧", { nav.go("/vehicle-db") })
                        SmallButton("情報を投稿", { nav.go("/board/new?vehicle=${android.net.Uri.encode(v.str("vehicle_number").orEmpty())}") }, icon = Icons.Outlined.EditNote, primary = true)
                    }
                }
            }
            item {
                Panel(title = "🚌 車両情報") {
                    KeyValue("車両番号", v.str("vehicle_number")); KeyValue("車両区分", v.str("vehicle_category")?.ifBlank { null } ?: "未確認")
                    KeyValue("ステップ数", v.str("step_count")?.ifBlank { null } ?: "未確認"); KeyValue("年式", v.str("model_year")?.ifBlank { null }?.let { "${it}年" } ?: "未確認")
                    KeyValue("車両形式", v.str("vehicle_type")?.ifBlank { null } ?: "未確認"); KeyValue("メーカー", v.str("manufacturer")?.ifBlank { null } ?: "未確認")
                    KeyValue("営業所", v.str("garage")?.ifBlank { null } ?: "未確認")
                    KeyValue("掲載状態", when (v.str("status")) { "active" -> "掲載中"; "unlisted" -> "非掲載（要確認）"; else -> "未確認" })
                    KeyValue("投稿数", "${v.int("post_count") ?: 0}件"); KeyValue("最終確認", v.long("last_info_at")?.let { fmtDateTime(it) } ?: "情報なし")
                    KeyValue("確認済み項目", if (confirmed.isEmpty()) "なし" else confirmed.joinToString("・"))
                    Muted("空欄・未確認は、シートや投稿から確認できていない項目です。推測で補うことはしません。", 10)
                }
            }
            item { Panel(title = "寄せられた情報と確認状態") { VehicleFacts(q.data.items("facts")) } }
            item {
                val obs = q.data.items("observations")
                Panel(title = "時系列（投稿日と目撃日を区別）") {
                    if (obs.isEmpty()) Muted("記録はありません。", 11)
                    obs.forEachIndexed { i, o ->
                        if (i > 0) RowDivider()
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Tag(KIND_LABEL[o.str("kind")] ?: o.str("kind").orEmpty(), Brand.MutedFg); VerifyBadge(o.str("verify_status")) }
                            Text(o.str("post_title") ?: "投稿", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Muted("目撃: ${o.long("observed_at")?.let { fmtDateTime(it) } ?: "記載なし"}　投稿: ${fmtRelative(o.long("posted_at"))}", 10)
                            o.str("location")?.takeIf { it.isNotBlank() }?.let { Muted("場所: $it", 10) }
                            o.str("route")?.takeIf { it.isNotBlank() }?.let { Muted("系統: $it", 10) }
                            o.str("post_id")?.takeIf { it.isNotBlank() }?.let { id -> Text("投稿を見る", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { nav.go("/board/$id") }) }
                        }
                    }
                }
            }
            val posts = q.data.items("posts")
            item { Text("目撃履歴・関連投稿（${posts.size}）", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            if (posts.isEmpty()) item { Muted("関連する投稿はまだありません。", 12) }
            items(posts.size) { i -> PostRow(posts[i]) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostRow(post: JsonElement) {
    val nav = LocalNav.current
    Panel(modifier = Modifier.clickable { nav.go("/board/${post.str("id")}") }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(post.str("category")); VerifyBadge(post.str("verify_status")); Muted("${fmtRelative(post.long("at"))}・${post.str("author_name")?.ifBlank { null } ?: "ゲスト"}", 10)
        }
        Text(post.str("title").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Muted(excerpt(post.str("body"), 96), 11)
    }
}

private val SAMPLES = listOf("最近目撃された車両は？", "最近投稿された転属情報をまとめて", "営業所の情報が確認されている車両を教えて", "まだ情報が少ない車両を教えて", "最近情報が更新された車両は？", "車両形式が確認されている車両を一覧にして")

/** 車両情報AI（Web: vehicle-ai.jsx + VehicleAiChat.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleAiScreen() {
    val nav = LocalNav.current
    var question by remember { mutableStateOf("") }
    val (st, launch) = rememberRunner("回答を取得できませんでした")
    fun run(t: String) { val q = t.trim(); if (q.length < 2 || st.loading) return; question = q; launch { Api.rpc("board/server-fns#askVehicleAi", jsonOf("question" to q)) } }
    val r = st.result
    ScreenList {
        item {
            MiniHeader("車両情報AI", "掲示板の投稿と車両一覧を横断して検索し、根拠（投稿・確認状態・日時）付きで回答します。投稿に書かれた指示には従いません。") {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) { SmallButton("車両一覧", { nav.go("/vehicle-db") }); SmallButton("掲示板", { nav.go("/board") }) }
            }
        }
        item {
            Panel(title = "車両や掲示板について質問する") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(question, { question = it.take(400) }, placeholder = { Text("例: 12-5006について分かっていることを教えて", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run(question) }))
                    SmallButton("質問", { run(question) }, enabled = !st.loading && question.trim().length >= 2, icon = Icons.Outlined.Search, primary = true, modifier = Modifier.padding(start = 6.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SAMPLES.forEach { s -> AssistChip({ run(s) }, label = { Text(s, fontSize = 11.sp) }, enabled = !st.loading) } }
            }
        }
        if (st.loading) item { Muted("車両一覧と掲示板を検索して回答を作成しています…", 12) }
        st.error?.let { item { ErrorText(it) } }
        if (r != null) item {
            Panel {
                Muted("🤖 車両情報AI ・ 車両${r.int("searched.vehicles") ?: 0}台 / 投稿${r.int("searched.posts") ?: 0}件を検索", 11)
                Markdown(r.str("answer").orEmpty())
                if (r.bool("notFound") == true) Text("該当する情報が見つかりませんでした。推測では補いません。", fontSize = 11.sp, color = Brand.Warning)
                val src = r.items("sources")
                if (src.isNotEmpty()) {
                    Text("根拠", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    src.forEach { s ->
                        if (s.str("kind") == "vehicle") Text("車両 ${s.str("vehicle_number")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { nav.go("/vehicle-db/${android.net.Uri.encode(s.str("vehicle_number").orEmpty())}") }.padding(vertical = 2.dp))
                        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(s.str("label").orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f, fill = false).clickable { nav.go("/board/${s.str("post_id")}") })
                            VerifyBadge(s.str("verify_status")); Muted("${fmtDateTime(s.long("at"))}${s.str("vehicle_number")?.takeIf { it.isNotBlank() }?.let { "・$it" } ?: ""}", 10)
                        }
                    }
                }
                val miss = r.list("missing").map { it.text }
                if (miss.isNotEmpty()) { Text("確認できなかった項目", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)); Muted(miss.joinToString(" / "), 11) }
                Muted("AIの回答は投稿内容に基づく参考情報です。「管理者確認済み」以外は確定情報ではありません。", 10)
            }
        }
        item { Muted("🛡 非公開・削除済みの投稿は検索対象から除外されます。投稿者個人の情報は回答に含めません。AIの信頼度評価は参考値で、確定情報は管理者確認済みの項目のみです。", 10) }
    }
}
