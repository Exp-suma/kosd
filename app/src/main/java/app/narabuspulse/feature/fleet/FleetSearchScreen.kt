package app.narabuspulse.feature.fleet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import app.narabuspulse.core.Logic
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.EmptyState
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.StatTile
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberRpc
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement


/** 車番検索（Web: src/routes/_authed/fleet.index.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FleetSearchScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val base = rememberRpc("fleet/fns#searchFleetVehicles", jsonOf("query" to ""))
    val history = rememberRpc("fleet/fns#listVehicleSearchHistory", jsonOf())
    val favorites = rememberFleetFavorites()
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JsonElement?>(null) }
    var submitted by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var runningOnly by remember { mutableStateOf(true) }
    val matchLabels by produceState(emptyMap<String, String>()) {
        value = (Logic.constant("fleet/format", "MATCH_LABEL") as? kotlinx.serialization.json.JsonObject)?.mapValues { it.value.text }.orEmpty()
    }

    fun runSearch(term: String, includeStopped: Boolean) {
        val value = term.trim()
        if (value.isEmpty()) { error = "車番または車両コードを入力してください"; return }
        busy = true; error = ""
        scope.launch {
            try {
                val res = Api.rpc("fleet/fns#searchFleetVehicles", jsonOf("query" to value, "includeNotRunning" to includeStopped))
                result = res; submitted = value
                val top = res.items("items").firstOrNull()
                runCatching {
                    Api.rpc("fleet/fns#recordVehicleSearch", jsonOf("query" to value, "matchType" to res.str("matchType"), "carCode" to top?.str("carCode"), "plateNo" to top?.str("plateNo"), "resultCount" to res.items("items").size))
                }
                history.reload()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.userMessage().ifBlank { "検索できませんでした" }
            } finally { busy = false }
        }
    }

    val items = result.items("items")
    val notRunning = if (runningOnly) emptyList() else result.items("notRunning")
    val suggestions = base.data.items("suggestions")
    val term = query.trim()
    val matches = if (term.isEmpty()) emptyList() else suggestions.filter { (it.str("plateNo").orEmpty()).contains(term) || (it.str("carCode").orEmpty()).contains(term) }.take(6)
    val stale = result?.bool("stale") == true || base.data?.bool("stale") == true

    ScreenList {
        item {
            Column {
                Text("車番検索", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("奈良交通の公式バスロケーションシステムから取得した運行中車両を、車番・数字・車両コードで検索できます。検索結果の★からお気に入りに登録できます。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SmallButton("お気に入り", { nav.go("/fleet/favorites") }, icon = Icons.Filled.Star, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item { FleetNav("/fleet") }
        item {
            Panel {
                OutlinedTextField(
                    query, { query = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; result = null; submitted = ""; error = "" }) { Icon(Icons.Outlined.Close, "入力を消す") } },
                    placeholder = { Text("車番を入力（例 奈良200か1371 / 1371 / 1170）", fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch(query, !runningOnly) }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SmallButton("検索", { runSearch(query, !runningOnly) }, enabled = !busy, icon = Icons.Outlined.Search, primary = true)
                    SmallButton(if (runningOnly) "運行中のみ" else "未運行も表示", {
                        runningOnly = !runningOnly
                        if (submitted.isNotEmpty()) runSearch(submitted, runningOnly.not())
                    }, icon = Icons.Outlined.Refresh)
                }
                if (matches.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text("候補", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    matches.forEach { m ->
                        AssistChip(onClick = { query = m.str("plateNo").orEmpty(); runSearch(query, !runningOnly) }, label = { Text("${m.str("plateNo")} 系統 ${m.str("route")?.ifBlank { null } ?: "不明"}", fontSize = 11.sp) })
                    }
                }
            }
        }
        val hist = history.data.items("items")
        if (hist.isNotEmpty()) item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, null, Modifier.size(14.dp))
                Text(" 検索履歴", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                hist.forEach { h -> AssistChip(onClick = { query = h.str("query").orEmpty(); runSearch(query, false) }, label = { Text(h.str("query").orEmpty(), fontSize = 11.sp) }) }
            }
        }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (result != null || base.data != null) item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                StatTile("運行中の車両", ((result ?: base.data).long("scanned") ?: 0).toString(), Modifier.weight(1f), "台")
                StatTile("検索結果", items.size.toString(), Modifier.weight(1f), "台", if (items.isNotEmpty()) Brand.Success else Brand.MutedFg)
                StatTile("最終取得", fleetClock((result ?: base.data).long("fetchedAt")), Modifier.weight(1f), tone = if (stale) Brand.Warning else null)
                StatTile("情報の取得元", "公式", Modifier.weight(1f), tone = INFO)
                StatTile("検索対象", if (runningOnly) "運行中" else "全車両", Modifier.weight(1f))
            }
        }
        if (base.loading && result == null) item { Text("公式バスロケーションから運行中の車両を取得しています…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val fetchErr = base.error ?: result?.str("error")?.ifBlank { null }
        if (fetchErr != null) item { Text("取得できませんでした: $fetchErr${result?.long("fetchedAt")?.let { "（最後に取得できた時刻 ${fleetClockPlain(it)}）" } ?: ""}", fontSize = 12.sp, color = Brand.Warning) }
        // 検索結果
        if (submitted.isNotEmpty() && items.isEmpty() && notRunning.isEmpty()) item {
            EmptyState("「$submitted」に一致する車両は見つかりませんでした", description = "奈良交通の公式バスロケーションに現在出ていない車両は、現在位置を取得できません。車番の一部（数字だけ）でも検索できます。")
        }
        if (items.isNotEmpty()) item {
            val mt = result.str("matchType") ?: "none"
            Panel(title = "検索結果", action = { Row(verticalAlignment = Alignment.CenterVertically) { Tag(matchLabels[mt] ?: "一致", if (mt == "exact") Brand.Success else INFO); Text("  ${items.size} 台  ", fontSize = 11.sp) } }) {
                items.forEachIndexed { i, item ->
                    if (i > 0) RowDivider()
                    val code = item.str("carCode").orEmpty()
                    val fav = favorites.byCode.containsKey(code)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable { nav.go("/fleet/$code") }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.DirectionsBus, null, tint = Brand.Orange)
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                    Text(item.str("plateNo").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("車両コード $code", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (item.bool("stale") == true) Tag("位置情報が古い", Brand.Warning)
                                    if (item.bool("wheelchair") == true) Tag("車いす", INFO)
                                }
                                Text((item.str("route")?.let { "系統 $it" } ?: "系統不明") + (item.str("destination")?.let { " $it 行" } ?: "") + (item.str("stop")?.let { " ・ $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(fleetClock(item.long("at")), fontSize = 12.sp)
                                Text(fleetAge(item.long("at")), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        }
                        IconButton(onClick = { favorites.toggle(code, item.str("plateNo").orEmpty()) }, enabled = favorites.busyCode != code) {
                            Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline, if (fav) "お気に入りを解除" else "お気に入りに登録", tint = if (fav) Brand.Warning else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (notRunning.isNotEmpty()) item {
            Panel(title = "公式バスロケで確認できない車両", action = { Text("${notRunning.size} 台  ", fontSize = 11.sp) }) {
                notRunning.forEachIndexed { i, row ->
                    if (i > 0) RowDivider()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row { Text(row.str("plate_no") ?: "車番不明", fontSize = 13.sp, fontWeight = FontWeight.Bold); Text("  車両コード ${row.str("car_code") ?: "—"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        val st = row.str("status")
                        Text("現在位置は取得できません（${when (st) { "no_service" -> "運行情報なし"; "error" -> "取得失敗"; else -> "運行終了" }}）。" +
                            (row.long("at")?.let { " 最終確認 ${fleetClockPlain(it)}" } ?: "") + (row.str("stop_name")?.let { " ・ $it" } ?: ""), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (items.isEmpty() && notRunning.isEmpty() && submitted.isEmpty()) item {
            EmptyState("車番を入力して検索してください", description = "入力候補から選ぶこともできます。")
        }
        item {
            val area = base.data.objAt("area")
            Text(
                "検索対象は奈良交通バスロケーションシステムの一般公開データです。公式に存在しない情報（系統名・車両形式など）は表示しません。" +
                    (area?.let { " 取得範囲は奈良交通の運行エリア（中心 ${it.str("lat")}, ${it.str("lon")} / 半径 ${Math.round((it.dbl("radiusM") ?: 0.0) / 1000)}km）です。" } ?: ""),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一覧の中など Composable 以外で使う時刻表示（JST の HH:mm）。 */
fun fleetClockPlain(ts: Long): String = app.narabuspulse.feature.bus.BusFormat.time(ts)
