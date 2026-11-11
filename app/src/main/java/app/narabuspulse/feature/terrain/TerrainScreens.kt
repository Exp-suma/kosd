package app.narabuspulse.feature.terrain

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Export
import app.narabuspulse.core.PdfTable
import app.narabuspulse.core.Session
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
import app.narabuspulse.feature.analytics.Col
import app.narabuspulse.feature.analytics.DataTable
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.KpiRow
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.BarChart
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.LineChart
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.MapLine
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.Markdown
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.Series
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlin.math.abs
import kotlin.math.roundToInt

private val TABS = listOf("/terrain" to "地形概要", "/terrain/hazards" to "狭隘・難所マップ", "/terrain/compare" to "路線比較", "/terrain/ai" to "AI地形分析")
private val HAZARD_LABELS = linkedMapOf("steep" to "急勾配", "sharp_curve" to "急カーブ", "hairpin" to "ヘアピンカーブ", "narrow" to "狭隘区間", "narrow_candidate" to "狭隘の可能性", "pass" to "峠・最高地点", "tunnel" to "トンネル", "bridge" to "橋梁", "turning" to "転回場所", "other" to "その他")
private val VERIFICATION = linkedMapOf("unconfirmed" to "未確認", "planned" to "調査予定", "surveying" to "調査中", "confirmed" to "確認済み", "needs_update" to "情報更新が必要")
private val SEVERITY = linkedMapOf("normal" to "通常", "caution" to "注意", "hard" to "難所", "very_hard" to "非常に難しい", "unknown" to "情報不足")
private val SEVERITY_COLOR = mapOf("normal" to Color(0xFF3F9142), "caution" to Color(0xFFD9A02B), "hard" to Color(0xFFE07A1F), "very_hard" to Color(0xFFC0392B), "unknown" to Color(0xFF8A8F8B))
private val FACILITY_LABELS = linkedMapOf("turnaround" to "転回場所", "passing_place" to "待避所", "terminus" to "終点", "office" to "営業所", "other" to "その他")
private val GRADIENT_CLASSES = listOf(2.0 to "ほぼ平坦", 4.0 to "緩勾配", 6.0 to "中程度の勾配", 8.0 to "急勾配", 10.0 to "非常に急な勾配", Double.MAX_VALUE to "極めて急な勾配")
private val SRC = mapOf("elevation" to "Open-Meteo Elevation API", "elevationModel" to "Copernicus DEM GLO-90（約90m格子）", "elevationLicense" to "Open-Meteo（CC BY 4.0）/ Copernicus DEM © ESA", "geometry" to "OSRM / OpenStreetMap 道路データ", "attributes" to "OpenStreetMap（Overpass API）", "osmLicense" to "© OpenStreetMap contributors, ODbL 1.0")
private val DATA_NOTES = listOf("標高は約90m格子のDEMから取得し、100m間隔の点で断面を計算します。取得できなかった点は「未取得」として扱い、推測値では埋めません。", "勾配は 標高差 ÷ 水平距離 × 100 で算出し、短距離のノイズを抑えるため移動平均で平滑化しています。",
    "曲率半径は道路中心線（OSRM）から外接円で推定した値で、実際の線形とは異なる場合があります。", "幅員は OpenStreetMap の width / maxwidth タグのみを使用します。タグが無い区間は未取得で、道路種別から幅員を断定しません。",
    "車両の通行可否は判定しません。必要な幅員・車両諸元・法的制限が確認できない場合は「情報不足」または「要確認」と表示します。")
private fun meters(v: Double?) = v?.let { if (it >= 1000) "%.1fkm".format(it / 1000) else "${it.roundToInt()}m" } ?: "—"
private fun ratio(v: Double?) = v?.let { "${(it * 100).roundToInt()}%" } ?: "—"
private fun numb(v: Double?, unit: String = "") = v?.let { "${if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()}$unit" } ?: "—"

@Composable
private fun TerrainScaffold(current: String, title: String, description: String, content: LazyListScope.() -> Unit) {
    val nav = LocalNav.current
    ScreenList {
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { ChipRow(TABS, current, { if (it != current) nav.go(it) }); Heading(title, description) } }
        content()
    }
}

@Composable private fun SeverityBadge(s: String?) = Tag(SEVERITY[s] ?: "情報不足", SEVERITY_COLOR[s] ?: Brand.MutedFg)
@Composable private fun VerificationBadge(s: String?) = Tag(VERIFICATION[s] ?: "未確認", if (s == "confirmed") Brand.Success else Brand.MutedFg)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HazardList(hazards: List<JsonElement>, selectedId: String?, empty: String, onSelect: (JsonElement) -> Unit) {
    if (hazards.isEmpty()) { Muted(empty, 11); return }
    hazards.forEach { h ->
        Column(Modifier.fillMaxWidth().clickable { onSelect(h) }.padding(vertical = 5.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(HAZARD_LABELS[h.str("hazard_type")] ?: h.str("hazard_type").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (h.str("id") == selectedId) Brand.Orange else Color.Unspecified)
                SeverityBadge(h.str("severity")); VerificationBadge(h.str("verification_status")); h.str("name")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
            }
            Muted(listOfNotNull(h.dbl("length_m")?.takeIf { it > 0 }?.let { "${it.roundToInt()}m" }, h.dbl("width_m")?.takeIf { it > 0 }?.let { "幅員${it}m" }, (h.dbl("max_gradient") ?: h.dbl("gradient"))?.takeIf { it != 0.0 }?.let { "$it%" }, h.dbl("curvature_radius")?.takeIf { it > 0 }?.let { "R${it.roundToInt()}m" }).joinToString(" / "), 10)
            h.str("description")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
        }
    }
}

/** 経路・急勾配・急カーブ・難所・施設の地図（Web: TerrainMap.jsx） */
@Composable
private fun TerrainMap(points: List<JsonElement>, hazards: List<JsonElement>, facilities: List<JsonElement>, layers: Map<String, Boolean>, focus: Pair<Double, Double>?, height: Int = 400,
                       onPick: ((Double, Double) -> Unit)? = null, onHazard: (JsonElement) -> Unit = {}, onFacility: (JsonElement) -> Unit = {}) {
    fun on(k: String) = layers[k] != false
    val coords = remember(points) { points.mapNotNull { p -> p.dbl("lat")?.let { la -> p.dbl("lon")?.let { lo -> la to lo } } } }
    val lines = buildList {
        if (on("route") && coords.size > 1) add(MapLine(coords, Color(0xFF23343A), 6f))
        if (on("steep")) { var run = mutableListOf<Pair<Double, Double>>(); points.forEach { p -> val g = p.dbl("gradient_smoothed"); if (g != null && abs(g) >= 6 && p.dbl("lat") != null) run.add(p.dbl("lat")!! to p.dbl("lon")!!) else { if (run.size > 1) add(MapLine(run, SEVERITY_COLOR.getValue("hard"), 9f)); run = mutableListOf() } }; if (run.size > 1) add(MapLine(run, SEVERITY_COLOR.getValue("hard"), 9f)) }
    }
    val pins = buildList {
        if (on("stops")) { var last: String? = null; points.forEach { p -> val n = p.str("stop_name"); if (!n.isNullOrBlank() && n != last && p.dbl("lat") != null) { add(MapPin("stop:${p.str("sequence")}", p.dbl("lat")!!, p.dbl("lon")!!, n, "停留所", color = Color(0xFF1F2937), size = 8.dp)); last = n } } }
        if (on("sharp_curve")) points.filter { it.str("shape_type") == "hairpin" || it.str("shape_type") == "sharp_curve" }.forEach { p -> p.dbl("lat")?.let { add(MapPin("curve:${p.str("sequence")}", it, p.dbl("lon") ?: 0.0, if (p.str("shape_type") == "hairpin") "ヘアピンカーブ" else "急カーブ", color = Color(0xFFFBBF24), size = 10.dp)) } }
        if (on("hazards")) hazards.forEach { h -> val la = h.dbl("start_lat") ?: h.dbl("lat"); val lo = h.dbl("start_lon") ?: h.dbl("lon"); if (la != null && lo != null) add(MapPin("hz:${h.str("id")}", la, lo, h.str("name")?.ifBlank { null } ?: HAZARD_LABELS[h.str("hazard_type")].orEmpty(), listOfNotNull(h.dbl("width_m")?.let { "幅員${it}m" }, h.dbl("gradient")?.let { "$it%" }).joinToString(" / "), color = SEVERITY_COLOR[h.str("severity")] ?: SEVERITY_COLOR.getValue("unknown"), size = 14.dp)) }
        if (on("facilities")) facilities.forEach { f -> f.dbl("lat")?.let { add(MapPin("fc:${f.str("id")}", it, f.dbl("lon") ?: 0.0, f.str("name").orEmpty(), FACILITY_LABELS[f.str("facility_type")].orEmpty(), color = Color(0xFF2B6CB0), size = 13.dp, label = "P")) } }
        focus?.let { add(MapPin("focus", it.first, it.second, "選択中", color = Color(0xFFC0392B), size = 20.dp, label = "◎")) }
    }
    OsmMap(Modifier.fillMaxWidth().height(height.dp), pins = pins, lines = lines, center = focus ?: coords.firstOrNull() ?: (34.685 to 135.83), zoom = if (focus != null) 15.0 else 12.0, fitKey = "${coords.size}|${hazards.size}|${focus == null}",
        onPinClick = { p -> when { p.id.startsWith("hz:") -> hazards.firstOrNull { "hz:${it.str("id")}" == p.id }?.let(onHazard); p.id.startsWith("fc:") -> facilities.firstOrNull { "fc:${it.str("id")}" == p.id }?.let(onFacility) } },
        onMapClick = onPick)
}

/** 路線地形分析（Web: terrain.index.jsx + WeightsEditor.jsx） */
@Composable
fun TerrainDashboardScreen() {
    val nav = LocalNav.current; val scope = rememberCoroutineScope(); val snackbar = LocalSnackbar.current
    val load = rememberLoad { Api.rpc("terrain/server-fns#getTerrainRoutes", jsonOf()) }
    var running by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    val data = load.data; val routes = data.items("routes")
    TerrainScaffold("/terrain", "路線地形分析", "監視シートの系統と停留所、道路形状（OSRM）と標高（約90m DEM）から、路線の勾配・カーブ・山越え・狭隘の可能性を計算します。幅員はOpenStreetMapのタグだけを使い、推測で補いません。") {
        if (load.loading && data == null) item { Muted("系統と解析状況を読み込み中…", 12) }
        load.error?.let { item { Text("⚠ $it", fontSize = 12.sp, color = Brand.Destructive) } }
        if (data != null) {
            item {
                val analyzed = routes.count { it.bool("analyzed") == true }; val missing = routes.count { (it.int("coord_count") ?: 0) < 2 }
                KpiRow(Triple("系統数", "${routes.size}", null), Triple("解析済み", "$analyzed", Brand.Success), Triple("未解析", "${routes.size - analyzed}", Brand.Warning), Triple("座標が不足", "$missing", if (missing > 0) Brand.Destructive else Brand.MutedFg), units = listOf("系統", "系統", "系統", "系統"))
                data.list("notes").takeIf { it.isNotEmpty() }?.let { n -> Muted(n.joinToString(" / ") { it.text }, 10) }
            }
            item {
                Panel(title = "系統一覧", action = { SmallButton("再読込", { load.reload() }, icon = Icons.Outlined.Refresh) }) {
                    if (routes.isEmpty()) Muted("系統がありません。監視シートの運用文字列から系統と停留所を作成します。", 12)
                    routes.forEach { r ->
                        val name = r.str("route_name").orEmpty()
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { nav.go("/terrain/${android.net.Uri.encode(name)}") }) { Text("$name →", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                                Muted("停留所 ${r.int("coord_count")}/${r.int("stop_count")}${(r.int("missing_count") ?: 0).takeIf { it > 0 }?.let { "（座標なし $it）" } ?: ""}", 10)
                                Column(Modifier.weight(1f)) {}
                                SmallButton(if (running == name) "解析中…" else if (r.bool("analyzed") == true) "再解析" else "解析実行", {
                                    running = name; errors = errors - name
                                    scope.launch {
                                        try {
                                            val res = Api.rpc("terrain/server-fns#analyzeTerrainRoute", jsonOf("routeName" to name, "force" to true))
                                            when { res.str("error") != null -> { errors = errors + (name to res.str("error").orEmpty()); snackbar.showSnackbar(res.str("error").orEmpty()) }
                                                res.bool("cached") == true -> snackbar.showSnackbar("最新の解析結果があります")
                                                else -> snackbar.showSnackbar("$name を解析しました（${((res.dbl("analysis.total_distance") ?: 0.0) / 1000).roundToInt()}km・難所${res.int("hazardCount") ?: 0}件）") }
                                            load.reload()
                                        } catch (e: Throwable) { if (e is CancellationException) throw e; errors = errors + (name to e.userMessage().ifBlank { "解析できませんでした" }) } finally { running = "" }
                                    }
                                }, enabled = running != name && (r.int("coord_count") ?: 0) >= 2, icon = Icons.Outlined.PlayCircle)
                            }
                            if (r.bool("analyzed") == true) Muted("${meters(r.dbl("total_distance"))}　標高差 ${meters(r.dbl("elevation_difference"))}　最大勾配 ${r.dbl("max_gradient")?.let { "$it%" } ?: "—"}　${r.dbl("difficulty_score")?.let { "難易度 ${numb(it)}" } ?: "評価データ不足"}　${r.long("analyzed_at")?.let { BusFormat.dateTime(it) }.orEmpty()}${if (r.bool("stale") == true) "（30日以上前）" else ""}", 10)
                            else Muted("未解析", 10)
                            r.list("missing_coords").takeIf { it.isNotEmpty() }?.let { m -> Muted("座標が無い停留所: ${m.joinToString("、") { it.text }}${if ((r.int("missing_count") ?: 0) > m.size) " ほか" else ""}", 10) }
                            errors[name]?.let { Text(it, fontSize = 10.sp, color = Brand.Destructive) }
                        }
                    }
                }
            }
            item {
                val items = listOf("gradient" to "急勾配の割合", "narrow" to "狭隘道路の割合", "curve" to "急カーブ・ヘアピン", "elevation" to "標高差・累積上昇", "passing" to "待避所の少なさ")
                var form by remember(data.objAt("weights")) { mutableStateOf(items.associate { (k, _) -> k to (data.dbl("weights.$k") ?: 0.0).toString() }) }
                var busy by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf("") }
                Panel(title = "難易度スコアの重み", subtitle = "0〜1で指定します。合計 ${"%.2f".format(form.values.sumOf { it.toDoubleOrNull() ?: 0.0 })}（内部で正規化）。算出根拠は各難易度の内訳に保存されます。") {
                    items.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { (k, l) -> OutlinedTextField(form[k].orEmpty(), { v -> form = form + (k to v) }, label = { Text(l, fontSize = 10.sp) }, singleLine = true, enabled = Session.isAdmin, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) } } }
                    if (msg.isNotBlank()) Muted(msg, 11)
                    if (Session.isAdmin) SmallButton(if (busy) "保存中…" else "重みを保存", {
                        busy = true; msg = ""
                        scope.launch { try { Api.rpc("terrain/server-fns#saveDifficultyWeights", jsonOf(*items.map { (k, _) -> k to (form[k]?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 1.0) }.toTypedArray())); msg = "重みを保存しました。次回の再計算から反映されます。" } catch (e: Throwable) { if (e is CancellationException) throw e; msg = e.userMessage().ifBlank { "保存できませんでした" } } finally { busy = false } }
                    }, enabled = !busy, icon = Icons.Outlined.Save, primary = true) else Muted("変更は管理者のみ可能です。", 10)
                }
            }
            item { SourcesPanel() }
        }
    }
}

@Composable
private fun SourcesPanel() = Panel(title = "データの取得状況・出典") {
    Muted("道路形状：${SRC["geometry"]}　標高：${SRC["elevation"]}", 11); Muted("標高モデル・解像度：${SRC["elevationModel"]}　道路属性・施設：${SRC["attributes"]}", 11)
    Muted("ライセンス: ${SRC["osmLicense"]} / ${SRC["elevationLicense"]}", 10); DATA_NOTES.forEach { Muted("・$it", 10) }
}

/** 路線ごとの地形（Web: terrain.$routeName.jsx + ElevationProfile.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerrainRouteScreen(routeName: String) {
    val context = LocalContext.current; val nav = LocalNav.current; val scope = rememberCoroutineScope(); val snackbar = LocalSnackbar.current
    val load = rememberLoad(routeName) { Api.rpc("terrain/server-fns#getTerrainDetail", jsonOf("routeName" to routeName)) }
    var busy by remember { mutableStateOf(false) }
    var forward by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<JsonElement?>(null) }
    val data = load.data
    val points = data.items("points").let { if (forward) it else it.reversed() }
    val hazards = data.items("hazards"); val a = data.objAt("analysis"); val d = data.objAt("difficulty")
    fun analyze() {
        busy = true
        scope.launch {
            try { val r = Api.rpc("terrain/server-fns#analyzeTerrainRoute", jsonOf("routeName" to routeName, "force" to true)); snackbar.showSnackbar(r.str("error") ?: "解析しました（${r.int("hazardCount") ?: 0}件の難所を検出）"); load.reload() }
            catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "解析できませんでした" }) } finally { busy = false }
        }
    }
    val hazardRows = hazards.map { h -> listOf(HAZARD_LABELS[h.str("hazard_type")] ?: h.str("hazard_type").orEmpty(), h.str("start_lat").orEmpty(), h.str("start_lon").orEmpty(), h.str("length_m").orEmpty(), h.str("severity").orEmpty(), h.str("width_m").orEmpty(), h.str("max_gradient") ?: h.str("gradient").orEmpty(), h.str("curvature_radius").orEmpty(), h.str("verification_status").orEmpty(), h.str("description").orEmpty()) }
    TerrainScaffold("/terrain", "路線地形 $routeName", "道路形状と標高から算出した断面・勾配・カーブ・難所です。値は推定で、幅員はOSMタグのみです。") {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallButton("一覧へ戻る", { nav.go("/terrain") }); SmallButton(if (busy) "解析中…" else "再解析", { analyze() }, enabled = !busy, icon = Icons.Outlined.Refresh)
                SmallButton("CSV", { scope.launch { runCatching { snackbar.showSnackbar("${Export.csv(context, "terrain-$routeName.csv", listOf("種別", "開始緯度", "開始経度", "長さ(m)", "難易度", "幅員(m)", "勾配(%)", "曲率半径(m)", "確認状態", "説明"), hazardRows)} に保存しました") } } }, enabled = hazards.isNotEmpty(), icon = Icons.Outlined.Download)
                SmallButton("PDF", {
                    val an = a ?: return@SmallButton
                    val rows = listOf("路線全長" to meters(an.dbl("total_distance")), "最低／最高標高" to "${numb(an.dbl("min_elevation"), "m")} / ${numb(an.dbl("max_elevation"), "m")}", "標高差" to numb(an.dbl("elevation_difference"), "m"), "累積上昇／下降" to "${numb(an.dbl("cumulative_ascent"), "m")} / ${numb(an.dbl("cumulative_descent"), "m")}",
                        "最大勾配／平均" to "${numb(an.dbl("max_gradient"), "%")} / ${numb(an.dbl("average_gradient"), "%")}", "急勾配・急カーブ・ヘアピン" to "${an.int("steep_count") ?: 0} / ${an.int("sharp_curve_count") ?: 0} / ${an.int("hairpin_count") ?: 0}", "狭隘（可能性含む）" to "${meters(an.dbl("narrow_length"))}（${ratio(an.dbl("narrow_ratio"))}）",
                        "難易度スコア" to (if (d.str("status") == "ok") d.str("difficulty_score").orEmpty() else "評価データ不足"), "解析状態／信頼度" to "${an.str("analysis_status")} / ${an.str("confidence")}", "解析日時" to BusFormat.dateTime(an.long("analyzed_at"))).map { listOf(it.first, it.second) } +
                        hazards.take(22).map { h -> listOf(HAZARD_LABELS[h.str("hazard_type")] ?: h.str("hazard_type").orEmpty(), "${h.str("length_m") ?: 0}m / ${SEVERITY[h.str("severity")]} / ${VERIFICATION[h.str("verification_status")]}") }
                    scope.launch { runCatching { snackbar.showSnackbar("${PdfTable.save(context, "terrain-$routeName.pdf", "路線地形レポート $routeName", "道路形状と標高からの推定値", listOf("項目", "値"), rows)} に保存しました") } }
                }, enabled = a != null, icon = Icons.Outlined.PictureAsPdf)
            }
        }
        if (load.loading && data == null) item { Muted("地形データを読み込み中…", 12) }
        load.error?.let { item { Text("⚠ $it", fontSize = 12.sp, color = Brand.Destructive) } }
        if (data != null && a == null) item { Panel { Text("この路線はまだ解析していません", fontSize = 13.sp, fontWeight = FontWeight.Bold); Muted("「再解析」で道路形状・標高・道路属性を取得して地形を計算します（初回は数十秒かかります）。", 11); SmallButton(if (busy) "解析中…" else "解析を実行", { analyze() }, enabled = !busy, primary = true) } }
        if (data != null && a != null) {
            item {
                KpiRow(Triple("路線全長", meters(a.dbl("total_distance")), null), Triple("標高差", meters(a.dbl("elevation_difference")), INFO), Triple("最高/最低(m)", "${numb(a.dbl("max_elevation"))}/${numb(a.dbl("min_elevation"))}", null))
                KpiRow(Triple("難易度スコア", if (d.str("status") == "ok") d.str("difficulty_score").orEmpty() else "データ不足", Brand.Warning), Triple("最大勾配", numb(a.dbl("max_gradient"), "%"), Brand.Destructive), Triple("累積上昇/下降(m)", "${numb(a.dbl("cumulative_ascent"))}/${numb(a.dbl("cumulative_descent"))}", null))
                KpiRow(Triple("急勾配/急カーブ", "${a.int("steep_count") ?: 0}/${a.int("sharp_curve_count") ?: 0}", null), Triple("ヘアピン/峠候補", "${a.int("hairpin_count") ?: 0}/${a.int("pass_count") ?: 0}", null), units = listOf("か所", "か所"))
                Muted("解析 ${a.str("analysis_status")} / 信頼度 ${a.str("confidence")}　山間区間 ${ratio(a.dbl("mountain_ratio"))} / 平坦 ${ratio(a.dbl("flat_ratio"))}　幅員取得 ${ratio(a.dbl("width_known_ratio"))} / 未取得点 ${a.int("elevation_fail_count") ?: 0}　${BusFormat.dateTime(a.long("analyzed_at"))}", 10)
                SmallButton(if (forward) "⇄ 上り（起点→終点）" else "⇄ 下り（終点→起点）", { forward = !forward })
            }
            item { TerrainMap(points, hazards, data.items("facilities"), emptyMap(), selected?.let { s -> (s.dbl("start_lat") ?: s.dbl("lat"))?.let { la -> la to (s.dbl("start_lon") ?: s.dbl("lon") ?: 0.0) } }, onHazard = { selected = it }) }
            item {
                val prof = points.filter { it.dbl("elevation") != null }
                Panel(title = "標高断面と勾配") {
                    if (prof.isEmpty()) Muted("標高データがありません。解析を実行してください。", 11)
                    else {
                        val lo = prof.minOf { it.dbl("elevation")!! }; val hi = prof.maxOf { it.dbl("elevation")!! }
                        Muted("最低 ${lo.roundToInt()}m　最高 ${hi.roundToInt()}m　標高差 ${(hi - lo).roundToInt()}m　急勾配 ${hazards.count { it.str("hazard_type") == "steep" }}か所 / 最高地点候補 ${hazards.count { it.str("hazard_type") == "pass" }}か所", 11)
                        val step = maxOf(1, prof.size / 120); val s = prof.filterIndexed { i, _ -> i % step == 0 }
                        val labels = s.map { "%.1f".format((it.dbl("distance_from_start") ?: 0.0) / 1000) }
                        LineChart(labels, listOf(Series("標高(m)", Brand.Orange, s.map { it.dbl("elevation")!! })), height = 200.dp, labelEvery = maxOf(1, s.size / 6))
                        BarChart(labels, listOf(Series("勾配(%)", Brand.Warning, s.map { abs(it.dbl("gradient_smoothed") ?: 0.0) })), height = 110.dp, labelEvery = maxOf(1, s.size / 6), valueSuffix = "%")
                        Muted("横軸は起点からの距離（km）。上段: 標高（Open-Meteo / Copernicus DEM 約90m格子）、下段: 進行方向の勾配の大きさ（標高差÷水平距離×100、平滑化後）。いずれも推定値です。", 10)
                    }
                }
            }
            item { Panel(title = "検出した難所（${hazards.size}件）") { HazardList(hazards, selected.str("id"), "難所は検出されていません。") { selected = it } } }
            selected?.let { s -> item { Panel(title = HAZARD_LABELS[s.str("hazard_type")] ?: s.str("hazard_type") ?: "地点") { Muted("${"%.5f".format(s.dbl("start_lat") ?: 0.0)}, ${"%.5f".format(s.dbl("start_lon") ?: 0.0)}${s.dbl("distance_from_start")?.let { " / 起点から ${meters(it)}" } ?: ""}", 11); s.str("description")?.let { Muted(it, 11) }; Muted("出典: ${s.str("source") ?: "—"}", 10) } } }
            item {
                Panel(title = "勾配区分の内訳") {
                    val all = data.items("points")
                    GRADIENT_CLASSES.forEachIndexed { i, (max, label) -> val min = if (i == 0) 0.0 else GRADIENT_CLASSES[i - 1].first; Row { Text(label, fontSize = 11.sp, modifier = Modifier.weight(1f)); Muted("${all.count { p -> p.dbl("gradient_smoothed")?.let { g -> abs(g) >= min && abs(g) < max } == true }}点", 11) } }
                    (a.int("elevation_fail_count") ?: 0).takeIf { it > 0 }?.let { Row { Text("標高未取得", fontSize = 11.sp, modifier = Modifier.weight(1f)); Muted("${it}点", 11) } }
                }
            }
            if (d != null) item {
                Panel(title = "難易度スコアの内訳") {
                    listOf("急勾配" to "gradient_score", "狭隘" to "narrow_score", "カーブ" to "curve_score", "標高差" to "elevation_score", "待避所の少なさ" to "passing_score").forEach { (l, k) -> Row { Text(l, fontSize = 11.sp, modifier = Modifier.weight(1f)); Muted(numb(d.dbl(k)), 11) } }
                    Row { Text("総合", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(if (d.str("status") == "ok") d.str("difficulty_score").orEmpty() else "評価データ不足", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    d.str("breakdown")?.let { Muted("算出根拠（入力値）: $it", 9) }
                }
            }
            item { Muted("出典: ${a.str("data_source")} / ${a.str("source_license")}", 10) }
        }
    }
}

private val HAZARD_LAYERS = listOf("route" to "路線経路", "stops" to "停留所", "steep" to "急勾配", "sharp_curve" to "急カーブ", "hairpin" to "ヘアピンカーブ", "narrow" to "狭隘道路", "narrow_candidate" to "すれ違い困難の可能性", "pass" to "峠・山越え", "tunnel" to "トンネル", "bridge" to "橋梁", "facilities" to "待避所・転回場所")

/** 狭隘路線・難所マップ（Web: terrain.hazards.jsx + HazardForm.jsx + FacilityForm.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerrainHazardsScreen() {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val snackbar = LocalSnackbar.current
    var routeName by remember { mutableStateOf("") }
    var layers by remember { mutableStateOf(HAZARD_LAYERS.associate { it.first to true }) }
    var mode by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var selected by remember { mutableStateOf<JsonElement?>(null) }
    var editing by remember { mutableStateOf<JsonElement?>(null) }
    val routesLoad = rememberLoad { Api.rpc("terrain/server-fns#getTerrainRoutes", jsonOf()) }
    val load = rememberLoad(routeName) { Api.rpc("terrain/server-fns#getHazardMap", if (routeName.isBlank()) jsonOf() else jsonOf("routeName" to routeName)) }
    val data = load.data; val hazards = data.items("hazards"); val facilities = data.items("facilities"); val surveys = data.items("surveys")
    val visible = hazards.filter { layers[it.str("hazard_type")] != false }
    fun done() { load.reload(); mode = ""; picked = null; editing = null }
    TerrainScaffold("/terrain/hazards", "狭隘路線・難所マップ", "急勾配・カーブ・狭隘の可能性・待避所・転回場所を重ねて表示します。地図をタップすると現地調査の情報を登録できます。") {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SelectField(listOf("" to "すべての系統") + routesLoad.data.items("routes").map { it.str("route_name").orEmpty() to "${it.str("route_name")}（${it.int("coord_count")}停留所）" }, routeName, { routeName = it; selected = null })
                SmallButton("CSV", { scope.launch { runCatching { snackbar.showSnackbar("${Export.csv(context, "hazards-${routeName.ifBlank { "all" }}.csv", listOf("系統", "種類", "緯度", "経度", "長さ(m)", "難易度", "幅員(m)", "幅員の出典", "勾配(%)", "曲率半径(m)", "確認状態", "出典", "説明"),
                    hazards.map { h -> listOf(h.str("route_name").orEmpty(), HAZARD_LABELS[h.str("hazard_type")] ?: h.str("hazard_type").orEmpty(), h.str("start_lat").orEmpty(), h.str("start_lon").orEmpty(), h.str("length_m").orEmpty(), h.str("severity").orEmpty(), h.str("width_m").orEmpty(), h.str("width_source").orEmpty(), h.str("max_gradient") ?: h.str("gradient").orEmpty(), h.str("curvature_radius").orEmpty(), h.str("verification_status").orEmpty(), h.str("source").orEmpty(), h.str("description").orEmpty()) })} に保存しました") } } }, enabled = hazards.isNotEmpty(), icon = Icons.Outlined.Download)
                FilterChip(mode == "hazard", { mode = if (mode == "hazard") "" else "hazard"; picked = null; selected = null; editing = null }, label = { Text("📍 難所を登録", fontSize = 11.sp) })
                FilterChip(mode == "facility", { mode = if (mode == "facility") "" else "facility"; picked = null; selected = null; editing = null }, label = { Text("＋ 転回場所・待避所を登録", fontSize = 11.sp) })
            }
            if (mode.isNotBlank()) Muted("地図をタップして地点を選んでください", 11)
        }
        if (load.loading && data == null) item { Muted("難所データを読み込み中…", 12) }
        load.error?.let { item { Text("⚠ $it", fontSize = 12.sp, color = Brand.Destructive) } }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { HAZARD_LAYERS.forEach { (k, l) -> FilterChip(layers[k] != false, { layers = layers + (k to (layers[k] == false)) }, label = { Text(l, fontSize = 10.sp) }) } } }
        item {
            TerrainMap(data.items("points"), visible, if (layers["facilities"] != false) facilities else emptyList(), layers, selected?.let { s -> (s.dbl("start_lat") ?: s.dbl("lat"))?.let { it to (s.dbl("start_lon") ?: s.dbl("lon") ?: 0.0) } } ?: picked, height = 440,
                onPick = { la, lo -> picked = la to lo; selected = null }, onHazard = { selected = it; mode = ""; editing = null }, onFacility = { selected = it; mode = ""; editing = null })
            Muted("難所 ${hazards.size}件 / 施設 ${facilities.size}件 / 調査 ${surveys.size}件", 10)
        }
        if (mode == "hazard") item(key = "hf-${editing.str("id")}") { Panel(title = "難所の登録・現地調査") { HazardForm(routeName, picked, editing, { mode = ""; picked = null; editing = null }) { done() } } }
        if (mode == "facility") item(key = "ff-${editing.str("id")}") { Panel(title = "転回場所・待避所の登録") { FacilityForm(routeName, picked, editing, { mode = ""; picked = null; editing = null }) { done() } } }
        val s = selected
        if (s != null && mode.isBlank()) item {
            val isHazard = s.str("hazard_type") != null
            Panel(title = "${if (isHazard) HAZARD_LABELS[s.str("hazard_type")] ?: s.str("hazard_type") else FACILITY_LABELS[s.str("facility_type")] ?: "地点"}${s.str("name")?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { s.str("severity")?.let { SeverityBadge(it) }; VerificationBadge(s.str("verification_status")); s.str("route_name")?.takeIf { it.isNotBlank() }?.let { Tag("系統 $it", Brand.MutedFg) } }
                Muted("位置 ${"%.5f".format(s.dbl("start_lat") ?: s.dbl("lat") ?: 0.0)}, ${"%.5f".format(s.dbl("start_lon") ?: s.dbl("lon") ?: 0.0)}", 11)
                if (isHazard) Muted("区間長 ${meters(s.dbl("length_m"))}　幅員 ${s.dbl("width_m")?.takeIf { it > 0 }?.let { "${it}m（${s.str("width_source") ?: "出典不明"}）" } ?: "未取得"}　勾配 ${(s.dbl("max_gradient") ?: s.dbl("gradient"))?.let { "$it%" } ?: "—"} / ${s.dbl("curvature_radius")?.let { "R${it.roundToInt()}m" } ?: "—"}", 11)
                else Muted("転回方法 ${when (s.str("turn_method")) { "loop" -> "周回"; "yard" -> "構内"; "back" -> "切り返し"; else -> "未確認" }}", 11)
                Muted("出典 ${s.str("source") ?: "—"}", 10); s.str("description")?.takeIf { it.isNotBlank() }?.let { Muted(it, 11) }; s.str("notes")?.takeIf { it.isNotBlank() }?.let { Muted(it, 11) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SmallButton("情報を更新", { mode = if (isHazard) "hazard" else "facility"; editing = s; picked = null }); SmallButton("閉じる", { selected = null }) }
            }
        }
        item { Panel(title = "難所一覧") { HazardList(hazards, selected.str("id"), "難所がありません。解析を実行するか、地図から登録してください。") { selected = it; mode = "" } } }
        item { Panel(title = "転回場所・待避所") { if (facilities.isEmpty()) Muted("登録がありません。", 11); facilities.forEach { f -> Row(Modifier.fillMaxWidth().clickable { selected = f; mode = "" }.padding(vertical = 4.dp)) { Text(f.str("name").orEmpty(), fontSize = 12.sp, modifier = Modifier.weight(1f)); Muted("${FACILITY_LABELS[f.str("facility_type")]}　${VERIFICATION[f.str("verification_status")]}", 10) } } } }
        item {
            Panel(title = "現地調査の記録") {
                if (surveys.isEmpty()) Muted("記録がありません。", 11)
                surveys.take(40).forEach { sv -> Muted("${sv.str("survey_date")}　${VERIFICATION[sv.str("verification_status")]}　${sv.dbl("measured_width")?.let { "実測幅員 ${it}m / " } ?: ""}${when (sv.str("passing_possible")) { "yes" -> "すれ違い可"; "difficult" -> "すれ違い困難"; "no" -> "すれ違い不可"; else -> "すれ違い未確認" }}${sv.str("notes")?.takeIf { it.isNotBlank() }?.let { " / $it" } ?: ""}", 10) }
            }
        }
    }
}

@Composable private fun F(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), number: Boolean = false, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 10.sp) }, singleLine = true, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HazardForm(routeName: String, point: Pair<Double, Double>?, hazard: JsonElement?, onCancel: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(hazard.str("hazard_type") ?: "narrow") }; var name by remember { mutableStateOf(hazard.str("name").orEmpty()) }; var severity by remember { mutableStateOf(hazard.str("severity") ?: "unknown") }
    var width by remember { mutableStateOf(hazard.str("width_m").orEmpty()) }; var widthSource by remember { mutableStateOf(hazard.str("width_source").orEmpty()) }; var status by remember { mutableStateOf(hazard.str("verification_status") ?: "unconfirmed") }
    var description by remember { mutableStateOf(hazard.str("description").orEmpty()) }
    var measured by remember { mutableStateOf("") }; var passing by remember { mutableStateOf("unknown") }; var surface by remember { mutableStateOf("") }; var large by remember { mutableStateOf("unknown") }; var notes by remember { mutableStateOf("") }; var photo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
    val lat = hazard.dbl("start_lat") ?: point?.first; val lon = hazard.dbl("start_lon") ?: point?.second
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch { try { val mime = context.contentResolver.getType(uri) ?: "image/jpeg"; val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: return@launch; photo = Api.uploadPrivateFile("survey.${mime.substringAfter('/')}", mime, bytes) } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "写真をアップロードできませんでした" } } finally { busy = false } }
    }
    Muted("地点 ${lat?.let { "%.5f".format(it) } ?: "—"}, ${lon?.let { "%.5f".format(it) } ?: "—"}（地図をタップすると変わります）", 11)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectField(HAZARD_LABELS.toList(), type, { type = it }, label = "種類"); SelectField(SEVERITY.toList(), severity, { severity = it }, label = "難易度（参考）"); SelectField(VERIFICATION.toList(), status, { status = it }, label = "確認状態")
    }
    F("名称（不明なら空欄）", name) { name = it }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { F("幅員（m）", width, Modifier.weight(1f), true) { width = it }; F("幅員の出典", widthSource, Modifier.weight(1f)) { widthSource = it } }
    F("説明・根拠", description) { description = it }
    Text("現地調査（任意）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        F("実測幅員（m）", measured, Modifier.padding(0.dp), true) { measured = it }
        SelectField(listOf("unknown" to "未確認", "yes" to "可能", "difficult" to "困難", "no" to "不可"), passing, { passing = it }, label = "すれ違い可否")
        SelectField(listOf("unknown" to "未確認", "yes" to "通行あり", "caution" to "注意", "no" to "通行なし"), large, { large = it }, label = "大型車の通行")
    }
    F("舗装・路面", surface) { surface = it }; F("調査メモ・調査者", notes) { notes = it }
    Row(verticalAlignment = Alignment.CenterVertically) { SmallButton("写真を追加（非公開ストレージ）", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy); if (photo.isNotBlank()) Muted("  添付済み", 10) }
    if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton("閉じる", onCancel)
        SmallButton(if (busy) "保存中…" else if (hazard != null) "更新" else "登録", {
            if (lat == null || lon == null) { error = "地図上で地点を選んでください"; return@SmallButton }
            busy = true; error = ""
            scope.launch {
                try {
                    val rn = routeName.ifBlank { hazard.str("route_name").orEmpty() }
                    val saved = Api.rpc("terrain/server-fns#saveHazard", jsonOf("id" to hazard.str("id"), "data" to jsonOf("route_name" to rn, "hazard_type" to type, "name" to name.ifBlank { null }, "start_lat" to lat, "start_lon" to lon, "end_lat" to (hazard.dbl("end_lat") ?: lat), "end_lon" to (hazard.dbl("end_lon") ?: lon),
                        "length_m" to hazard.dbl("length_m"), "severity" to severity, "width_m" to width.toDoubleOrNull(), "width_source" to widthSource.ifBlank { null }, "gradient" to hazard.dbl("gradient"), "description" to description.ifBlank { null }, "source" to (hazard.str("source") ?: "現地調査・手動登録"), "verification_status" to status)))
                    if (measured.isNotBlank() || photo.isNotBlank() || notes.isNotBlank() || passing != "unknown") Api.rpc("terrain/server-fns#saveSurvey", jsonOf("route_name" to rn, "hazard_id" to saved.str("id"), "survey_type" to (if (type.startsWith("narrow")) "narrow" else if (type == "pass") "other" else type),
                        "survey_date" to java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).toString(), "lat" to lat, "lon" to lon, "measured_width" to measured.toDoubleOrNull(), "width_method" to "measure", "passing_possible" to passing, "surface" to surface.ifBlank { null }, "large_vehicle" to large,
                        "photo_uri" to photo.ifBlank { null }, "notes" to notes.ifBlank { null }, "source" to "現地調査", "verification_status" to (if (status == "unconfirmed") "surveying" else status)))
                    onSaved()
                } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "保存できませんでした" } } finally { busy = false }
            }
        }, enabled = !busy, primary = true)
    }
    Muted("写真は非公開ストレージに保存されます。幅員・通行可否は、実測または公式資料で確認できた場合のみ確定してください。", 10)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacilityForm(routeName: String, point: Pair<Double, Double>?, facility: JsonElement?, onCancel: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(facility.str("facility_type") ?: "turnaround") }; var name by remember { mutableStateOf(facility.str("name").orEmpty()) }; var area by remember { mutableStateOf(facility.str("area_m2").orEmpty()) }
    var turn by remember { mutableStateOf(facility.str("turn_method") ?: "unknown") }; var constraints by remember { mutableStateOf(facility.str("vehicle_constraints").orEmpty()) }; var description by remember { mutableStateOf(facility.str("description").orEmpty()) }
    var status by remember { mutableStateOf(facility.str("verification_status") ?: "unconfirmed") }; var notes by remember { mutableStateOf(facility.str("notes").orEmpty()) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
    val lat = facility.dbl("lat") ?: point?.first; val lon = facility.dbl("lon") ?: point?.second
    Muted("地点 ${lat?.let { "%.5f".format(it) } ?: "—"}, ${lon?.let { "%.5f".format(it) } ?: "—"}（地図をタップすると変わります）", 11)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectField(FACILITY_LABELS.toList(), type, { type = it }, label = "種類"); SelectField(VERIFICATION.toList(), status, { status = it }, label = "確認状態")
        SelectField(listOf("unknown" to "未確認", "loop" to "周回", "yard" to "構内", "back" to "切り返し"), turn, { turn = it }, label = "転回方法")
    }
    F("名称", name) { name = it }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { F("面積（㎡）", area, Modifier.weight(1f), true) { area = it }; F("車両の制約", constraints, Modifier.weight(1f)) { constraints = it } }
    F("説明", description) { description = it }; F("メモ", notes) { notes = it }
    if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton("閉じる", onCancel)
        SmallButton(if (busy) "保存中…" else if (facility != null) "更新" else "登録", {
            if (name.isBlank()) { error = "名称を入力してください"; return@SmallButton }
            if (lat == null || lon == null) { error = "地図上で地点を選んでください"; return@SmallButton }
            busy = true; error = ""
            scope.launch {
                try { Api.rpc("terrain/server-fns#saveFacility", jsonOf("id" to facility.str("id"), "data" to jsonOf("facility_type" to type, "name" to name.trim(), "lat" to lat, "lon" to lon, "route_name" to routeName.ifBlank { facility.str("route_name") }, "area_m2" to area.toDoubleOrNull(), "turn_method" to turn,
                    "vehicle_constraints" to constraints.ifBlank { null }, "description" to description.ifBlank { null }, "source" to (facility.str("source") ?: "現地調査・手動登録"), "verification_status" to status, "notes" to notes.ifBlank { null }))); onSaved() }
                catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "保存できませんでした" } } finally { busy = false }
            }
        }, enabled = !busy, primary = true)
    }
}

/** 路線比較（Web: terrain.compare.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerrainCompareScreen() {
    val routesLoad = rememberLoad { Api.rpc("terrain/server-fns#getTerrainRoutes", jsonOf()) }
    val routes = routesLoad.data.items("routes")
    var selected by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(routes.size) { if (selected.isEmpty() && routes.isNotEmpty()) selected = routes.filter { it.bool("analyzed") == true }.take(3).map { it.str("route_name").orEmpty() }.ifEmpty { routes.take(2).map { it.str("route_name").orEmpty() } } }
    val load = rememberLoad(selected) { if (selected.isEmpty()) null else Api.rpc("terrain/server-fns#compareTerrainRoutes", jsonOf("routes" to selected)) }
    val rows = load.data.items("rows")
    val cols: List<Pair<String, (JsonElement) -> String>> = listOf("距離" to { r -> meters(r.dbl("total_distance")) }, "標高差" to { r -> meters(r.dbl("elevation_difference")) }, "最高標高" to { r -> numb(r.dbl("max_elevation"), "m") }, "最低標高" to { r -> numb(r.dbl("min_elevation"), "m") },
        "累積上昇" to { r -> meters(r.dbl("cumulative_ascent")) }, "最大勾配" to { r -> numb(r.dbl("max_gradient"), "%") }, "急勾配" to { r -> numb(r.dbl("steep_count"), "か所") }, "急カーブ" to { r -> numb(r.dbl("sharp_curve_count"), "か所") }, "ヘアピン" to { r -> numb(r.dbl("hairpin_count"), "か所") },
        "狭隘（可能性含む）" to { r -> meters(r.dbl("narrow_length")) }, "待避所・転回場" to { r -> "${r.int("facility_count") ?: 0}か所" }, "山間区間" to { r -> ratio(r.dbl("mountain_ratio")) }, "難易度" to { r -> if (r.str("difficulty_status") == "ok") numb(r.dbl("difficulty_score")) else "データ不足" }, "データ取得率" to { r -> ratio(r.dbl("data_completeness")) })
    TerrainScaffold("/terrain/compare", "路線比較", "解析済みの路線を地形・道路条件で比較します。未解析の路線は「—」または「データ不足」と表示し、推測値は出しません。") {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { routes.forEach { r -> val n = r.str("route_name").orEmpty(); FilterChip(n in selected, { selected = if (n in selected) selected - n else if (selected.size >= 8) selected else selected + n }, label = { Text("$n${if (r.bool("analyzed") != true) " 未解析" else ""}", fontSize = 11.sp) }) } }
            Muted("最大8路線まで比較できます（現在 ${selected.size}）。", 10)
        }
        if (load.loading) item { Muted("比較しています…", 12) }
        load.error?.let { item { Text("⚠ $it", fontSize = 12.sp, color = Brand.Destructive) } }
        if (rows.isNotEmpty()) {
            item { Panel { DataTable(listOf(Col("項目", 130.dp)) + rows.map { Col(it.str("route_name").orEmpty(), 84.dp, true) }, cols.map { (label, f) -> listOf(label) + rows.map { r -> if (r.bool("analyzed") == true) f(r) else "—" } }) } }
            item {
                Panel(title = "難易度・勾配・標高差の比較") {
                    val names = rows.map { it.str("route_name").orEmpty() }
                    BarChart(names, listOf(Series("難易度", Brand.Warning, rows.map { if (it.str("difficulty_status") == "ok") it.dbl("difficulty_score") ?: 0.0 else 0.0 }), Series("最大勾配(%)", Brand.Destructive, rows.map { it.dbl("max_gradient") ?: 0.0 })), height = 220.dp)
                    BarChart(names, listOf(Series("標高差(m)", INFO, rows.map { it.dbl("elevation_difference") ?: 0.0 })), height = 160.dp, valueSuffix = "m")
                    Muted("難易度は0〜100（高いほど厳しい）。データ取得率が30%未満の路線は「データ不足」として数値を出しません。", 10)
                }
            }
            item { Panel(title = "比較の前提") { Muted("標高は約90m格子DEM、道路形状はOSRM、幅員・トンネル・橋梁はOpenStreetMapのタグに基づきます。幅員タグが無い区間は未取得として扱います。", 10); Muted("「狭隘（可能性含む）」には車線数1かつ幅員不明の区間も含まれます。実際の通行可否は保証しません。", 10) } }
        }
    }
}

private val AI_EXAMPLES = listOf("この路線で最も勾配が厳しい場所はどこですか", "大型バスの通行が難しい可能性がある区間はどこですか", "最も狭い道路区間と、幅員の出典を教えてください", "待避所の間隔が長い区間はどこですか", "急勾配と急カーブが重なっている場所はどこですか", "追加調査が必要な場所はどこですか")

/** AI地形分析（Web: terrain.ai.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerrainAiScreen() {
    val scope = rememberCoroutineScope()
    val routesLoad = rememberLoad { Api.rpc("terrain/server-fns#getTerrainRoutes", jsonOf()) }
    var routeName by remember { mutableStateOf("") }; var question by remember { mutableStateOf("") }; var result by remember { mutableStateOf<JsonElement?>(null) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
    fun submit(text: String? = null) {
        val q = (text ?: question).trim(); if (q.length < 2) return
        question = q; busy = true; error = ""; result = null
        scope.launch { try { result = Api.rpc("terrain/server-fns#askTerrainAi", jsonOf("question" to q, "routeName" to routeName.ifBlank { null })) } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "回答を取得できませんでした" } } finally { busy = false } }
    }
    TerrainScaffold("/terrain/ai", "AI地形分析", "保存済みの地形解析・難所の実データだけを根拠に回答します。データに無い幅員や通行可否は答えません。") {
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) { SelectField(listOf("" to "すべての系統") + routesLoad.data.items("routes").map { it.str("route_name").orEmpty() to it.str("route_name").orEmpty() }, routeName, { routeName = it }); Muted("  解析済みの路線だけが対象です", 10) }
                OutlinedTextField(question, { question = it }, placeholder = { Text("例: この路線で最も勾配が厳しい場所はどこですか", fontSize = 12.sp) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                SmallButton(if (busy) "分析中…" else "質問する", { submit() }, enabled = !busy && question.trim().length >= 2, icon = Icons.Outlined.AutoAwesome, primary = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { AI_EXAMPLES.forEach { ex -> FilterChip(false, { submit(ex) }, enabled = !busy, label = { Text(ex, fontSize = 10.sp) }) } }
            }
        }
        if (error.isNotBlank()) item { Text(error, fontSize = 12.sp, color = Brand.Destructive) }
        result?.let { r -> item { Panel(title = "回答", subtitle = "根拠: 解析${r.int("routeCount") ?: 0}路線 / 難所${r.int("hazardCount") ?: 0}件 ・ ${BusFormat.dateTime(r.long("generatedAt"))}") { Markdown(r.str("answer").orEmpty()); Muted("参照データ: ${r.list("sources").joinToString(" / ") { it.text }}", 10) } } }
        item { Panel(title = "回答の前提") { DATA_NOTES.forEach { Muted("・$it", 10) }; Muted("道路形状: ${SRC["geometry"]}　標高: ${SRC["elevation"]}（${SRC["elevationModel"]}）　道路属性: ${SRC["attributes"]}　${SRC["osmLicense"]}", 10) } }
    }
}
