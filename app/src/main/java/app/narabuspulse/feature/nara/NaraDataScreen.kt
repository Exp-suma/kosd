package app.narabuspulse.feature.nara

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.obj
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.ErrorText
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.KpiRow
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.analytics.fmtNum
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.predict.MiniHeader
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

private val MODE_LABEL = mapOf("direct" to "直行", "transfer" to "乗換", "timetable" to "区間時刻表", "stop" to "停留所")
private val FEED_STATUS = mapOf("ok" to ("取得できた" to Brand.Success), "error" to ("取得に失敗" to Brand.Destructive), "unavailable" to ("未公開" to Brand.MutedFg), "unconfigured" to ("未確認" to Brand.Warning))

/** データ・診断（Web: nara.data.jsx） */
@Composable
fun NaraDataScreen() {
    val scope = rememberCoroutineScope()
    val q = rememberLoad("nara-data") { Api.rpc("nara/server-fns#getNaraDataStatus", JsonNull) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val data = q.data
    ScreenList {
        item { NaraNav("/nara/data") }
        item {
            MiniHeader("データ・診断", "経路検索のデータ取得元（奈良バスなびweb 公式API）の状態、収録件数、GTFSの公開状況をまとめて確認できます。") {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallButton("再読込", { q.reload() }, enabled = !q.loading, icon = Icons.Outlined.Refresh)
                    SmallButton(if (checking) "確認中…" else "今すぐ確認", {
                        checking = true; message = ""
                        scope.launch {
                            try { message = Api.rpc("nara/server-fns#checkNaraSources", JsonNull).str("message").orEmpty(); q.reload() }
                            catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "確認できませんでした" } } finally { checking = false }
                        }
                    }, enabled = !checking, icon = Icons.Outlined.VerifiedUser, primary = true)
                }
            }
        }
        if (message.isNotBlank()) item { Muted(message, 12) }
        q.error?.let { item { ErrorText(it) } }
        if (data != null) {
            item {
                KpiRow(
                    Triple("保存した停留所", "${data.int("counts.stops") ?: 0}", null),
                    Triple("座標あり", "${data.int("counts.stopsWithCoords") ?: 0}", if ((data.int("counts.stopsWithCoords") ?: 0) > 0) Brand.Success else Brand.Warning),
                    Triple("検索キャッシュ", "${data.int("counts.cache") ?: 0}", null), Triple("検索履歴", "${data.int("counts.history") ?: 0}", null),
                    units = listOf("件", "件", "件", "件"),
                )
            }
            item {
                Panel(title = "データ取得の診断", action = { data.long("generatedAt")?.takeIf { it > 0 }?.let { Muted("${BusFormat.dateTime(it)}  ", 10) } }) {
                    data.items("diagnostics").forEach { d ->
                        val (icon, color) = when (d.str("level")) { "ok" -> "✓" to Brand.Success; "warn" -> "⚠" to Brand.Warning; else -> "ⓘ" to INFO }
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(icon, color = color, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
                            Column { Text(d.str("label").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted(d.str("detail").orEmpty(), 11) }
                        }
                    }
                }
            }
            item { HybridStatusPanel() }
            data.items("feeds").forEach { feed -> item { FeedStatusCard(feed) } }
            item {
                Panel(title = "GTFS（奈良交通）の公開状況") {
                    Text(data.str("gtfs.reason").orEmpty(), fontSize = 12.sp)
                    Muted(data.str("gtfs.alternative").orEmpty(), 11)
                    data.items("gtfs.catalogs").forEach { c -> ExternalLink(c.str("label").orEmpty(), c.str("url")) }
                    Muted("最終確認 ${data.str("gtfs.checkedAt").orEmpty()}", 10)
                }
            }
            item {
                Panel(title = "直近の検索") {
                    val recent = data.items("recent")
                    if (recent.isEmpty()) Muted("まだ検索していません。", 12)
                    recent.forEachIndexed { i, r ->
                        if (i > 0) RowDivider()
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Muted(BusFormat.dateTime(r.long("at")), 10)
                                Tag(MODE_LABEL[r.str("mode")] ?: r.str("mode").orEmpty(), INFO)
                                Text(if (r.bool("ok") == true) "${r.int("resultCount") ?: 0}件${if (r.bool("cached") == true) "・保存済み" else ""}" else "失敗", fontSize = 11.sp, color = if (r.bool("ok") == true) Brand.Success else Brand.Destructive)
                            }
                            Text("${r.str("from") ?: "—"} → ${r.str("to") ?: "—"}　${r.str("date").orEmpty()} ${r.str("time").orEmpty()}", fontSize = 12.sp)
                            r.str("error")?.let { Text(it, fontSize = 10.sp, color = Brand.Destructive) }
                        }
                    }
                }
            }
            item { Muted("データ取得元: ${data.str("source").orEmpty()}。取得した内容は画面表示のためにのみ使用し、一括ダウンロードや再配布は行いません。", 10) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedStatusCard(feed: JsonElement) {
    val (label, color) = FEED_STATUS[feed.str("status")] ?: FEED_STATUS.getValue("unconfigured")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(feed.str("label").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Tag(label, color)
        }
        feed.str("sourceUrl")?.let { ExternalLink("配布元", it) }
        Muted("最終確認 ${BusFormat.dateTime(feed.long("checkedAt"))}　最終成功 ${BusFormat.dateTime(feed.long("lastOkAt"))}", 10)
        val counts = feed.objAt("counts")?.entries?.filter { it.value !is JsonNull }.orEmpty()
        if (counts.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            counts.forEach { (k, v) -> Tag("$k: ${if (v is JsonArray) v.joinToString(" / ") { it.text } else v.text}", Brand.MutedFg) }
        }
        feed.str("lastError")?.let { Text(it, fontSize = 10.sp, color = Brand.Destructive) }
        feed.str("note")?.let { Muted(it, 10) }
        feed.str("license")?.let { Muted(it, 10) }
    }
}

/** 経路検索システムの稼働状況（Web: components/transit/HybridStatusPanel.jsx） */
@Composable
fun HybridStatusPanel() {
    val scope = rememberCoroutineScope()
    val q = rememberLoad("hybrid-status") { Api.rpc("transit/hybrid-fns#getHybridJourneyStatus", JsonNull) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val data = q.data
    Panel(title = "経路検索システムの稼働状況", action = {
        SmallButton(if (testing) "テスト中…" else "接続テスト", {
            testing = true; message = ""
            scope.launch {
                try { message = Api.rpc("transit/hybrid-fns#testHybridJourneyApis", JsonNull).str("message").orEmpty(); q.reload() }
                catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "接続テストを実行できませんでした" } } finally { testing = false }
            }
        }, enabled = !testing, icon = Icons.Outlined.NetworkCheck)
    }) {
        if (q.loading && data == null) Muted("読み込み中…", 11)
        q.error?.let { Text(it, fontSize = 11.sp, color = Brand.Destructive) }
        if (message.isNotBlank()) Muted(message, 11)
        if (data != null) {
            data.items("apis").forEach { api ->
                val ok = api.bool("ok") == true
                Column(Modifier.padding(vertical = 3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (ok) "✓" else "✕", color = if (ok) Brand.Success else Brand.Destructive, fontSize = 12.sp)
                        Text(api.str("label").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(if (ok) "接続OK" else "接続できません", fontSize = 11.sp, color = if (ok) Brand.Success else Brand.Destructive)
                    }
                    Muted("応答 ${api.int("ms") ?: 0}ms（HTTP ${api.int("status")?.toString() ?: "—"}）${api.long("lastOkAt")?.let { "　最終成功 ${BusFormat.dateTime(it)}" } ?: ""}", 10)
                    api.str("error")?.let { Text(it, fontSize = 10.sp, color = Brand.Destructive) }
                    api.str("note")?.let { Muted(it, 10) }
                }
            }
            KpiRow(
                Triple("検索回数（直近100件）", "${data.int("searches.total") ?: 0}", null), Triple("検索成功率", data.dbl("searches.successRate")?.let { fmtNum(it) } ?: "—", null),
                Triple("平均応答時間", "${data.int("searches.avgMs") ?: 0}", null), Triple("ハイブリッド候補", "${data.int("searches.hybridResults") ?: 0}", null),
                units = listOf("回", "%", "ms", "件"),
            )
            val byKind = data.objAt("cache.byKind")?.entries.orEmpty().joinToString("") { "／${it.key}: ${it.value.text}" }
            Muted("キャッシュ 合計 ${data.int("cache.total") ?: 0} 件（期限切れ ${data.int("cache.expired") ?: 0} 件）$byKind", 10)
            Text("経路検索エラー・直近の検索", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            val errors = data.items("errors"); val recent = data.items("recent")
            if (errors.isEmpty() && recent.isEmpty()) Muted("まだ検索されていません。", 11)
            errors.forEach { r -> Text("⚠ ${BusFormat.dateTime(r.long("at"))} ${r.str("from")} → ${r.str("to")}　${r.str("error").orEmpty()}　Transit ${if (r.bool("transitOk") == true) "OK" else "NG"}／奈良交通 ${if (r.bool("busOk") == true) "OK" else "NG"}", fontSize = 10.sp, color = Brand.Destructive) }
            recent.forEach { r -> Muted("${BusFormat.dateTime(r.long("at"))} ${r.str("from")} → ${r.str("to")}　${r.str("date").orEmpty()} ${r.str("time").orEmpty()}　${if (r.bool("ok") == true) "${r.int("results") ?: 0}件（うち乗換${r.int("hybrid") ?: 0}件）／${r.int("ms") ?: 0}ms" else "失敗"}", 10) }
            Muted("確認日時 ${BusFormat.dateTime(data.long("checkedAt"))}", 10)
        }
    }
}

@Suppress("unused") private fun keep(e: JsonElement?) = e.obj ?: e.list("x")
