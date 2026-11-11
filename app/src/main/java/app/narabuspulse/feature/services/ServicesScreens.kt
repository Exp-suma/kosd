package app.narabuspulse.feature.services

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Config
import app.narabuspulse.core.bool
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
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.board.VerifyBadge
import app.narabuspulse.feature.board.excerpt
import app.narabuspulse.feature.board.fmtDateTime
import app.narabuspulse.feature.board.fmtRelative
import app.narabuspulse.feature.board.guestKey
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.addDays
import app.narabuspulse.feature.data.jstTodayKey
import app.narabuspulse.feature.nara.yen
import app.narabuspulse.feature.predict.MiniHeader
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.time.LocalDateTime
import java.time.ZoneId

/** マイページ（Web: profile.jsx） */
@Composable
fun ProfileScreen() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val gk = remember { guestKey(context) }
    val q = rememberLoad("board-profile", gk) { Api.rpc("board/server-fns#getBoardProfile", jsonOf("guestKey" to gk)) }
    val d = q.data
    @Composable fun Posts(title: String, list: List<JsonElement>, empty: String) {
        Panel(title = title) {
            if (list.isEmpty()) Muted(empty, 12)
            list.forEachIndexed { i, p ->
                if (i > 0) RowDivider()
                Column(Modifier.fillMaxWidth().clickable { nav.go("/board/${p.str("id")}") }.padding(vertical = 6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { app.narabuspulse.feature.board.CategoryBadge(p.str("category")); VerifyBadge(p.str("verify_status")); Muted(fmtRelative(p.long("at")), 10) }
                    Text(p.str("title").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Muted(excerpt(p.str("body"), 80), 11)
                }
            }
        }
    }
    ScreenList {
        item {
            MiniHeader("マイページ", "自分の投稿・コメント・保存した投稿をまとめて確認できます。未ログインの場合は、この端末からの投稿が対象になります。") {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallButton("新規投稿", { nav.go("/board/new") }, icon = Icons.Outlined.EditNote, primary = true)
                    SmallButton("通知", { nav.go("/notifications") }, icon = Icons.Outlined.Notifications)
                }
            }
        }
        if (q.loading && d == null) item { Muted("読み込み中…", 12) }
        q.error?.let { item { ErrorText(it) } }
        if (d != null) {
            item { Posts("✎ 自分の投稿（${d.int("stats.posts") ?: 0}）", d.items("posts"), "まだ投稿はありません。") }
            item { Posts("★ 保存した投稿（${d.int("stats.saved") ?: 0}）", d.items("savedPosts"), "保存した投稿はありません。") }
            item { Posts("♥ いいねした投稿（${d.int("stats.liked") ?: 0}）", d.items("likedPosts"), "いいねした投稿はありません。") }
            item {
                Panel(title = "💬 自分のコメント（${d.int("stats.comments") ?: 0}）") {
                    val cs = d.items("comments")
                    if (cs.isEmpty()) Muted("コメントはありません。", 12)
                    cs.forEach { c ->
                        Column(Modifier.fillMaxWidth().clickable { nav.go("/board/${c.str("post_id")}") }.padding(vertical = 4.dp)) {
                            Text(c.str("body").orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Muted(fmtDateTime(c.long("at")), 10); VerifyBadge(if (c.str("status") == "published") "reported" else "retracted") }
                        }
                    }
                    Muted("投稿の削除・編集は、投稿を開いて「編集」「削除」から行えます。削除した投稿はAIの検索対象からも外れます（車両情報として確認済みの項目は残ります）。", 10)
                }
            }
        }
    }
}

private val SEARCH_LINKS = mapOf("lodging" to ("/services/lodging" to "宿泊を検索"), "transit" to ("/services/transit" to "経路を検索"), "x" to ("/services/x" to "投稿を検索"))
private fun fmtTs(ts: Long?) = if (ts == null || ts == 0L) "—" else BusFormat.dateTime(ts)

/** 外部サービス接続（Web: services.index.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServicesScreen() {
    val nav = LocalNav.current
    val uri = LocalUriHandler.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    val q = rememberLoad("service-statuses", version) { Api.rpc("services/server-fns#getServiceStatuses", jsonOf()) }
    var testing by remember { mutableStateOf("") }
    val open = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    ScreenList {
        item { MiniHeader("外部サービス接続", "宿泊検索・経路検索・X投稿検索・GPS自動取得の接続状態を確認できます。認証情報は登録済み／未登録のみ表示し、値は表示しません。") { SmallButton("再読み込み", { version++ }, enabled = !q.loading, icon = Icons.Outlined.Refresh) } }
        if (q.loading && q.data == null) item { Muted("読み込み中…", 12) }
        q.error?.let { item { ErrorText(it) } }
        q.data?.let { d ->
            items(d.items("services").size) { i ->
                val s = d.items("services")[i]
                val key = s.str("key").orEmpty()
                val configured = s.bool("configured") == true
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(s.str("name").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold); Muted(s.str("purpose").orEmpty(), 11) }
                        when { !configured -> Tag("接続待ち", Brand.MutedFg); s.str("status") == "ok" -> Tag("✓ 接続済み", Brand.Success); s.str("status") == "error" -> Tag("エラー", Brand.Destructive); else -> Tag("未確認", Brand.MutedFg) }
                    }
                    KeyValue("最終成功", fmtTs(s.long("lastOkAt"))); KeyValue("最終確認", fmtTs(s.long("lastCheckAt"))); KeyValue("呼び出し回数", "${s.int("calls") ?: 0} 回（成功 ${s.int("successCount") ?: 0}）")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { s.items("secrets").forEach { sec -> Text("${if (sec.bool("present") == true) "登録済み" else "未登録"} ${sec.str("name")}", fontSize = 10.sp, color = if (sec.bool("present") == true) Brand.Success else Brand.Warning, fontFamily = FontFamily.Monospace) } }
                    s.str("lastError")?.let { Text("最終エラー（${fmtTs(s.long("lastErrorAt"))}）: $it", fontSize = 10.sp, color = Brand.Destructive) }
                    s.str("note")?.let { Muted(it, 10) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        SmallButton(if (testing == key) "テスト中…" else "接続テスト", {
                            testing = key
                            scope.launch {
                                try { val r = Api.rpc("services/server-fns#testServiceConnection", jsonOf("service" to key)); snackbar.showSnackbar(r.str("message").orEmpty()); version++ }
                                catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "接続テストに失敗しました" }) } finally { testing = "" }
                            }
                        }, enabled = testing != key, icon = Icons.Outlined.Power)
                        TextButton(onClick = { open[key] = open[key] != true }) { Text("設定手順 ${if (open[key] == true) "▴" else "▾"}", fontSize = 11.sp) }
                    }
                    if (open[key] == true) {
                        Muted("料金・プラン: ${s.str("plan").orEmpty()}", 10); Muted("接続先: ${s.str("endpoint").orEmpty()}", 10); Muted("制限: ${s.str("limits").orEmpty()}", 10)
                        s.list("steps").forEachIndexed { n, st -> Muted("${n + 1}. ${st.text}", 10) }
                        Row { s.str("docsUrl")?.let { u -> TextButton(onClick = { runCatching { uri.openUri(u) } }) { Text("公式ドキュメント", fontSize = 11.sp) } }; s.str("registerUrl")?.let { u -> TextButton(onClick = { runCatching { uri.openUri(u) } }) { Text("キーの取得先", fontSize = 11.sp) } } }
                    }
                    if (key == "collect") d.objAt("collect")?.let { CollectDetail(it) }
                    else SEARCH_LINKS[key]?.let { (to, l) -> TextButton(onClick = { nav.go(to) }) { Text("$l →", fontSize = 12.sp) } }
                }
            }
            item { GasSetupCard() }
            item { Muted("未登録の認証情報は Base44 の Secrets に追加すると反映されます（反映後、上の「接続テスト」で実際にAPIを呼んで確認できます）。宿泊・経路・投稿検索は外部サービスの契約状況により利用可否が変わります。取得できなかった情報は推測せず「未確認」と表示します。", 10) }
        }
    }
}

@Composable
private fun CollectDetail(c: JsonElement) {
    Column(Modifier.padding(top = 6.dp)) {
        KeyValue("自動取得", if (c.bool("enabled") == true) "有効" else "無効（設定画面で有効にしてください）")
        KeyValue("間隔", "${c.int("intervalMinutes") ?: 0}分"); KeyValue("1回の上限", "${c.int("maxVehicles") ?: 0}台")
        KeyValue("最終実行", "${fmtTs(c.long("lastRunAt"))}（${c.str("lastStatus") ?: "—"}）"); KeyValue("最終成功", fmtTs(c.long("lastOkAt")))
        val next = c.long("nextAt"); KeyValue("次回予定", fmtTs(next) + if (next != null && next < System.currentTimeMillis()) "（予定時刻を過ぎています。GASのトリガーを確認してください）" else "")
        c.long("authErrorAt")?.let { Text("⚠ 認証エラーを検知: ${fmtTs(it)}（COLLECT_TOKEN が一致していません）", fontSize = 10.sp, color = Brand.Destructive) }
        c.str("lastError")?.let { Text("最終エラー: $it", fontSize = 10.sp, color = Brand.Destructive) }
        val logs = c.items("logs")
        if (logs.isNotEmpty()) { Text("最近の実行ログ", fontSize = 11.sp, fontWeight = FontWeight.Bold); logs.forEach { l -> Muted("${fmtTs(l.long("startedAt"))} / ${l.str("trigger")} / ${l.str("status")} / ${l.int("ok") ?: 0}台成功・${l.int("failed") ?: 0}台失敗・新規${l.int("newSnapshots") ?: 0}件", 10) } }
    }
}

private fun gasCode() = """// ===== 奈良交通GPS自動取得 (Base44 へ送信) =====
const BASE44_URL = 'https://nara-bus-pulse.base44.app/api/collect';
const COLLECT_TOKEN = 'スクリプトプロパティに保存した値と同じ文字列';
function collectGps() {
  const props = PropertiesService.getScriptProperties();
  const token = props.getProperty('COLLECT_TOKEN') || COLLECT_TOKEN;
  const url = props.getProperty('BASE44_URL') || BASE44_URL;
  const res = UrlFetchApp.fetch(url, {
    method: 'post',
    contentType: 'application/json',
    headers: { 'x-collect-token': token },
    payload: JSON.stringify({ trigger: 'gas' }),
    muteHttpExceptions: true,
  });
  const status = res.getResponseCode();
  const body = res.getContentText();
  console.log('HTTP ' + status + ' / ' + body);
  // 401 は COLLECT_TOKEN の不一致。ログに残して気づけるようにする
  if (status === 401) {
    console.error('認証エラー: COLLECT_TOKEN が一致しません');
  }
  return { status: status, body: body };
}
// 5分ごとのトリガーを1つだけ作る（二重実行防止）
function setupTrigger() {
  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'collectGps') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('collectGps').timeBased().everyMinutes(5).create();
  console.log('5分間隔のトリガーを作成しました');
}"""

@Composable
private fun GasSetupCard() {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Panel(title = "GAS（Google Apps Script）側の設定", action = { SmallButton(if (copied) "コピーしました" else "コードをコピー", { clipboard.setText(AnnotatedString(gasCode())); copied = true }, icon = Icons.Outlined.ContentCopy) }) {
        Muted("スクリプトプロパティに COLLECT_TOKEN と BASE44_URL を登録し、下のコードを貼って setupTrigger を1回実行すると5分間隔で動きます。", 11)
        KeyValue("Base44 側に必要な設定", "Secrets: COLLECT_TOKEN（32文字以上）／設定画面: スプレッドシートID・自動収集を有効・間隔5分")
        KeyValue("GAS 側に必要な設定", "スクリプトプロパティ: COLLECT_TOKEN（Base44と同じ値）・BASE44_URL（このアプリの /api/collect）")
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            Text(gasCode(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp))
        }
    }
}

@Composable
private fun ServiceHeader(key: String, title: String, desc: String, missingNote: String) {
    val nav = LocalNav.current
    val q = rememberLoad("service-statuses", 0) { Api.rpc("services/server-fns#getServiceStatuses", jsonOf()) }
    val s = q.data.items("services").firstOrNull { it.str("key") == key }
    Column {
        TextButton(onClick = { nav.go("/services") }) { Text("← 外部サービス接続へ", fontSize = 12.sp) }
        MiniHeader(title, desc)
        if (s != null && s.bool("configured") != true) Text("未登録の認証情報: ${s.list("missing").joinToString(" / ") { it.text }}。$missingNote", fontSize = 11.sp, color = Brand.Warning)
    }
}

private val PREFECTURES = listOf("nara" to "奈良県", "osaka" to "大阪府", "kyoto" to "京都府", "wakayama" to "和歌山県", "mie" to "三重県", "shiga" to "滋賀県", "hyogo" to "兵庫県")

/** 宿泊検索（Web: services.lodging.jsx + LodgingPanel.jsx） */
@Composable
fun LodgingScreen() {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var pref by remember { mutableStateOf("nara") }
    var checkin by remember { mutableStateOf(addDays(jstTodayKey(), 7)) }
    var checkout by remember { mutableStateOf(addDays(jstTodayKey(), 8)) }
    var adults by remember { mutableStateOf("2") }
    var rooms by remember { mutableStateOf("1") }
    var loading by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<JsonElement?>(null) }
    var error by remember { mutableStateOf("") }
    ScreenList {
        item { ServiceHeader("lodging", "宿泊施設の空室・料金", "楽天トラベル空室検索APIで、指定日の空室と料金（税・サービス料込みの目安）を取得します。予約は各施設の公式ページで行います。", "登録するとここで実際の空室・料金を取得できます。") }
        item {
            Panel {
                SelectField(PREFECTURES, pref, { pref = it }, label = "都道府県")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Muted("チェックイン", 11); DateField(checkin, { checkin = it }); Muted("アウト", 11); DateField(checkout, { checkout = it }, min = checkin)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(adults, { adults = it.filter(Char::isDigit).take(2) }, label = { Text("大人（人）", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(rooms, { rooms = it.filter(Char::isDigit).take(2) }, label = { Text("部屋数", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                SmallButton(if (loading) "検索中…" else "空室・料金を検索", {
                    loading = true; data = null; error = ""
                    scope.launch {
                        try {
                            val r = Api.rpc("services/server-fns#searchLodging", jsonOf("prefecture" to pref, "checkin" to checkin, "checkout" to checkout, "adults" to (adults.toIntOrNull() ?: 2), "rooms" to (rooms.toIntOrNull() ?: 1)))
                            val miss = r.list("missing"); if (miss.isNotEmpty()) error = "未登録の認証情報: ${miss.joinToString(" / ") { it.text }}" else { data = r; error = r.str("error").orEmpty() }
                        } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "検索できませんでした" } } finally { loading = false }
                    }
                }, enabled = !loading, icon = Icons.Outlined.Search, primary = true, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (error.isNotBlank()) item { ErrorText(error) }
        data?.let { d ->
            val hotels = d.items("hotels")
            item { Muted("${hotels.size}件／取得日時 ${fmtTs(d.long("fetchedAt"))}（料金は1泊あたり税・サービス料込みの目安）", 11) }
            items(hotels.size) { i ->
                val h = hotels[i]
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(h.str("name").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold); Muted(h.str("address").orEmpty(), 10) }
                        Tag(if (h.bool("vacancyConfirmed") == true) "空室あり" else "空室未確認", if (h.bool("vacancyConfirmed") == true) Brand.Success else Brand.MutedFg)
                    }
                    Text((h.dbl("minCharge")?.let { "${yen(it)}〜" } ?: "料金情報なし") + if (h.int("chargeFlag") == 1 && h.dbl("minCharge") != null) "（1室あたり）" else "", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    (h.str("bookingUrl") ?: h.str("infoUrl"))?.let { u -> TextButton(onClick = { runCatching { uri.openUri(u) } }) { Text("楽天トラベルで見る", fontSize = 12.sp) } }
                }
            }
        }
    }
}

/** 全国公共交通 経路検索（Web: services.transit.jsx + TransitPanel.jsx） */
@Composable
fun ServiceTransitScreen() {
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf("近鉄奈良駅") }
    var destination by remember { mutableStateOf("JR難波駅") }
    val start = remember { LocalDateTime.now(ZoneId.of("Asia/Tokyo")).plusMinutes(30) }
    var date by remember { mutableStateOf(start.toLocalDate().toString()) }
    var time by remember { mutableStateOf(start.toLocalTime().toString().take(5)) }
    var loading by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<JsonElement?>(null) }
    var error by remember { mutableStateOf("") }
    ScreenList {
        item { ServiceHeader("transit", "全国公共交通 経路検索", "鉄道・地下鉄・路線バス・高速バス・フェリー・航空・徒歩を組み合わせた経路を取得します。時刻表データが無い区間は結果が返らないため、推測の時刻は表示しません。", "") }
        item {
            Panel {
                OutlinedTextField(origin, { origin = it }, label = { Text("出発地", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(destination, { destination = it }, label = { Text("到着地", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) { Muted("出発日時（JST）", 11); DateField(date, { date = it }); TimeField(time, { time = it }) }
                SmallButton(if (loading) "検索中…" else "経路を検索", {
                    if (origin.isBlank() || destination.isBlank()) return@SmallButton
                    loading = true; data = null; error = ""
                    scope.launch {
                        try {
                            val departAt = LocalDateTime.parse("${date}T$time").atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
                            val r = Api.rpc("services/server-fns#searchTransit", jsonOf("origin" to origin, "destination" to destination, "departAt" to departAt))
                            val miss = r.list("missing"); if (miss.isNotEmpty()) error = "未登録の認証情報: ${miss.joinToString(" / ") { it.text }}" else { data = r; error = r.str("error").orEmpty() }
                        } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "検索できませんでした" } } finally { loading = false }
                    }
                }, enabled = !loading, icon = Icons.Outlined.Search, primary = true, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (error.isNotBlank()) item { ErrorText(error) }
        if (data?.bool("noRoute") == true && error.isBlank()) item { Muted("この条件で取得できる経路がありませんでした。時刻表データが未整備の区間は結果が返りません（推測の時刻は表示しません）。", 12) }
        val routes = data.items("routes")
        items(routes.size) { i ->
            val r = routes[i]
            Panel(title = "候補${i + 1}：所要 ${r.int("durationMin") ?: "—"}分／${r.dbl("distanceKm")?.let { "${it}km" } ?: "距離不明"}／乗換 ${maxOf(0, r.int("transferCount") ?: 0)}回") {
                r.items("steps").forEach { s ->
                    val icon = when { s.str("mode") == "WALK" -> "🚶"; s.str("mode").orEmpty().contains("BUS") -> "🚌"; s.str("mode") == "FERRY" -> "⛴"; s.str("mode").orEmpty().contains("RAIL") || s.str("mode").orEmpty().contains("TRAIN") || s.str("mode") in setOf("SUBWAY", "TRAM", "MONORAIL") -> "🚆"; else -> "✈" }
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(icon, modifier = Modifier.width(24.dp))
                        Column {
                            Text("${s.str("modeLabel").orEmpty()}${s.str("lineName")?.let { "・$it" } ?: ""}${s.str("headsign")?.let { "　$it 行" } ?: ""}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("${s.str("departure")?.let { "$it " } ?: ""}${s.str("fromStop") ?: "—"} → ${s.str("arrival")?.let { "$it " } ?: ""}${s.str("toStop") ?: "—"}${s.int("stopCount")?.takeIf { it > 0 }?.let { "（${it}停留所）" } ?: ""}", fontSize = 12.sp)
                            s.str("agency")?.let { Muted(it, 10) }
                        }
                    }
                }
            }
        }
    }
}

private val X_MODES = listOf(Triple("narakotsu", "奈良交通関連", "（検索語は不要）"), Triple("keyword", "キーワード", "例：奈良交通 撮影"), Triple("vehicle", "車番", "例：奈良200か257"), Triple("plate", "登録番号", "例：奈良200か"), Triple("model", "車両型式", "例：QPG-KV290N1"), Triple("route", "系統・路線", "例：21系統"))

/** X投稿検索（Web: services.x.jsx + XSearchPanel.jsx） */
@Composable
fun XSearchScreen() {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("narakotsu") }
    var term by remember { mutableStateOf("") }
    var since by remember { mutableStateOf("") }
    var until by remember { mutableStateOf("") }
    var max by remember { mutableStateOf(20) }
    var rts by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<JsonElement?>(null) }
    var error by remember { mutableStateOf("") }
    ScreenList {
        item { ServiceHeader("x", "X（旧Twitter）投稿検索", "車番・登録番号・型式・系統・奈良交通に関する投稿を検索します。直近7日間のみが対象で、接続できない場合は理由を表示します（結果を装う表示はしません）。", "X API は検索権限のある有料プランが必要です。") }
        item {
            Panel {
                SelectField(X_MODES.map { it.first to it.second }, mode, { mode = it; term = "" }, label = "検索の種類")
                OutlinedTextField(term, { term = it }, label = { Text("検索語", fontSize = 12.sp) }, placeholder = { Text(X_MODES.first { it.first == mode }.third, fontSize = 12.sp) }, enabled = mode != "narakotsu", singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    DateField(since, { since = it }, label = if (since.isBlank()) "開始日（任意）" else null); DateField(until, { until = it }, label = if (until.isBlank()) "終了日（任意）" else null)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectField((1..10).map { it * 10 }.map { it to "${it}件" }, max, { max = it }, label = "取得件数")
                    Checkbox(rts, { rts = it }); Text("リポストを含む", fontSize = 12.sp)
                }
                Muted("Recent Search は直近7日間のみ取得できます。期間外を指定した場合は理由を表示します。", 10)
                SmallButton(if (loading) "検索中…" else "投稿を検索", {
                    if (mode != "narakotsu" && term.isBlank()) return@SmallButton
                    loading = true; data = null; error = ""
                    scope.launch {
                        try {
                            val r = Api.rpc("services/server-fns#searchX", jsonOf("mode" to mode, "term" to term.trim(), "since" to since.ifBlank { null }, "until" to until.ifBlank { null }, "max" to max, "includeRetweets" to rts))
                            val miss = r.list("missing"); if (miss.isNotEmpty()) error = "未登録の認証情報: ${miss.joinToString(" / ") { it.text }}" else { data = r; error = r.str("error").orEmpty() }
                        } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "検索できませんでした" } } finally { loading = false }
                    }
                }, enabled = !loading, icon = Icons.Outlined.Search, primary = true, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (error.isNotBlank()) item { ErrorText(error) }
        data?.let { d ->
            val posts = d.items("posts")
            if (d.list("posts").isNotEmpty() || d.str("query") != null) item { Muted("検索式: ${d.str("query").orEmpty()}／${d.int("resultCount") ?: posts.size}件", 11) }
            if (d.bool("empty") == true) item { Muted("該当する投稿はありませんでした。", 12) }
            items(posts.size) { i ->
                val p = posts[i]
                Panel {
                    Row { Text(p.str("authorName") ?: p.str("authorHandle") ?: "不明", fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted("  @${p.str("authorHandle").orEmpty()}", 11) }
                    Muted(p.str("createdAt")?.let { runCatching { BusFormat.dateTime(java.time.Instant.parse(it).toEpochMilli()) }.getOrDefault(it) } ?: "—", 10)
                    Text(p.str("text").orEmpty(), fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                    Row {
                        p.items("media").take(4).forEach { m -> TextButton(onClick = { runCatching { uri.openUri(m.str("url") ?: p.str("url").orEmpty()) } }) { Text("${if (m.str("type") == "photo") "画像" else "動画"}を開く", fontSize = 11.sp) } }
                        TextButton(onClick = { runCatching { uri.openUri(p.str("url").orEmpty()) } }) { Text("投稿を開く", fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

/** ホーム画面に追加（Web: install.jsx）。ネイティブ版はインストール済みのため案内のみ。 */
@Composable
fun InstallScreen() {
    val context = LocalContext.current
    ScreenList {
        item { MiniHeader("ホーム画面に追加", "このアプリは Android アプリとしてインストール済みです。ホーム画面のアイコンから起動できます。") }
        item {
            Panel(title = "このアプリについて") {
                KeyValue("アプリ", "奈良交通バスロケ監視（ネイティブ版）")
                KeyValue("バージョン", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—")
                KeyValue("接続先", Config.BASE_URL)
                Muted("Web版の「ホーム画面に追加（PWA）」の手順は不要です。アイコンが見つからない場合は、アプリ一覧から「奈良交通バスロケ監視」を長押ししてホーム画面に追加してください。", 11)
                Muted("通知はアプリの通知設定から受け取れます（設定 → 通知）。", 11)
            }
        }
    }
}

