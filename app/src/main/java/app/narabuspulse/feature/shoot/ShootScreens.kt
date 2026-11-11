package app.narabuspulse.feature.shoot

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.objAt
import app.narabuspulse.core.rememberLocationRequest
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusGate
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.MapLine
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

private val TABS = listOf("/shoot/light" to "光線シミュレーション", "/shoot/environment" to "撮影環境予測", "/shoot/spots" to "撮影スポット検索", "/shoot/chances" to "撮影チャンス", "/shoot/times" to "おすすめ撮影時間", "/shoot/favorites" to "お気に入り撮影地点")
internal fun hex(v: String?, fallback: Color = Brand.MutedFg): Color = runCatching { Color(android.graphics.Color.parseColor(v)) }.getOrDefault(fallback)
private fun deg(v: Double?) = v?.let { "${it.roundToInt()}°" } ?: "—"
private fun orNone(v: String?) = v ?: "取得できません"
private fun gmaps(origin: Pair<Double, Double>?, dest: Pair<Double, Double>, mode: String): String =
    "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=%s".format(dest.first, dest.second, mode) + (origin?.let { "&origin=%.6f,%.6f".format(it.first, it.second) } ?: "")

/** 6 画面共通の枠（見出し・タブ・取得失敗の通知・操作欄）。 */
@Composable
private fun ShootScaffold(current: String, view: JsonObject? = null, showStep: Boolean = false, content: LazyListScope.(ShootModel) -> Unit) {
    BusGate { data ->
        val nav = LocalNav.current
        val m = rememberShootModel(data, view)
        ScreenList {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Heading("AI撮影支援", "走行経路・停留所座標・太陽位置・気象予報を組み合わせ、光線状態・撮影環境スコア・撮影スポットを同じデータから算出します。地点ごとの気象は予報APIの値をそのまま使い、取得できない値は推測しません。")
                    ChipRow(TABS, current, { if (it != current) nav.go(it) })
                }
            }
            listOf(Triple("気象データ", m.weather.error, m.weather.reload), Triple("道路形状", m.road.error, m.road.reload), Triple("停留所座標", m.geo.error.orEmpty(), m.geo.reload)).filter { it.second.isNotBlank() }.forEach { (label, msg, retry) ->
                item {
                    Row(Modifier.fillMaxWidth().background(Brand.Warning.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("⚠ ${label}を取得できませんでした。", fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted("$msg（取得できなかった項目は「データなし」と表示します）", 10) }
                        SmallButton("再取得", retry, icon = Icons.Outlined.Refresh)
                    }
                }
            }
            item { ShootControls(m, showStep) }
            if (m.r == null) item { Muted(if (m.geo.pending) "停留所の座標を取得しています…" else "計算しています…", 12) }
            else content(m)
        }
    }
}

/** 路線・撮影日・時刻・目的（Web: ShootControls.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShootControls(m: ShootModel, showStep: Boolean) {
    val p = ShootPrefs
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SelectField(if (m.routeOptions.isEmpty()) listOf("" to "系統が見つかりません") else m.routeOptions.map { it.route to "${it.route}（${it.stops}停留所・${it.vehicles}台）" }, p.routeName, { p.routeName = it }, label = "路線・系統")
            Muted("撮影日", 11); DateField(p.date, { p.date = it.ifBlank { jstToday() } })
            FilterChip(p.mode == "now", { p.mode = "now"; p.date = jstToday() }, label = { Text("現在時刻", fontSize = 11.sp) })
            FilterChip(p.mode == "set", { p.mode = "set" }, label = { Text("日時指定", fontSize = 11.sp) })
            if (p.mode == "set") TimeField(p.time, { p.time = it })
            SelectField(m.meta.items("purposes").map { it.str("key").orEmpty() to it.str("label").orEmpty() }.ifEmpty { listOf("normal" to "通常のバス撮影") }, p.purpose, { p.purpose = it }, label = "撮影目的")
            if (showStep) SelectField(listOf(15 to "15分", 30 to "30分"), if (p.stepMin == 15) 15 else 30, { p.stepMin = it }, label = "時間の粒度")
        }
        if (m.road.pending) Muted("道路形状を解析しています…", 10)
    }
}

@Composable
private fun Row2(label: String, value: String, note: String = "") = Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    Text(label, fontSize = 11.sp, color = Brand.MutedFg, modifier = Modifier.weight(1f)); Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold); if (note.isNotBlank()) Muted("  $note", 10)
}

/** 太陽位置（Web: SunPanel.jsx） */
@Composable
private fun SunPanel(m: ShootModel) {
    val r = m.r; val az = r.dbl("sun.azimuth"); val alt = r.dbl("sun.altitude")
    Panel(title = "☀ 太陽位置", subtitle = "${if (ShootPrefs.sunMode == "manual") "手動（仮想条件）" else if (ShootPrefs.mode == "now") "現在時刻" else "指定日時"}・日本標準時") {
        Row2("撮影日時", r.str("_at").orEmpty())
        Row2("太陽方位角", az?.let { "%.1f°".format(it) } ?: "—", r.str("sun._compass").orEmpty())
        Row2("太陽高度", alt?.let { "%.1f°".format(it) } ?: "—")
        Row2("光線の方向", r.str("sun._compass")?.takeIf { it.isNotBlank() }?.let { "${it}から差し込む" } ?: "—")
        Row2("日の出", r.str("times._sunrise") ?: "—"); Row2("南中", r.str("times._noon") ?: "—"); Row2("日の入り", r.str("times._sunset") ?: "—")
        if (alt != null && alt <= 0) Text("太陽高度が ${"%.1f".format(alt)}° のため、太陽は地平線より下にあります（直射光なし）。色温度と街灯の影響で、日中とは異なる露出が必要です。", fontSize = 11.sp, color = Brand.Warning)
        Muted("太陽位置は座標と日時からの天文計算（NOAA の式）です。天候による減光は含みません。", 10)
        Muted("日照時間 ${r.str("times._hours") ?: "—"}時間　地点: ${r.dbl("center.lat")?.let { "%.4f, %.4f".format(it, r.dbl("center.lon")) } ?: "—"}", 10)
    }
}

/** 太陽光線シミュレーター（Web: SunSimulator.jsx） */
@Composable
private fun SunSimulator(m: ShootModel) {
    val p = ShootPrefs; val r = m.r
    val manual = p.sunMode == "manual"
    val az = r.dbl("sun.azimuth") ?: 180.0; val alt = r.dbl("sun.altitude") ?: 0.0
    val start = r.int("dayRange.start") ?: 330; val end = r.int("dayRange.end") ?: 1140; val minutes = r.int("minutes") ?: 720
    Panel(title = "☀ 太陽光線シミュレーター", action = { Tag(if (manual) "手動（仮想条件）" else "実際の太陽", if (manual) Brand.Warning else Brand.Success) }) {
        Text("${r.str("_minutes")}・方位 ${deg(r.dbl("sun.azimuth"))}・高度 ${r.dbl("sun.altitude")?.let { "%.1f°".format(it) } ?: "—"}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!manual, { p.sunMode = "actual" }, label = { Text("実際の太陽", fontSize = 11.sp) })
            FilterChip(manual, { p.sunMode = "manual"; if (!manual) { p.manualAzimuth = az; p.manualAltitude = alt.coerceIn(-5.0, 85.0) } }, label = { Text("手動シミュレーション", fontSize = 11.sp) })
        }
        if (manual) {
            Muted("手動で動かした光線は実際の太陽位置とは異なる仮想条件です。", 10)
            Muted("方位 ${az.roundToInt()}°", 11); Slider(az.toFloat(), { p.setSunAngle(it.toDouble()) }, valueRange = 0f..359f)
            Muted("高度 ${alt.roundToInt()}°", 11); Slider(alt.toFloat(), { p.setSunAltitude(it.toDouble()) }, valueRange = -5f..85f)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { p.playing = false; p.setMinutes(minutes - p.stepMin) }) { Icon(Icons.Outlined.ChevronLeft, "${p.stepMin}分戻す") }
            IconButton(onClick = { if (p.playing) p.playing = false else { if (p.mode == "now") p.setMinutes(minutes); p.playing = true } }) { Icon(if (p.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (p.playing) "一時停止" else "再生") }
            IconButton(onClick = { p.playing = false; p.setMinutes(minutes + p.stepMin) }) { Icon(Icons.Outlined.ChevronRight, "${p.stepMin}分進める") }
            Slider(minutes.coerceIn(start, end).toFloat(), { v -> p.setMinutes((v / p.stepMin).roundToInt() * p.stepMin) }, valueRange = start.toFloat()..end.toFloat(), modifier = Modifier.weight(1f))
            SelectField(listOf(5, 10, 15, 30).map { it to "${it}分" }, p.stepMin, { p.stepMin = it })
        }
        Muted("${r.str("dayRange._start")} 〜 ${r.str("dayRange._end")}（日の出〜日の入り基準）${if (p.playing) "　再生中（${p.stepMin}分ずつ進行）" else ""}", 10)
        if (!m.road.pending && r.list("analysis.segments").isEmpty()) Muted("道路形状を取得できていません", 10)
    }
}

/** 光線判定（Web: LightTable.jsx） */
@Composable
private fun LightTable(m: ShootModel) {
    val f = m.focus
    if (f == null) { Panel { Muted("経路の候補地点を作れませんでした。路線を選ぶか、道路形状の取得後にもう一度お試しください。", 12) }; return }
    val light = f.objAt("light")
    Panel(title = "💡 光線判定", subtitle = f.str("name")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Muted("撮影方向から見た判定", 11); Tag(light.str("_label").orEmpty(), hex(light.str("_hex"))) }
        Muted("${f.str("_side").orEmpty()}撮影・レンズ方位 ${deg(f.dbl("cameraHeading"))}（${f.str("_camera")}）", 10)
        light.list("surfaces").forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                Text(s.str("label").orEmpty(), fontSize = 12.sp, modifier = Modifier.width(52.dp)); Tag(s.str("state.label").orEmpty(), hex(s.str("_hex")))
                Muted(s.str("state.detail").orEmpty(), 10); Muted("法線 ${deg(s.dbl("normal"))}", 10)
            }
        }
        light.objAt("camera")?.let { c -> Muted("レンズが向く面：${c.str("state.label")}（${c.str("state.detail")}）", 11) }
        Muted("バスの進行方向: ${deg(f.dbl("heading"))}（${f.str("_heading")}）　撮影方向: ${deg(f.dbl("cameraHeading"))}（${f.str("_camera")}）", 10)
        Muted("撮影位置（推定）: ${f.dbl("camera.lat")?.let { "%.5f, %.5f".format(it, f.dbl("camera.lon")) } ?: "算出できません"}（道路形状からの推定値）", 10)
        Muted("建物・山・街路樹による影は、地形・建物データが無いため計算していません。「日陰の可能性」は低い太陽高度や厚い雲から判定したものです。", 10)
    }
}

/** 撮影環境スコア（Web: ScoreCard.jsx） */
@Composable
private fun ScoreCard(m: ShootModel, spot: JsonElement? = null) {
    val sr = (spot ?: m.focus).objAt("scoreResult")
    if (sr == null) { Panel { Muted("撮影環境スコアを算出する地点がありません。", 12) }; return }
    Panel(title = "撮影環境スコア", subtitle = m.r.str("purposeLabel")) {
        Row(verticalAlignment = Alignment.Bottom) { Text(sr.str("score") ?: "—", fontSize = 34.sp, fontWeight = FontWeight.Bold); Muted(" / 100", 12) }
        sr.list("items").forEach { item ->
            val max = item.dbl("max") ?: 0.0; val ratio = if (max > 0) ((item.dbl("points") ?: 0.0) / max).toFloat() else 0f
            Row { Text(item.str("label").orEmpty(), fontSize = 11.sp, modifier = Modifier.weight(1f)); Muted(if (item.bool("available") == true) "${item.str("points")} / ${item.str("max")}" else "データなし", 11) }
            LinearProgressIndicator(progress = { ratio.coerceIn(0.02f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp))
            Muted(item.str("note").orEmpty(), 10)
        }
        Muted("合計 ${sr.str("got")} / ${sr.str("max")} 点を100点満点に換算しています。${if (sr.bool("partial") == true) " 気象データ未取得の項目があるため、取得できた項目のみで算出しています。" else ""}", 10)
    }
}

private val SHAPE_FIELDS = listOf("straightMax" to "直進とみなす方位変化（°）", "curveMax" to "カーブとみなす下限（°）", "turnMin" to "右左折とみなす下限（°）", "uturnMin" to "Uターンとみなす下限（°）", "shortSpan" to "短い距離とみなす長さ（m）")

/** 道路形状の自動判定（Web: RouteShapePanel.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteShapePanel(m: ShootModel) {
    val occ = m.r.objAt("analysis.occurrences").orEmpty().mapValues { it.value.text.toIntOrNull() ?: 0 }.entries.sortedByDescending { it.value }
    val defaults = m.meta.objAt("defaultThresholds")
    Panel(title = "📐 道路形状の自動判定", subtitle = "${occ.sumOf { it.value }} か所${if (m.road.pending) "・解析中…" else ""}") {
        if (occ.isEmpty()) Muted("道路形状をまだ取得できていません（経路の取得後に自動で解析します）。", 11)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { occ.forEach { (k, n) -> Tag("${m.meta.str("shapeIcons.$k") ?: "?"} ${m.meta.str("shapeLabels.$k") ?: k} $n", Brand.MutedFg) } }
        SHAPE_FIELDS.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (k, label) ->
                    val v = ShootPrefs.thresholds[k] ?: defaults.dbl(k) ?: 0.0
                    OutlinedTextField(v.roundToInt().toString(), { t -> t.filter(Char::isDigit).toDoubleOrNull()?.let { ShootPrefs.thresholds = ShootPrefs.thresholds + (k to it.coerceIn(1.0, if (k == "shortSpan") 400.0 else 179.0)) } }, label = { Text(label, fontSize = 9.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
        }
        Muted("前後の進行方位の変化量と、その変化に要した距離から分類しています。曲率が連続する区間はカーブ、短い区間での大きな変化は右左折（交差点）とみなします。", 10)
    }
}

private fun pt(e: JsonElement?): Pair<Double, Double>? { val la = e.dbl("lat"); val lo = e.dbl("lon"); return if (la != null && lo != null) la to lo else null }

/** 経路・光線・道路形状・候補地点の地図（Web: ShootMapCard.jsx + ShootMap.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShootMapCard(m: ShootModel, title: String = "撮影地点の地図", note: String = "色は光線条件、数字は撮影環境スコア") {
    val context = LocalContext.current
    val r = m.r; val layers = ShootPrefs.layers; val focus = m.focus
    val groups = m.meta.items("lightGroups").associate { it.str("key").orEmpty() to hex(it.str("hex")) }
    val manual = ShootPrefs.sunMode == "manual"
    Panel(title = title, subtitle = note) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            if (manual) Tag("手動の仮想光線", Brand.Warning)
            Muted("太陽 方位 ${deg(r.dbl("sun.azimuth"))}・高度 ${r.dbl("sun.altitude")?.let { "%.1f°".format(it) } ?: "—"}", 11)
            if (manual) SmallButton("実際の太陽へ", { ShootPrefs.sunMode = "actual" })
            m.meta.items("lightGroups").forEach { g -> Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).background(hex(g.str("hex")), CircleShape)); Muted(" ${g.str("label")}", 10) } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { LAYER_DEFS.forEach { (k, label) -> FilterChip(layers[k] != false, { ShootPrefs.toggleLayer(context, k) }, label = { Text(label, fontSize = 10.sp) }) } }
        val center = pt(r.objAt("center"))
        if (center == null) { Muted("停留所の座標を取得しています…", 12); return@Panel }
        val lines = buildList {
            if (layers["path"] != false) r.list("analysis.segments").forEach { s ->
                val coords = s.list("coords").mapNotNull { c -> val a = c as? kotlinx.serialization.json.JsonArray; a?.getOrNull(0)?.text?.toDoubleOrNull()?.let { la -> a.getOrNull(1)?.text?.toDoubleOrNull()?.let { lo -> la to lo } } ?: pt(c) }
                if (coords.size > 1) add(MapLine(coords, if (layers["light"] != false) groups[s.str("group")] ?: Color(0xFF9CA3AF) else Color(0xFF475569), 7f, dashed = layers["light"] != false && s.str("group") == "unknown"))
            }
            val sunStart = pt(r.objAt("sunStart"))
            if (layers["sunRay"] != false && sunStart != null) add(MapLine(listOf(sunStart, center), Color(0xFFF59E0B), 6f, dashed = true))
            if (layers["camera"] != false && focus != null) {
                pt(focus)?.let { a -> pt(focus.objAt("_busEnd"))?.let { b -> add(MapLine(listOf(a, b), Color(0xFF2563EB), 8f)) } }
                pt(focus.objAt("camera"))?.let { a -> pt(focus.objAt("_cameraEnd"))?.let { b -> add(MapLine(listOf(a, b), hex(focus.str("light._hex")), 7f)) } }
            }
        }
        val pins = buildList {
            if (layers["path"] != false) m.stops.forEach { s -> add(MapPin("stop:${s.name}", s.lat, s.lon, s.name, "停留所", color = Color(0xFF0F172A), size = 7.dp)) }
            if (layers["shapes"] != false) r.list("analysis.shapes").forEachIndexed { i, s -> pt(s)?.let { (la, lo) -> add(MapPin("shape:$i", la, lo, "${s.str("label")}（方位変化 ${s.str("turn")}°）", color = Color(0xFF334155), size = 16.dp, label = s.str("icon"))) } }
            if (layers["vehicles"] != false) r.list("routeVehicles").forEach { v -> pt(v)?.let { (la, lo) -> add(MapPin("bus:${v.str("name")}", la, lo, "${v.str("name")}（運行中）", color = Color(0xFFF97316), size = 20.dp, label = "🚌")) } }
            if (layers["sun"] != false) pt(r.objAt("sunStart"))?.let { (la, lo) -> add(MapPin("sun", la, lo, "太陽 方位 ${deg(r.dbl("sun.azimuth"))}・高度 ${r.dbl("sun.altitude")?.roundToInt()}°${if (manual) "（手動）" else ""}", color = Color(0xFFF59E0B), size = 24.dp, label = "☀")) }
            if (layers["camera"] != false && focus != null) pt(focus.objAt("camera"))?.let { (la, lo) -> add(MapPin("camera", la, lo, "撮影位置（推定）・撮影方向", color = hex(focus.str("light._hex")), size = 12.dp, label = "📷")) }
            if (layers["spots"] != false) m.spots.forEach { s -> pt(s)?.let { (la, lo) -> val sel = s.str("id") == focus.str("id"); add(MapPin("spot:${s.str("id")}", la, lo, s.str("name").orEmpty(), "${s.str("light._label")}・スコア ${s.str("score") ?: "—"}", color = hex(s.str("light._hex")), size = if (sel) 26.dp else 21.dp, label = s.str("score") ?: "•")) } }
        }
        OsmMap(Modifier.fillMaxWidth().height(380.dp), pins = pins, lines = lines, center = pt(focus) ?: center, zoom = if (focus != null) 15.0 else 13.0, fitKey = "${ShootPrefs.routeName}|${focus.str("id")}",
            onPinClick = { pin -> if (pin.id.startsWith("spot:")) ShootPrefs.focusId = pin.id.removePrefix("spot:") })
        Muted("経路の色は、その地点の進行方向に対する光線条件です（${r.list("analysis.segments").size} 区間・${r.list("analysis.shapes").size} か所の右左折・カーブ）。撮影位置・撮影方向は道路形状から推定した値で、歩道・私有地・立入禁止の情報は含みません。太陽の方位は上のシミュレーターのスライダーで変えられます。", 10)
    }
}

/** おすすめ撮影時間（Web: ScheduleTable.jsx） */
@Composable
private fun ScheduleTable(m: ShootModel, compact: Boolean = false) {
    val f = m.focus
    if (f == null) { Panel { Muted("候補地点がありません。", 12) }; return }
    val rows = m.r.items("focusSchedule").let { if (compact) it.take(8) else it }
    Panel(title = "🕒 おすすめ撮影時間", subtitle = "${ShootPrefs.stepMin}分単位・${m.r.str("purposeLabel")}", action = { m.r.str("focusWindow.label")?.let { Tag("✨ 推奨 $it", Brand.Success) } }) {
        if (rows.isEmpty()) Muted("日の出・日の入りを計算できませんでした（この地点の座標を確認してください）。", 11)
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(row.str("time").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(46.dp))
                Box(Modifier.weight(1f)) { Tag(row.str("label").orEmpty(), hex(row.str("_hex"))) }
                Muted("${row.dbl("altitude")?.roundToInt()}°", 11)
                Text("${row.str("score")}${if (row.bool("partial") == true) "*" else ""}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = { ShootPrefs.date = row.str("_day") ?: ShootPrefs.date; ShootPrefs.time = row.str("time") ?: ShootPrefs.time; ShootPrefs.mode = "set" }) { Text("この時刻", fontSize = 10.sp) }
            }
        }
        Muted("スコアは光線・気象・視界・積雪・時刻から算出しています。* は気象データ未取得の項目があることを示し、取得できた項目だけで算出しています。", 10)
    }
}

/** 気象情報（Web: WeatherPanel.jsx） */
@Composable
private fun WeatherPanel(m: ShootModel) {
    val r = m.r; val w = r.objAt("pointWeather"); val f = m.focus
    fun n(k: String, unit: String, digits: Int = 0) = w.dbl(k)?.let { "%.${digits}f%s".format(it, unit) } ?: "取得できません"
    Panel(title = "☁ 気象情報", subtitle = "${f?.let { "%.4f, %.4f".format(it.dbl("lat"), it.dbl("lon")) } ?: "地点未選択"}　${f.objAt("day")?.let { "${it.str("_age")}・${it.str("source")}" } ?: "未取得"}",
        action = { SmallButton(if (m.weather.loading) "取得中…" else "更新", m.weather.reload, enabled = !m.weather.loading, icon = Icons.Outlined.Refresh) }) {
        if (w == null) {
            Muted("この地点・日付の気象データを取得できていません。取得できない場合は推測で補完しないため、天候に関する評価は行っていません。", 11)
            if (m.weather.error.isNotBlank()) Text(m.weather.error, fontSize = 11.sp, color = Brand.Warning)
        } else {
            Row2("天気", w.str("_label").orEmpty()); Row2("気温", n("temp", "℃", 1)); Row2("湿度", n("hum", "%")); Row2("降水確率", n("pop", "%")); Row2("降水量", n("prec", "mm/h", 1)); Row2("降雪量", n("snow", "cm/h", 1))
            Row2("積雪深", n("snowDepth", "cm")); Row2("風速", n("wind", "m/s", 1), w.str("_windDir").orEmpty()); Row2("雲量", n("cloud", "%")); Row2("視程", w.dbl("vis")?.let { "%.1fkm".format(it / 1000) } ?: "取得できません")
            Row2("日照時間（可照）", r.str("times._hours")?.takeIf { it != "—" }?.let { "${it}時間" } ?: "計算できません", "雲による実日照は含みません")
            Text("天候別の撮影環境", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            r.list("advice").forEach { a -> Text("${a.str("title")}：${a.str("body")}", fontSize = 11.sp) }
        }
        Muted(r.str("forecastKind.label").orEmpty(), 10)
    }
}

/** 経路・移動時間（Web: SpotRoute.jsx）。Google マップを開く。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpotRoute(m: ShootModel, spot: JsonElement) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf("transit") }
    var origin by remember(spot.str("id")) { mutableStateOf("current") }
    var here by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var hereState by remember { mutableStateOf("") }
    val locate = rememberLocationRequest { loc -> if (loc == null) hereState = "現在地を取得できませんでした" else { here = loc.lat to loc.lon; hereState = "" } }
    val near = m.r.items("focusNearStops")
    val dest = pt(spot) ?: return
    val from = if (origin == "current") here else near.firstOrNull { it.str("name") == origin }?.let { pt(it) }
    val meters = from?.let { app.narabuspulse.core.offline.OfflineStore.distanceKm(it.first, it.second, dest.first, dest.second) * 1000 }
    val minutes = meters?.let { when (mode) { "walking" -> (it / 80).roundToInt(); "bicycling" -> (it / 250).roundToInt(); else -> null } }
    Text("経路・移動時間", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("transit" to "公共交通", "walking" to "徒歩", "driving" to "車", "bicycling" to "自転車").forEach { (k, l) -> FilterChip(mode == k, { mode = k }, label = { Text(l, fontSize = 10.sp) }) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        SelectField(listOf("current" to "現在地（GPS）") + near.map { it.str("name").orEmpty() to "${it.str("name")}（${it.dbl("distance")?.roundToInt()}m）" }, origin, { origin = it; if (it == "current" && here == null) { hereState = "現在地を取得しています…"; locate() } }, label = "起点")
        if (origin == "current" && here == null) SmallButton("現在地を取得", { hereState = "現在地を取得しています…"; locate() }, icon = Icons.Outlined.MyLocation)
        SmallButton("Googleマップで経路 ↗", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(gmaps(from, dest, mode)))) } }, primary = true)
    }
    Muted(when {
        origin == "current" && hereState.isNotBlank() -> "$hereState${if (hereState.endsWith("…")) "" else "。Googleマップは端末の位置情報を起点に開きます。"}"
        origin == "current" && from == null -> "Googleマップが端末の現在地を起点に開きます。"
        meters == null -> "この地点の座標が無いため、距離を計算できません。"
        minutes == null -> "直線距離 ${meters.roundToInt()}m・所要時間はGoogleマップで確認してください。"
        else -> "直線距離 ${meters.roundToInt()}m・${if (mode == "walking") "徒歩" else "自転車"} 約${minutes}分（直線距離からの目安）"
    }, 10)
    if (near.isEmpty()) Muted("この経路の停留所座標が未取得のため、起点は現在地のみ選べます。", 10)
}

private fun favoriteOf(s: JsonElement, extraDate: String): JsonObject = jsonOf("id" to s.str("id"), "name" to s.str("name"), "lat" to s.dbl("lat"), "lon" to s.dbl("lon"), "camera" to s.objAt("camera"), "heading" to s.dbl("heading"),
    "cameraHeading" to s.dbl("cameraHeading"), "route" to s.str("route"), "shapeKey" to s.str("shapeKey"), "shapeLabel" to s.str("shapeLabel"), "roleLabel" to s.str("role.label").orEmpty(), "lightKey" to s.str("light._key"),
    "lightLabel" to s.str("light._label"), "score" to s.dbl("score"), "bestTime" to s.str("window.label").orEmpty(), "date" to extraDate)

/** 地点の詳細（Web: SpotDetail.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpotDetail(m: ShootModel) {
    val context = LocalContext.current
    val t = m.focus ?: return
    val w = t.objAt("weather")
    val saved = ShootPrefs.favorites.any { it.str("id") == t.str("id") }
    Panel(title = t.str("name"), action = { SmallButton(if (saved) "★ 保存済み" else "☆ お気に入り", { ShootPrefs.addFavorite(context, favoriteOf(t, ShootPrefs.date)) }, primary = saved) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Tag(t.str("light._label").orEmpty(), hex(t.str("light._hex"))); Tag("スコア ${t.str("score") ?: "—"}", Brand.MutedFg) }
        KeyValue("緯度・経度", "%.5f, %.5f".format(t.dbl("lat"), t.dbl("lon")))
        KeyValue("おすすめ撮影方向", "${deg(t.dbl("cameraHeading"))}（${t.str("_camera")}）"); KeyValue("バスの進行方向", "${deg(t.dbl("heading"))}（${t.str("_heading")}）")
        KeyValue("道路形状", "${t.str("shapeLabel") ?: "—"}${t.dbl("shapeTurn")?.let { "（${if (it > 0) "+" else ""}${it.roundToInt()}°）" } ?: ""}"); KeyValue("撮影パターン", t.str("role.label") ?: "—")
        KeyValue("太陽（方位・高度）", "${deg(m.r.dbl("sun.azimuth"))} / ${m.r.dbl("sun.altitude")?.let { "%.1f°".format(it) } ?: "—"}"); KeyValue("おすすめ撮影時間", t.str("window.label") ?: "算出できません")
        KeyValue("撮影対象路線", t.str("route")?.let { "系統 $it" } ?: "—"); KeyValue("近い停留所", "${t.str("nearestStop") ?: "—"}（${t.dbl("stopDistance")?.let { "${it.roundToInt()}m" } ?: "—"}）")
        KeyValue("天気", orNone(w.str("_label"))); KeyValue("気温", orNone(w.dbl("temp")?.let { "%.1f℃".format(it) })); KeyValue("降水確率", orNone(w.dbl("pop")?.let { "${it.roundToInt()}%" }))
        KeyValue("降雪量", orNone(w.dbl("snow")?.let { "%.1fcm/h".format(it) })); KeyValue("積雪深", orNone(w.dbl("snowDepth")?.let { "${it.roundToInt()}cm" })); KeyValue("雲量", orNone(w.dbl("cloud")?.let { "${it.roundToInt()}%" }))
        KeyValue("風速", orNone(w.dbl("wind")?.let { "%.1fm/s".format(it) })); KeyValue("視程", orNone(w.dbl("vis")?.let { "%.1fkm".format(it / 1000) })); KeyValue("撮影環境スコア", "${t.str("score") ?: "—"} / 100")
        SpotRoute(m, t)
        Text("撮影パターン別の評価", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { t.list("roles").forEach { role -> Tag("${role.str("label")}・${role.str("_lightLabel")}・${role.str("score")}", if (role.str("key") == t.str("role.key")) Brand.Orange else Brand.MutedFg) } }
        Text("時間帯別の評価（上位3件）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        t.list("scheduleTop").forEach { row -> Muted("${row.str("time")}　${row.str("label")}　スコア ${row.str("score")}", 11) }
        if (t.list("scheduleTop").isEmpty()) Muted("時間帯別の評価を算出できませんでした。", 11)
        Muted("${t.str("shapeNote")?.takeIf { it.isNotBlank() }?.let { "$it " } ?: ""}道路形状は経路の座標から判定しており、交差点かカーブかの区別は道路ネットワーク未参照のため推定です。歩道・私有地・交通量の情報は含みません。", 10)
        Text("この系統の車両の通過予測", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        val passing = m.r.items("focusPassing")
        if (passing.isEmpty()) Muted("この系統を走行中の車両が監視データに無いため、通過時刻は予測できません。", 11)
        passing.take(4).forEach { item -> Muted("${item.str("vehicle")}　${item.str("label")}${if (item.str("status") == "coming") "　${item.str("_at")}ごろ" else ""}", 11) }
    }
}

// ------------------------------------------------------------------ 画面

/** 光線シミュレーション（Web: shoot.light.jsx） */
@Composable
fun ShootLightScreen() = ShootScaffold("/shoot/light") { m ->
    item { SunSimulator(m) }; item { SunPanel(m) }; item { LightTable(m) }; item { ScoreCard(m) }; item { RouteShapePanel(m) }
    item { ShootMapCard(m, "経路・太陽光線・道路形状の同時表示", "経路の色は光線条件。太陽の方位はスライダーで変えられます") }
    item { ScheduleTable(m, compact = true) }
}

/** 撮影環境予測（Web: shoot.environment.jsx） */
@Composable
fun ShootEnvironmentScreen() = ShootScaffold("/shoot/environment") { m ->
    item {
        Panel {
            SelectField(m.spots.map { it.str("id").orEmpty() to "${it.str("name")}（スコア ${it.str("score") ?: "—"}）" }.ifEmpty { listOf("" to "候補地点なし") }, m.focus.str("id").orEmpty(), { ShootPrefs.focusId = it }, label = "撮影地点")
            m.focus?.let { Tag(it.str("light._label").orEmpty(), hex(it.str("light._hex"))) }
            Muted("気象: ${m.focus.objAt("day")?.let { "${it.str("source")}・1時間ごとの予報" } ?: "未取得"}", 10)
        }
    }
    item { WeatherPanel(m) }; item { ScoreCard(m) }; item { ShootMapCard(m, "撮影環境", "地点ごとの予報を地点の座標で取得しています") }
    item {
        Panel(title = "この画面の前提") {
            Muted("気象は予報API（Open-Meteo）の格子予報を地点座標で取得した値で、現地の実測ではありません。予報格子（約1〜11km）が同じ地点は同じ値になります。", 11)
            Muted("スコアは光線条件（35点）・天候と雲量（20点）・降水降雪（15点）・視界（10点）・積雪状況（10点）・撮影時刻（10点）の合計を100点満点に換算したものです。", 11)
        }
    }
}

/** 撮影スポット検索（Web: shoot.spots.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShootSpotsScreen() {
    var filters by remember { mutableStateOf(mapOf<String, String>()) }
    var condition by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("score") }
    val view = jsonOf("filters" to JsonObject(filters.mapValues { (k, v) -> if (k == "minScore") app.narabuspulse.core.toJson(v.toIntOrNull() ?: 0) else app.narabuspulse.core.toJson(v) }), "condition" to applied.ifBlank { null })
    ShootScaffold("/shoot/spots", view) { m ->
        item {
            Panel(title = "✨ 条件から最適な地点を探す", subtitle = "光線・道路形状・気象を解析して提案します") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    SelectField(listOf("" to "条件を選んでください") + m.meta.items("conditions").map { it.str("key").orEmpty() to it.str("label").orEmpty() }, condition, { condition = it })
                    SmallButton("最適化する", { applied = condition }, enabled = condition.isNotBlank(), primary = true)
                    if (applied.isNotBlank()) SmallButton("クリア", { applied = "" })
                }
                if (applied.isNotBlank()) {
                    val results = m.r.items("suggestions")
                    if (results.isEmpty()) Muted("この条件に合う地点が、いまの日時・系統の候補の中に見つかりませんでした。時刻を変えるか、系統を変えてお試しください。", 11)
                    results.forEach { s ->
                        Column(Modifier.padding(vertical = 3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { TextButton(onClick = { ShootPrefs.focusId = s.str("id").orEmpty() }) { Text(s.str("name").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold) }; Muted(s.str("roleLabel").orEmpty(), 10); Muted("スコア ${s.str("score")}", 10) }
                            s.list("reasons").forEach { Muted("・${it.text}", 10) }
                        }
                    }
                }
            }
        }
        item {
            val f = { k: String -> filters[k].orEmpty() }
            fun set(k: String, v: String) { filters = filters + (k to v) }
            val yn = listOf("" to "指定なし", "yes" to "あり", "no" to "なし")
            Panel(title = "絞り込み", subtitle = "${m.r.list("filteredIds").size} / ${m.spots.size} 地点", action = { TextButton(onClick = { filters = emptyMap() }) { Text("条件をクリア", fontSize = 11.sp) } }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    SelectField(listOf("" to "指定なし") + m.meta.items("lightOrder").filter { it.str("key") != "night" }.map { it.str("key").orEmpty() to it.str("label").orEmpty() }, f("light"), { set("light", it) }, label = "光線")
                    SelectField(listOf("" to "指定なし", "clear" to "晴天", "cloudy" to "曇天", "rain" to "雨", "snow" to "雪"), f("weather"), { set("weather", it) }, label = "天気")
                    SelectField(yn, f("snow"), { set("snow", it) }, label = "降雪"); SelectField(yn, f("snowDepth"), { set("snowDepth", it) }, label = "積雪")
                    SelectField(listOf("" to "指定なし", "front" to "前面が明るい", "side" to "側面が明るい", "rear" to "後面が明るい"), f("target"), { set("target", it) }, label = "撮影対象")
                    Muted("時間帯", 11); TimeField(f("from"), { set("from", it) }); Muted("〜", 11); TimeField(f("to"), { set("to", it) })
                    SelectField(m.meta.items("sortOptions").map { it.str("key").orEmpty() to it.str("label").orEmpty() }.ifEmpty { listOf("score" to "スコア順") }, f("sortBy").ifBlank { "score" }, { set("sortBy", it) }, label = "並べ替え")
                    SelectField(listOf("0" to "指定なし", "50" to "50以上", "60" to "60以上", "70" to "70以上", "80" to "80以上"), f("minScore").ifBlank { "0" }, { set("minScore", it) }, label = "スコア")
                }
                Muted("時間帯は、その時間内に順光・準順光になる時刻がある地点を残します。降雪・積雪・天気は取得できた気象データのみで判定しています。", 10)
            }
        }
        item {
            val ids = m.r.list("filteredIds").map { it.text }
            val byId = m.spots.associateBy { it.str("id") }
            val list = ids.mapNotNull { byId[it] }.let { l ->
                when (sort) { "light" -> l.sortedByDescending { it.int("light._rank") ?: 0 }; "window" -> l.sortedBy { it.str("window.from") ?: "99" }; "passing" -> l.sortedBy { it.int("_comingMin") ?: 9999 }; else -> l }
            }
            Panel(title = "📍 撮影スポット候補", subtitle = "${list.size} 地点", action = { SelectField(listOf("score" to "絞り込みの並び順", "light" to "光線の良さ順", "window" to "推奨時間順", "passing" to "通過予測順"), sort, { sort = it }) }) {
                if (list.isEmpty()) Muted("条件に合う地点がありません。絞り込みを緩めるか、路線・日時を変えてください。", 11)
                list.forEach { s -> SpotRow(s, s.str("id") == m.focus.str("id")) }
                Muted("候補は走行経路（道路形状）を一定間隔でサンプリングし、道路の向きと太陽位置から光線を判定したものです。道路幅員・歩道の有無・立入可否は含みません。", 10)
            }
        }
        item { ShootMapCard(m, "撮影スポット候補", "数字はスコア。ピンを押すと詳細が出ます") }
        item { SpotDetail(m) }
    }
}

@Composable
private fun SpotRow(s: JsonElement, active: Boolean) {
    val context = LocalContext.current
    val saved = ShootPrefs.favorites.any { it.str("id") == s.str("id") }
    val w = s.objAt("weather")
    Row(Modifier.fillMaxWidth().background(if (active) Brand.Orange.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(8.dp)).clickable { ShootPrefs.focusId = s.str("id").orEmpty() }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(hex(s.str("light._hex")), CircleShape), contentAlignment = Alignment.Center) { Text(s.str("score") ?: "—", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(s.str("name").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Muted("${s.str("light._label")}　推奨 ${s.str("window.label") ?: "—"}　${w.str("_label") ?: "気象未取得"}${w.dbl("temp")?.let { "　%.1f℃".format(it) } ?: ""}", 10)
            Muted("進行方位 ${deg(s.dbl("heading"))}（${s.str("_heading")}）・撮影方位 ${deg(s.dbl("cameraHeading"))}", 10)
        }
        IconButton(onClick = { ShootPrefs.addFavorite(context, favoriteOf(s, ShootPrefs.date)) }) { Icon(if (saved) Icons.Outlined.Star else Icons.Outlined.StarOutline, "お気に入りに保存", tint = if (saved) Brand.Warning else Brand.MutedFg) }
    }
}

private fun toMin(v: String): Int? = Regex("^(\\d{1,2}):(\\d{2})$").find(v)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

/** 撮影チャンス（Web: shoot.chances.jsx + ChanceCard.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShootChancesScreen() {
    val nav = LocalNav.current
    var minScore by remember { mutableStateOf(60) }
    var fromTime by remember { mutableStateOf("") }
    var toTime by remember { mutableStateOf("") }
    ShootScaffold("/shoot/chances", jsonOf("chance" to jsonOf("minScore" to minScore, "fromMin" to toMin(fromTime), "toMin" to toMin(toTime)))) { m ->
        val chances = m.r.items("chances")
        val byId = m.spots.associateBy { it.str("id") }
        item {
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Muted("最低スコア", 11); listOf(50, 60, 70, 80).forEach { v -> FilterChip(minScore == v, { minScore = v }, label = { Text("$v", fontSize = 11.sp) }) }
                    Muted("開始時刻", 11); TimeField(fromTime, { fromTime = it }); Muted("終了時刻", 11); TimeField(toTime, { toTime = it })
                    if (fromTime.isNotBlank() || toTime.isNotBlank()) TextButton(onClick = { fromTime = ""; toTime = "" }) { Text("時刻をクリア", fontSize = 11.sp) }
                }
                Muted("対象日 ${ShootPrefs.date}（${m.r.str("forecastKind.label")}）", 11)
                Muted("系統 ${ShootPrefs.routeName.ifBlank { "未選択" }} / スポット ${m.spots.size}件 / チャンス ${chances.size}件（スコア80以上 ${chances.count { (it.int("peakScore") ?: 0) >= 80 }}件）", 11)
            }
        }
        if (chances.isEmpty()) item { Panel { Muted("この条件ではチャンスが見つかりませんでした。スコアのしきい値を下げるか、時間帯の指定を広げてください。", 12); Muted("スポットが0件の場合は、系統を選んで走行経路（道路形状）と停留所座標の取得完了後に再計算されます。", 11) } }
        chances.forEach { c ->
            item(key = "chance-${c.str("id")}") {
                val context = LocalContext.current
                val spot = byId[c.str("spotId")]
                val saved = ShootPrefs.favorites.any { it.str("id") == c.str("spotId") }
                val peak = c.int("peakScore") ?: 0
                val w = c.objAt("weather")
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tag(c.str("route") ?: "—", Brand.MutedFg)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) { Text(c.str("name").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold); Muted("${c.str("roleLabel")?.ifBlank { null } ?: "撮影パターン未設定"}${c.str("shapeLabel")?.takeIf { it.isNotBlank() }?.let { " / $it" } ?: ""}", 10) }
                        Column(horizontalAlignment = Alignment.End) { Text("$peak", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (peak >= 80) Brand.Success else if (peak >= 65) Brand.Warning else Brand.MutedFg); Muted("ピーク / 平均 ${c.str("averageScore")}", 9) }
                    }
                    Text("🕒 ${c.str("from")}〜${c.str("to")}　☀ ${c.str("lightLabel")}${c.str("sun.altitude")?.let { "（太陽高度 $it°）" } ?: ""}${c.dbl("cameraHeading")?.let { "　📷 撮影方位 ${it.roundToInt()}°" } ?: ""}", fontSize = 11.sp)
                    Muted(if (w == null) "気象データなし" else listOfNotNull(w.str("label"), w.dbl("cloud")?.let { "雲量 ${it.roundToInt()}%" }, w.dbl("pop")?.let { "降水確率 ${it.roundToInt()}%" }, w.dbl("snow")?.takeIf { it > 0 }?.let { "降雪 %.1fcm/h".format(it) }, w.dbl("snowDepth")?.takeIf { it > 0 }?.let { "積雪 ${it.roundToInt()}cm" }).joinToString("・"), 11)
                    c.list("reasons").forEach { Muted("・${it.text}", 10) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton("地図と詳細を見る", { ShootPrefs.focusId = c.str("spotId").orEmpty(); nav.go("/shoot/spots") })
                        SmallButton(if (saved) "お気に入り登録済み" else "お気に入りに追加", { spot?.let { ShootPrefs.addFavorite(context, favoriteOf(it, ShootPrefs.date)) } }, enabled = spot != null && !saved)
                    }
                    Muted("${c.str("sampleCount")}点のサンプル / 緯度 ${c.dbl("lat")?.let { "%.4f".format(it) }} 経度 ${c.dbl("lon")?.let { "%.4f".format(it) }}", 9)
                }
            }
        }
        item {
            Panel(title = "📷 いま系統に入っている車両（実測）") {
                val vs = m.r.items("routeVehicles")
                if (vs.isEmpty()) Muted("現在この系統で観測できている車両はありません。", 11)
                vs.forEach { v -> Row { Text(v.str("name").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Muted("${v.str("stop")?.ifBlank { null } ?: "停留所不明"}　取得値", 11) } }
                Muted("通過予定時刻は提供されていないため表示しません。当日の運行情報で確認してください。", 10)
            }
        }
        item {
            val history = rememberLoad(ShootPrefs.routeName, ShootPrefs.date) { if (ShootPrefs.routeName.isBlank()) null else Api.rpc("shoot/chances-fns#getRouteRunHistory", jsonOf("route" to ShootPrefs.routeName, "date" to ShootPrefs.date)) }
            Panel(title = "🕒 同じ曜日の運行実績") {
                if (history.loading) Muted("集計しています…", 11)
                history.error?.let { Text("⚠ $it", fontSize = 11.sp, color = Brand.Warning) }
                history.data?.let { h ->
                    val vs = h.items("vehicles")
                    if (vs.isEmpty()) Muted(h.str("note").orEmpty(), 11)
                    vs.take(8).forEach { row -> Row { Text(row.str("vehicle").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Muted("${row.str("days")}日 / ${row.str("runs")}回${row.str("startMedian")?.takeIf { it.isNotBlank() }?.let { " / 初回 $it" } ?: ""}　過去実績", 11) } }
                    Muted("対象期間 ${h.str("range.from")} 〜 ${h.str("range.to")}（${h.str("recordCount")}件）。${h.str("note").orEmpty()}", 10)
                }
            }
        }
        item { Panel(title = "この画面の根拠") { m.meta.list("chanceNotes").forEach { Muted("・${it.text}", 10) }; Muted("・天候・降水・積雪は地点の予報で、取得できない時間帯は配点から除外しています。", 10) } }
    }
}

/** おすすめ撮影時間（Web: shoot.times.jsx） */
@Composable
fun ShootTimesScreen() = ShootScaffold("/shoot/times", showStep = true) { m ->
    item { SunPanel(m) }
    item {
        Panel(title = "推奨時間帯") {
            val w = m.focus.objAt("window")
            Text(if (w != null) "${w.str("label")} が最も安定して順光になります（平均スコア ${w.str("avgScore")}）。" else "この地点では順光・準順光が続く時間帯を特定できませんでした。太陽の向きと道路の向きが合わない地点です。", fontSize = 12.sp)
            Muted("地点: ${m.focus.str("name") ?: "—"}／照射方向 ${deg(m.focus.dbl("cameraHeading"))}", 11)
            SelectField(m.spots.map { it.str("id").orEmpty() to it.str("name").orEmpty() }.ifEmpty { listOf("" to "候補地点なし") }, m.focus.str("id").orEmpty(), { ShootPrefs.focusId = it }, label = "地点")
        }
    }
    item { ScheduleTable(m) }
    item { ShootMapCard(m, "地点の確認", "推奨時間帯の太陽位置と撮影方向") }
}

/** お気に入り撮影地点（Web: shoot.favorites.jsx + FavoritesPanel.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShootFavoritesScreen() = ShootScaffold("/shoot/favorites") { m ->
    item {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("") }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val id = target
            if (uri == null || id.isBlank()) return@rememberLauncherForActivityResult
            busy = id; error = ""
            scope.launch {
                try {
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: throw IllegalStateException("写真を読み込めませんでした")
                    ShootPrefs.updateFavorite(context, id, "photoUri" to Api.uploadPrivateFile("spot.${mime.substringAfter('/')}", mime, bytes), "photoName" to (uri.lastPathSegment ?: "photo"))
                } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "写真を保存できませんでした" } } finally { busy = "" }
            }
        }
        val favorites = ShootPrefs.favorites
        Panel(title = "★ お気に入り撮影地点", subtitle = "${favorites.size} 件・この端末に保存されます") {
            if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
            if (favorites.isEmpty()) Muted("まだ保存した地点がありません。撮影スポット検索で地点を選び、「お気に入り」から保存できます。", 12)
            favorites.forEach { fav ->
                val id = fav.str("id").orEmpty()
                var memo by remember(id) { mutableStateOf(fav.str("memo").orEmpty()) }
                Column(Modifier.padding(vertical = 6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(fav.str("name").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Tag(fav.str("lightLabel")?.ifBlank { null } ?: m.meta.str("lightStates.${fav.str("lightKey")}.label").orEmpty(), hex(m.meta.str("lightStates.${fav.str("lightKey")}.hex")))
                        fav.str("route")?.takeIf { it.isNotBlank() }?.let { Tag("系統 $it", Brand.MutedFg) }; Muted("スコア ${fav.str("score") ?: "—"}", 10)
                    }
                    Muted("${fav.dbl("lat")?.let { "%.5f, %.5f".format(it, fav.dbl("lon")) }}　撮影方向 ${deg(fav.dbl("cameraHeading"))}　おすすめ ${fav.str("bestTime")?.ifBlank { null } ?: fav.str("date") ?: "—"}", 10)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(memo, { memo = it }, placeholder = { Text("メモ（撮影条件・注意点など）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                        SmallButton("メモを保存", { ShootPrefs.updateFavorite(context, id, "memo" to memo) }, modifier = Modifier.padding(start = 6.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        SmallButton(if (busy == id) "保存中…" else if (fav.str("photoUri").isNullOrBlank()) "写真を追加" else "写真を変更", { target = id; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = busy.isBlank())
                        fav.str("photoUri")?.takeIf { it.isNotBlank() }?.let { uri -> SmallButton("写真を見る", { scope.launch { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Api.signedUrl(uri)))) } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "写真を開けませんでした" } } } }) }
                        if (m.spots.any { it.str("id") == id }) SmallButton("地図で表示", { ShootPrefs.focusId = id })
                        IconButton(onClick = { ShootPrefs.removeFavorite(context, id) }) { Icon(Icons.Outlined.Delete, "削除") }
                    }
                }
            }
            Muted("写真は非公開ストレージに保存され、表示のたびに有効期限付きのURLを発行します。地点の保存内容はこの端末に限られます。", 10)
        }
    }
    item { ShootMapCard(m) }
    item {
        Panel(title = "保存できる内容") {
            listOf("地点名・緯度経度", "撮影方向・光線条件", "おすすめ日時（推奨時間帯）", "メモ", "撮影写真（非公開ストレージ）").forEach { Muted("・$it", 11) }
            Muted("地点名・撮影方向・おすすめ日時は保存時点の計算結果です。太陽位置や気象は日付で変わるため、撮影前に再度シミュレーションしてください。", 10)
        }
    }
}

