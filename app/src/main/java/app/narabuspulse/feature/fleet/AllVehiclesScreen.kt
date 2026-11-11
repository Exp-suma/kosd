package app.narabuspulse.feature.fleet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.long
import app.narabuspulse.core.obj
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.feature.bus.StopTimelineSheet
import app.narabuspulse.feature.bus.Vehicle
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.OSM_ATTRIBUTION
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.StatTile
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.text.Normalizer

private val INFO = Color(0xFF2F6FB0)

/** 全車両マップ（Web: src/routes/_authed/all-vehicles.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AllVehiclesScreen(focus: String? = null) {
    val nav = LocalNav.current
    var register by remember { mutableStateOf(true) }
    val query = rememberLoad("fleet-map") { Api.rpc("catalog/fns#getFleetMapData", jsonOf("register" to register)) }
    var auto by remember { mutableStateOf(true) }
    var depot by remember { mutableStateOf("") }
    var onlyCatalog by remember { mutableStateOf(false) }
    var hideStale by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<JsonElement?>(null) }
    var stopsFor by remember { mutableStateOf<JsonElement?>(null) }
    var search by remember { mutableStateOf("") }
    val favorites = rememberFleetFavorites()
    val steps = stepLabels()

    // 新規車番の自動登録は最初の 1 回だけ（以降は取得のみ）
    LaunchedEffect(query.data) { if (query.data != null && register) register = false }
    LaunchedEffect(auto) { while (auto) { delay(60_000); query.reload() } }

    val data = query.data
    val vehicles = data.items("vehicles")
    val index = data.objAt("index")
    fun entryOf(v: JsonElement): JsonElement? = index.objAt("byCode")?.get(v.str("carCode").orEmpty()) ?: index.objAt("byPlate")?.get(normPlate(v.str("plateNo")))
    LaunchedEffect(focus, vehicles.size) {
        if (focus != null) vehicles.firstOrNull { normPlate(it.str("plateNo")) == normPlate(focus) || it.str("carCode") == focus.trim() }?.let { selected = it }
    }
    val depots = remember(index) { index.objAt("byPlate")?.values?.mapNotNull { it.str("garage")?.ifBlank { null } }?.distinct()?.sortedWith(java.text.Collator.getInstance(java.util.Locale.JAPANESE)).orEmpty() }
    val filtered = vehicles.filter { v ->
        val e = entryOf(v)
        (depot.isEmpty() || e?.str("garage") == depot) && (!onlyCatalog || e != null) && (!hideStale || v.bool("stale") != true)
    }
    val staleCount = vehicles.count { it.bool("stale") == true }
    val catalogCount = vehicles.count { entryOf(it) != null }
    val focusMissing = focus != null && data != null && vehicles.none { normPlate(it.str("plateNo")) == normPlate(focus) || it.str("carCode") == focus.trim() }

    // 検索候補（Web: FleetSearchBar）
    val results = remember(search, filtered) {
        val q = Normalizer.normalize(search, Normalizer.Form.NFKC).trim().uppercase().replace(Regex("[\\s-]"), "")
        if (q.isEmpty()) emptyList() else filtered.mapNotNull { v ->
            val e = entryOf(v)
            val fields = listOf(v.str("plateNo"), v.str("carCode"), v.str("route"), v.str("destination"), v.str("stop"), e?.str("model"), e?.str("manufacturer"), e?.str("garage"), e?.str("year"))
            val n = { f: String? -> Normalizer.normalize(f.orEmpty(), Normalizer.Form.NFKC).uppercase().replace(Regex("[\\s-]"), "") }
            if (fields.none { n(it).contains(q) }) null else (if (n(v.str("plateNo")) == q) 0 else 1) to v
        }.sortedBy { it.first }.take(8).map { it.second }
    }

    ScreenList {
        item {
            Column {
                Text("全車両マップ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("奈良交通バスロケーションの運行中車両を実際の位置で表示します。位置が取得できない車両は地図に置きません。★からお気に入りに登録できます。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SmallButton("車両図鑑", { nav.go("/vehicle-catalog") }, icon = Icons.Outlined.MenuBook)
                    SmallButton("お気に入り", { nav.go("/fleet/favorites") }, icon = Icons.Filled.Star)
                }
            }
        }
        item { FleetNav("/all-vehicles") }
        item {
            OutlinedTextField(search, { search = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("車番・車両コード・系統・行先・型式で検索", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (search.isNotBlank()) Panel {
                if (results.isEmpty()) Text("該当する運行中の車両はありません", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                results.forEach { v ->
                    val e = entryOf(v)
                    Column(Modifier.fillMaxWidth().clickable { selected = v; search = "" }.padding(vertical = 6.dp)) {
                        Text(v.str("plateNo") ?: "車番不明", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text((listOfNotNull(e?.str("model"), e?.str("manufacturer"), e?.str("garage")).filter { it.isNotBlank() }.joinToString("・").ifBlank { "図鑑の情報なし" }) + (e?.int("year")?.let { "・${it}年式" } ?: ""), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                StatTile("表示中の車両", filtered.size.toString(), Modifier.weight(1f), "台")
                StatTile("運行中の車両", vehicles.size.toString(), Modifier.weight(1f), "台")
                StatTile("図鑑登録済み", catalogCount.toString(), Modifier.weight(1f), "台", INFO)
                StatTile("位置情報が古い", staleCount.toString(), Modifier.weight(1f), "台", if (staleCount > 0) Brand.Warning else Brand.MutedFg)
                StatTile("最終取得", fleetClock(data.long("fetchedAt")), Modifier.weight(1f), tone = if (!data.str("error").isNullOrBlank()) Brand.Warning else null)
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SmallButton("更新", { query.reload() }, enabled = !query.loading, icon = Icons.Outlined.Refresh)
                CheckLabel("自動更新", auto) { auto = it }
                SelectField(listOf("" to "営業所: すべて") + depots.map { it to it }, depot, { depot = it })
                CheckLabel("図鑑登録済みのみ", onlyCatalog) { onlyCatalog = it }
                CheckLabel("古い位置を除く", hideStale) { hideStale = it }
            }
            if (staleCount > 0) Text("位置情報が古い車両が $staleCount 台あります（灰色のマーカー）。現在位置として扱いません。", fontSize = 11.sp, color = Brand.Warning)
            if (!data.str("error").isNullOrBlank()) Text("バスロケの取得に失敗しました（${data.str("error")}）。直前に取得できた内容を表示しています。", fontSize = 11.sp, color = Brand.Warning)
            if (focusMissing) Text("$focus の現在位置を取得できません。バスロケに表示されていない車両は地図に表示しません。", fontSize = 11.sp, color = Brand.Warning)
            if (query.error != null && data == null) Text(query.error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        item {
            Panel(padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                val pins = filtered.mapNotNull { v ->
                    val lat = v.dbl("lat"); val lon = v.dbl("lon")
                    if (lat == null || lon == null) return@mapNotNull null
                    val sel = selected?.let { it.str("carCode") == v.str("carCode") } == true
                    val stale = v.bool("stale") == true
                    MapPin(
                        "c-${v.str("carCode") ?: v.str("plateNo")}", lat, lon, v.str("plateNo") ?: "車番不明",
                        listOfNotNull(v.str("route")?.let { "系統 $it" }, v.str("destination"), v.str("stop")).joinToString(" / "),
                        when { sel -> Brand.Orange; stale -> Color(0xFF9AA3AB); else -> INFO }, if (sel) 20.dp else 13.dp,
                    )
                }
                OsmMap(
                    Modifier.fillMaxWidth().height(380.dp), pins = pins,
                    center = selected?.let { s -> s.dbl("lat")?.let { lat -> s.dbl("lon")?.let { lat to it } } },
                    fitKey = if (selected == null) "all:${pins.size > 0}" else null, zoom = 11.0,
                    onPinClick = { p -> selected = filtered.firstOrNull { "c-${it.str("carCode") ?: it.str("plateNo")}" == p.id } },
                )
                Text(OSM_ATTRIBUTION, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(6.dp))
            }
        }
        selected?.let { s ->
            item {
                val e = entryOf(s)
                val code = s.str("carCode").orEmpty()
                val fav = favorites.byCode.containsKey(code)
                Panel(title = s.str("plateNo") ?: "車番不明", subtitle = "${s.str("route")?.let { "系統 $it" } ?: "系統不明"}／${s.str("destination")?.ifBlank { null } ?: "行先不明"}", action = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tag(if (s.bool("stale") == true) "位置情報が古い" else "運行中", if (s.bool("stale") == true) Brand.Warning else Brand.Success)
                        SmallButton(if (fav) "お気に入り済み" else "お気に入り", { favorites.toggle(code, s.str("plateNo").orEmpty()) }, enabled = favorites.busyCode != code, icon = if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline)
                    }
                }) {
                    KeyValue("現在位置付近", s.str("stop")?.ifBlank { null } ?: "取得不可")
                    KeyValue("進行方向", compassLabel(s.dbl("direction")))
                    KeyValue("最終更新", s.long("at")?.let { "${fleetClock(it)}（${fleetAge(it)}）" } ?: "取得不可")
                    KeyValue("車いす", when (s.int("wheelchair")) { 1 -> "対応"; 0 -> if (s.str("wheelchair") == "0") "非対応" else "取得不可"; else -> "取得不可" })
                    KeyValue("型式", e?.str("model")?.ifBlank { null } ?: "図鑑未登録")
                    KeyValue("年式", e?.int("year")?.let { "${it}年" } ?: "不明")
                    KeyValue("営業所", e?.str("garage")?.ifBlank { null } ?: "不明")
                    KeyValue("ステップ数（図鑑）", e?.str("step")?.let { steps[it] ?: it.ifBlank { null } } ?: "不明")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        SmallButton("車両図鑑を開く", { nav.go("/vehicle-catalog/${e?.str("number") ?: s.str("plateNo")}") }, icon = Icons.Outlined.MenuBook)
                        if (code.isNotBlank()) SmallButton("この車両を追跡", { nav.go("/fleet/$code") }, icon = Icons.Outlined.Navigation)
                    }
                    SmallButton("停留所一覧", { stopsFor = s }, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item {
            Panel(title = "運行中の車両", subtitle = "${filtered.size} 台") {
                if (filtered.isEmpty()) Text(if (query.loading) "読み込み中…" else "条件に一致する運行中の車両はありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                filtered.forEachIndexed { i, v ->
                    if (i > 0) RowDivider()
                    val e = entryOf(v)
                    val code = v.str("carCode").orEmpty()
                    val active = selected?.str("carCode") == code
                    Row(Modifier.fillMaxWidth().background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).clickable { selected = v }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row { Text(v.str("plateNo") ?: "車番不明", fontSize = 13.sp, fontWeight = FontWeight.Bold); v.str("route")?.let { Text("  系統 $it", fontSize = 12.sp, color = INFO) } }
                            Text(listOfNotNull(v.str("destination"), v.str("stop")).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "運行情報なし" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (e != null) Text(listOfNotNull(e.str("model"), e.int("year")?.let { "${it}年" }, e.str("garage")).filter { it.isNotBlank() }.joinToString("・"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val fav = favorites.byCode.containsKey(code)
                        IconButton(onClick = { favorites.toggle(code, v.str("plateNo").orEmpty()) }, enabled = favorites.busyCode != code) {
                            Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline, if (fav) "お気に入りを解除" else "お気に入りに登録", tint = if (fav) Brand.Warning else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    stopsFor?.let { s ->
        val code = s.str("carCode")
        val v = Vehicle.from(jsonOf("name" to (s.str("plateNo") ?: "車番不明"), "url" to (code?.let { "https://navi.narakotsu.co.jp/bus_service/?carCode=$it" } ?: ""), "operation" to listOfNotNull(s.str("route"), s.str("destination")?.let { "→$it" }).joinToString("")))
        StopTimelineSheet(v, emptyList(), onClose = { stopsFor = null }, onRefresh = null, refreshing = false)
    }
}

@Composable
fun CheckLabel(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(!checked) }) {
        Checkbox(checked, onChange, modifier = Modifier.size(32.dp))
        Text(label, fontSize = 12.sp)
    }
}
