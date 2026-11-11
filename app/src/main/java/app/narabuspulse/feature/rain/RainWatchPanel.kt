package app.narabuspulse.feature.rain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.long
import app.narabuspulse.core.rememberLocationRequest
import app.narabuspulse.core.str
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.bus.jsNum
import app.narabuspulse.feature.notify.CheckRow
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.EmptyState
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val INFO = Color(0xFF2F6FB0)

/** 降雨の見込み状態のバッジ（Web: components/rain/RainStateBadge.jsx） */
@Composable
fun RainStateBadge(state: String?) {
    val (text, color) = when (state) {
        "dry" -> "雨なし" to Brand.MutedFg
        "later" -> "3時間内" to INFO
        "soon60" -> "60分内" to Brand.Warning
        "soon30" -> "30分内" to Brand.Warning
        "raining" -> "降雨中" to Brand.Destructive
        "clearing" -> "弱まる" to Brand.Orange
        else -> "取得不可" to Brand.MutedFg
    }
    Tag(text, color)
}

private val LEVEL_LABELS = mapOf("light" to "弱い雨以上", "moderate" to "やや強い雨以上", "heavy" to "強い雨以上")

/** 雨雲・降雨監視（Web: components/rain/RainWatchPanel.jsx + RainWatchRow / RainWatchForm） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RainWatchPanel(modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val query = rememberLoad("rain-watches") { Api.rpc("rain/fns#listRainWatches", jsonOf()) }
    var formOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var overrides by remember { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    val items = query.data.items("items").map { overrides[it.str("id")] ?: it }

    fun act(block: suspend () -> Unit) {
        busy = true; error = ""
        scope.launch { try { block() } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage() } finally { busy = false } }
    }

    Panel(
        title = "雨雲・降雨監視",
        subtitle = query.data?.let { "最終判定 ${BusFormat.time(it.long("checkedAt"))}" } ?: "登録した地点の雨の降り出し・強まりを監視します",
        modifier = modifier,
        action = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { overrides = emptyMap(); query.reload() }, enabled = !query.loading && !busy) { Icon(Icons.Outlined.Refresh, "予報を取り直す", Modifier.size(18.dp)) }
                SmallButton("地点を追加", { formOpen = !formOpen }, icon = Icons.Outlined.Add)
            }
        },
    ) {
        if (error.isNotBlank() || query.error != null) Text(error.ifBlank { query.error.orEmpty() }, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        when {
            query.loading && query.data == null -> Text("監視地点を確認しています…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            items.isEmpty() -> EmptyState("監視地点がありません", description = "自宅・駅・撮影地を登録すると、雨の降り出しと強まりを判定して通知します。", actionLabel = "地点を登録", onAction = { formOpen = true })
            else -> items.forEachIndexed { i, item ->
                if (i > 0) RowDivider()
                RainWatchRow(item, busy, onDelete = { act { Api.rpc("rain/fns#deleteRainWatch", jsonOf("id" to item.str("id"))); overrides = emptyMap(); query.reload() } },
                    onCheck = { act { val fresh = Api.rpc("rain/fns#checkRainWatch", jsonOf("id" to item.str("id"))); fresh.str("id")?.let { overrides = overrides + (it to fresh) } } })
            }
        }
        if (formOpen) RainWatchForm(null, busy, onSubmit = { values ->
            act { Api.rpc("rain/fns#saveRainWatch", values); formOpen = false; overrides = emptyMap(); query.reload() }
        }, onCancel = { formOpen = false })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(query.data.str("note") ?: "降水は予報モデルの値で、雨雲の移動方向・速度は表示しません。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { nav.go("/profile") }) { Text("プッシュ通知の登録", fontSize = 11.sp) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RainWatchRow(item: JsonElement, busy: Boolean, onDelete: () -> Unit, onCheck: () -> Unit) {
    val timing = if (item.bool("rainingNow") == true) {
        if (item.int("minutesToEnd") != null) "${BusFormat.time(item.long("endAt"))}ごろ弱まる見込み" else "3時間以内に止む見込みなし"
    } else {
        item.int("minutesToOnset")?.let { "${BusFormat.time(item.long("onsetAt"))}ごろ降り出し（約${it}分後）" } ?: "3時間先まで雨の予報なし"
    }
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(item.str("name").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                RainStateBadge(item.str("state"))
                if (item.bool("notify") == false) Tag("通知OFF", Brand.MutedFg)
                if (item.bool("stale") == true) Tag("前回値", Brand.Warning)
            }
            item.str("label")?.let { Text(it, fontSize = 12.sp) }
            Text(timing, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.dbl("peakMmh")?.let { Text("ピーク ${jsNum(it)}mm/h相当", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item.int("maxPop")?.let { Text("降水確率 $it%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("${LEVEL_LABELS[item.str("level")] ?: LEVEL_LABELS["light"]}で通知", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.str("address")?.takeIf { it.isNotBlank() }?.let { Text("📍 $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton(onClick = onCheck, enabled = !busy) { Icon(Icons.Outlined.Refresh, "最新の予報で確認し直す", Modifier.size(18.dp)) }
        IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Outlined.Delete, "この地点を削除", Modifier.size(18.dp)) }
    }
}

@Composable
private fun RainWatchForm(initial: JsonElement?, busy: Boolean, onSubmit: (JsonObject) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial.str("name").orEmpty()) }
    var address by remember { mutableStateOf(initial.str("address").orEmpty()) }
    var lat by remember { mutableStateOf(initial.str("lat").orEmpty()) }
    var lon by remember { mutableStateOf(initial.str("lon").orEmpty()) }
    var level by remember { mutableStateOf(initial.str("level") ?: "light") }
    var notify by remember { mutableStateOf(initial.bool("notify") != false) }
    var quietFrom by remember { mutableStateOf(initial.str("quiet_from").orEmpty()) }
    var quietTo by remember { mutableStateOf(initial.str("quiet_to").orEmpty()) }
    var geo by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val locate = rememberLocationRequest { loc ->
        if (loc == null) geo = "現在地を取得できませんでした"
        else { lat = "%.5f".format(loc.lat); lon = "%.5f".format(loc.lon); geo = "現在地を入力しました" }
    }
    Column(Modifier.padding(top = 8.dp)) {
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("地点名", fontSize = 12.sp) }, placeholder = { Text("自宅・撮影地・駅など") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(address, { address = it.take(160) }, label = { Text("住所・メモ（任意）", fontSize = 12.sp) }, placeholder = { Text("奈良市登大路町") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(lat, { lat = it }, label = { Text("緯度", fontSize = 12.sp) }, placeholder = { Text("34.685") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(lon, { lon = it }, label = { Text("経度", fontSize = 12.sp) }, placeholder = { Text("135.804") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            SmallButton("現在地を使う", { geo = "現在地を取得しています…"; locate() }, icon = Icons.Outlined.GpsFixed)
            if (geo.isNotBlank()) Text("  $geo", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SelectField(LEVEL_LABELS.entries.map { it.key to it.value }, level, { level = it }, label = "通知する強さ")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(quietFrom, { quietFrom = it.filter(Char::isDigit).take(2) }, label = { Text("通知しない開始", fontSize = 11.sp) }, placeholder = { Text("23") }, singleLine = true, modifier = Modifier.weight(1f))
            Text("〜")
            OutlinedTextField(quietTo, { quietTo = it.filter(Char::isDigit).take(2) }, label = { Text("終了", fontSize = 11.sp) }, placeholder = { Text("6") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        CheckRow("この地点の雨通知を受け取る", notify) { notify = it }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("キャンセル", onCancel, enabled = !busy)
            SmallButton(if (initial != null) "更新" else "登録", {
                val la = lat.toDoubleOrNull(); val lo = lon.toDoubleOrNull()
                when {
                    name.isBlank() -> error = "地点名を入力してください"
                    la == null || la !in -90.0..90.0 || lo == null || lo !in -180.0..180.0 -> error = "緯度・経度を数値で入力してください"
                    else -> {
                        error = ""
                        onSubmit(JsonObject(mapOf(
                            "id" to (initial.str("id")?.let { JsonPrimitive(it) } ?: JsonNull), "name" to JsonPrimitive(name.trim()), "address" to JsonPrimitive(address.trim()),
                            "lat" to JsonPrimitive(la), "lon" to JsonPrimitive(lo), "level" to JsonPrimitive(level), "notify" to JsonPrimitive(notify),
                            "quiet_from" to (quietFrom.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull), "quiet_to" to (quietTo.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull),
                        ).filterValues { it != JsonNull || true }))
                    }
                }
            }, enabled = !busy, icon = Icons.Outlined.NotificationsActive, primary = true)
        }
    }
}
