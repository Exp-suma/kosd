package app.narabuspulse.feature.bus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Logic
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.str
import app.narabuspulse.core.at
import app.narabuspulse.core.text
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.MapLine
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.Note
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private val INFO = Color(0xFF2F6FB0)

@Composable
private fun StateBadge(state: VehicleState?) { if (state != null) Tag(state.label, state.color) else Tag("—", Brand.MutedFg) }

/** 基本情報タブ（Web: VehicleInfoTab.jsx） */
@Composable
fun VehicleInfoTab(vehicle: Vehicle?, state: VehicleState?, now: Long, rows: List<Pair<String, String>>?, note: String?) {
    if (vehicle == null) return
    val op = parseOperation(vehicle.operation)
    val list = rows ?: listOf(
        "車両番号" to vehicle.name,
        "監視状態" to (state?.label ?: "—"),
        "監視" to vehicle.onOff.ifBlank { "—" },
        "最終確認" to (vehicle.lastCheck?.let { "${BusFormat.dateTime(it)}（${BusFormat.elapsed(it, now)}）" } ?: "記録なし"),
        "最終運行" to (vehicle.lastRun?.let { BusFormat.dateTime(it) } ?: "—"),
        "行先" to op.destination.ifBlank { "—" },
        "次発時刻" to vehicle.nextTime.ifBlank { "—" },
        "ロケーションURL" to vehicle.url.ifBlank { "—" },
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(title = "基本情報", action = { Box(Modifier.padding(end = 8.dp)) { StateBadge(state) } }) {
            list.forEach { (k, v) -> KeyValue(k, v) }
        }
        if (note != "") Note(note ?: "車種・型式・所属営業所は監視シート（メインシート）に列が無いため表示できません。シートに列が追加されれば「運用履歴」と合わせて表示できます。")
    }
}

/** 現在の運行タブ（Web: CurrentRunTab.jsx） */
@Composable
fun CurrentRunTab(vehicle: Vehicle?, state: VehicleState?, timeline: List<JsonElement>, onOpenStops: () -> Unit, onShowRoute: () -> Unit) {
    if (vehicle == null) return
    val op = parseOperation(vehicle.operation)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(title = "現在の運行", action = { Box(Modifier.padding(end = 8.dp)) { StateBadge(state) } }) {
            KeyValue("系統", op.route.ifBlank { "—" })
            KeyValue("行先", op.destination.ifBlank { "—" })
            KeyValue("始発", op.origin.ifBlank { "—" })
            KeyValue("経由地", vehicle.via.ifBlank { "—" })
            KeyValue("現在の停留所", vehicle.stop.ifBlank { "—" })
            KeyValue("運用", vehicle.operation.ifBlank { "運行なし" })
            KeyValue("確認時刻", vehicle.lastCheck?.let { BusFormat.dateTime(it) } ?: "記録なし")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                SmallButton("停留所一覧（乗り場・乗車口）", onOpenStops, enabled = vehicle.stop.isNotBlank(), icon = Icons.AutoMirrored.Outlined.FormatListBulleted)
            }
            SmallButton("この系統の経路を地図に表示", onShowRoute, icon = Icons.Outlined.Place, modifier = Modifier.padding(top = 4.dp))
        }
        Panel(title = "通過状況", subtitle = "灰色＝通過済み・オレンジ＝現在・黄色＝このあと（実際の観測と運用表の停留所順から作成）。時刻は予定、+n分は本日の遅れです。") {
            if (timeline.isEmpty()) Text("停留所の情報が取得できていないため、通過状況を表示できません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else timeline.forEach { stop ->
                val st = stop.str("state")
                val color = when (st) { "current" -> Brand.Orange; "passed" -> Brand.MutedFg; else -> Brand.Warning }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Box(Modifier.size(if (st == "current") 12.dp else 8.dp).background(color, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(stop.str("name").orEmpty(), fontSize = 12.sp, fontWeight = if (st == "current") FontWeight.Bold else FontWeight.Normal,
                        color = if (st == "passed") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (st == "current" && op.route.isNotBlank()) { Spacer(Modifier.width(6.dp)); Tag(op.route, Brand.Orange) }
                }
            }
        }
    }
}

/** 走行経路タブ（Web: RouteTab.jsx） */
@Composable
fun RouteTab(options: List<Pair<String, Int>>, selected: List<String>, onToggle: (String) -> Unit, road: RoadPathsState) {
    val distance = produceState("—", road.totalDistance) { value = Logic.call("bus/geo", "formatDistance", road.totalDistance).text.ifBlank { "—" } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(title = "表示する系統", action = { Text("${selected.size} 系統選択中", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp)) }) {
            if (options.isEmpty()) Text("系統別の停留所一覧を作れる運用情報がありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            options.forEach { (route, count) ->
                val on = route in selected
                Row(
                    Modifier.fillMaxWidth().background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(6.dp)).clickable { onToggle(route) }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(on, { onToggle(route) })
                    Text("系統 $route", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("$count 停留所", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("最大4系統まで同時表示できます", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel(title = "区間と距離", subtitle = "道路形状の区間 ${road.legs.size} 本・合計 ${distance.value}${if (road.pending) "（OpenStreetMap の道路データを取得中）" else ""}") {
            if (road.error.isNotBlank()) Note("${road.error}（直線での代用表示は行いません）", tone = Brand.Warning)
            if (road.missing > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${road.missing} 区間の道路形状を取得できませんでした。", fontSize = 11.sp, modifier = Modifier.weight(1f))
                SmallButton("再取得", road.reload, icon = Icons.Outlined.Refresh)
            }
            if (road.legs.isEmpty()) Text(if (road.lines.isNotEmpty()) "区間距離を計算できませんでした。" else "系統を選ぶと、停留所間の道路距離を表示します。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            road.legs.forEachIndexed { i, leg ->
                if (i > 0) RowDivider()
                LegRow(leg)
            }
        }
    }
}

@Composable
private fun LegRow(leg: RoadLeg) {
    val d = produceState("", leg.distance) { value = Logic.call("bus/geo", "formatDistance", leg.distance).text }
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Tag(leg.route.ifBlank { "—" }, INFO)
        Spacer(Modifier.width(6.dp))
        Text("${leg.from} → ${leg.to}", fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(d.value, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 走行履歴（Web: VehicleTrackPanel.jsx） */
@Composable
fun VehicleTrackPanel(vehicle: Vehicle, track: JsonElement) {
    val points = track.list("points")
    val heading = track.dbl("heading")
    val compass = produceState("", heading) { value = heading?.let { Logic.call("bus/track", "compassLabel", it).text }.orEmpty() }
    Panel(title = "走行履歴", action = { Text("${points.size} 地点", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp)) }) {
        if (heading != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ArrowUpward, null, tint = Brand.Orange, modifier = Modifier.size(18.dp).rotate(heading.toFloat()))
            Spacer(Modifier.width(6.dp))
            Text("${compass.value}方向へ移動中", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            track.str("previousStop")?.takeIf { it.isNotBlank() }?.let { Text("　$it から", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else Text(if (vehicle.stop.isNotBlank()) "2分ごとの観測で移動を確認すると、進行方向と走行経路を表示します。" else "この車両は停留所の情報がありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        points.reversed().forEachIndexed { i, p ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (i == 0) Brand.Orange else INFO.copy(alpha = 0.6f), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(BusFormat.time(p.long("at")), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
                Text(p.str("stop").orEmpty(), fontSize = 12.sp)
                if (i == 0) { Spacer(Modifier.width(6.dp)); Tag("現在") }
            }
        }
    }
}

private val FACING = listOf(45.0 to "車両の前面", 120.0 to "車両の側面", 150.0 to "前面の斜め後ろ", 180.0 to "車両の後面")
private fun facing(fromFront: Double) = (FACING.firstOrNull { fromFront <= it.first } ?: FACING.last()).second

private fun toneColor(tone: String?) = when (tone) {
    "success" -> Brand.Success; "info" -> INFO; "warning" -> Brand.Warning; "danger" -> Brand.Destructive; else -> Brand.MutedFg
}

/** 太陽光線タブ（Web: SunTab.jsx） */
@Composable
fun SunTab(
    mode: String, onMode: (String) -> Unit, date: String, time: String, onDate: (String) -> Unit, onTime: (String) -> Unit,
    atMs: Long, sun: JsonElement, times: JsonElement, stops: List<JsonElement>, pickedStop: String, onPickStop: (String) -> Unit,
) {
    val az = sun.dbl("azimuth") ?: 0.0
    val alt = sun.dbl("altitude") ?: 0.0
    val picked = stops.firstOrNull { it.str("name") == pickedStop } ?: stops.firstOrNull { it.str("state") == "current" }
    val lights by produceState<List<JsonElement>>(emptyList(), az, alt, stops) {
        value = stops.map { s -> Logic.call("bus/solar", "lightingState", jsonOf("sunAzimuth" to az, "sunAltitude" to alt, "busHeading" to s.dbl("heading"))) }
    }
    val pickedLight = picked?.let { p -> lights.getOrNull(stops.indexOf(p)) }
    val labels by produceState(listOf("", "", "", "", ""), az, times, atMs) {
        value = listOf(
            Logic.call("bus/solar", "compass", az).text,
            Logic.call("bus/solar", "formatClock", times.long("sunrise")).text,
            Logic.call("bus/solar", "formatClock", times.long("sunset")).text,
            Logic.call("bus/solar", "formatClock", times.long("noon")).text,
            Logic.call("bus/solar", "formatClock", atMs).text,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(title = "太陽光線シミュレーション") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("現在時刻", { onMode("now") }, primary = mode == "now")
                SmallButton("日時を指定", { onMode("pick") }, primary = mode == "pick")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                DateField(date, onDate, label = "日付")
                TimeField(time, onTime, label = "時刻")
            }
            KeyValue("太陽方位角", "${"%.1f".format(az)}°（${labels[0]}）")
            KeyValue("太陽高度", "${"%.1f".format(alt)}°")
            KeyValue("日の出", labels[1]); KeyValue("日の入り", labels[2]); KeyValue("南中", labels[3]); KeyValue("計算時刻（JST）", labels[4])
            Text("太陽の方位は地図上の線で表示します。建物・山による実際の日陰はデータが無いため判定しません。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (picked != null && pickedLight != null) Panel(title = "撮影地点の光線条件", subtitle = picked.str("name")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(pickedLight.str("label").orEmpty(), toneColor(pickedLight.str("tone")))
                Spacer(Modifier.width(8.dp))
                Text("太陽高度 ${"%.1f".format(alt)}°", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(pickedLight.str("detail").orEmpty(), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
            val ff = pickedLight.dbl("fromFront")
            if (ff != null) Text("光が当たる面（推定）: ${facing(ff)}（バスの進行方向 ${pickedLight.dbl("busHeading")?.let { Math.round(it) } ?: "—"}° と太陽方位の差 ${Math.round(ff)}°）", fontSize = 12.sp)
            Text("方位角による幾何学的な推定です。ビル影・山影などの実際の遮蔽は確認できていません。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel(title = "停留所ごとの光線", subtitle = "停留所を選ぶと、その地点でバスを撮影した場合の光線条件を表示します") {
            if (stops.isEmpty()) Text("この系統の停留所座標が揃っていないため、地点ごとの光線を表示できません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            stops.forEachIndexed { i, s ->
                val light = lights.getOrNull(i)
                val active = picked?.str("name") == s.str("name")
                Row(
                    Modifier.fillMaxWidth().background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).clickable { onPickStop(s.str("name").orEmpty()) }.padding(vertical = 5.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.str("name").orEmpty(), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (light != null) Tag(light.str("label").orEmpty(), toneColor(light.str("tone")))
                    Text(s.dbl("heading")?.let { " ${Math.round(it)}°" } ?: " —", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
                }
            }
        }
    }
}

/** 運用履歴タブ（Web: RunHistoryTab.jsx） */
@Composable
fun RunHistoryTab(
    vehicle: String, date: String, minDate: String, maxDate: String, onDate: (String) -> Unit, runs: List<JsonElement>,
    dbRunCount: Int, loading: Boolean, error: String, onReload: () -> Unit, onPlayback: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(title = "運用履歴", action = { SmallButton("更新", onReload, enabled = !loading, icon = Icons.Outlined.Refresh) }) {
            DateField(date, onDate, min = minDate, max = maxDate)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            if (runs.isEmpty()) Text(
                if (loading) "運用記録を読み込んでいます…" else "この日の運用記録はありません。ログシートに運行開始・運行終了が記録されると表示されます（確認できない運用は作りません）。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp),
            )
            runs.forEachIndexed { i, run ->
                if (i > 0) RowDivider()
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(BusFormat.time(run.long("startAt")), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                        Tag(run.str("route")?.ifBlank { null } ?: "系統不明", INFO)
                        Spacer(Modifier.width(6.dp))
                        Text(run.str("destination")?.ifBlank { null } ?: "行先不明", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Tag(if (run.str("source") == "db") "保存記録" else "運行ログ", Brand.MutedFg)
                    }
                    Row {
                        Text((run.str("fromStop")?.ifBlank { null } ?: run.str("operation")?.ifBlank { null } ?: "始発不明") + (run.str("lastStop")?.takeIf { it.isNotBlank() }?.let { " ／ 最終観測 $it" } ?: ""), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(run.long("endAt")?.let { "運行終了 ${BusFormat.time(it)}" } ?: "運行中", fontSize = 11.sp, color = if (run.long("endAt") == null) Brand.Success else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("このアプリが保存した運用 $dbRunCount 件・表示中の日付の運用 ${runs.size} 件", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                SmallButton("履歴を再生", onPlayback, icon = Icons.Outlined.History)
            }
        }
        Note("運用履歴は、ログシートの運行開始・終了（実績）と、画面を開いている間の観測から保存した記録で構成しています（車両 $vehicle）。過去に遡って存在しなかった運行は表示しません。")
    }
}

/** 次の運行予測タブ（Web: PredictionTab.jsx） */
@Composable
fun PredictionTab(vehicle: Vehicle?, current: JsonElement, runs: List<JsonElement>, onShowRoute: (String?) -> Unit, onPlayback: () -> Unit) {
    val forecast by produceState<JsonElement?>(null, runs, current.toString()) {
        value = Logic.call("bus/predict", "forecastVehicle", jsonOf("runs" to runs, "current" to current))
    }
    val f = forecast ?: return
    val next = f.at("nextRuns")
    fun tone(score: Int) = when { score >= 70 -> Brand.Success; score >= 50 -> INFO; score >= 30 -> Brand.Warning; else -> Brand.MutedFg }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(title = "次の運行予測", action = { Box(Modifier.padding(end = 8.dp)) { Tag("予測", Color(0xFF8E5BC1)) } }) {
            if (next.bool("available") == true) next.list("candidates").forEachIndexed { i, c ->
                if (i > 0) RowDivider()
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tag(c.str("route")?.ifBlank { null } ?: "系統不明", INFO)
                        Spacer(Modifier.width(6.dp))
                        Text(c.str("destination")?.ifBlank { null } ?: "行先不明", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Tag("${c.str("confidenceLabel")}（${c.int("confidence")}%）", tone(c.int("confidence") ?: 0))
                    }
                    Text("開始予測 ${c.str("startText")}・出発 ${c.str("fromStop")?.ifBlank { null } ?: "不明"}${c.dbl("gap")?.let { "・前の運行終了から約${Math.round(it)}分" } ?: ""}", fontSize = 11.sp)
                    Text(c.str("basis").orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SmallButton("この系統の経路を表示", { onShowRoute(c.str("route")) }, enabled = !c.str("route").isNullOrBlank(), icon = Icons.Outlined.Route)
                }
            } else Text(next.str("reason").orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            next.str("note")?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Panel(title = "車両の運行予測") {
            f.list("items").forEachIndexed { i, item ->
                if (i > 0) RowDivider()
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row {
                        Text(item.str("label").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (item.bool("available") == true) Tag("信頼度 ${item.int("confidence")}%", tone(item.int("confidence") ?: 0)) else Tag("予測できません", Brand.MutedFg)
                    }
                    if (item.bool("available") == true) Text(item.str("value").orEmpty(), fontSize = 12.sp)
                    Text(item.str("basis").orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Note("予測は、この車両の保存済み運用履歴（${runs.size} 件）だけを根拠にした推定です。時刻表・道路状況・営業所の情報は取得できないため使用していません。実際の運行情報は「現在の運行」「運用履歴」で確認してください。")
        SmallButton("履歴再生へ", onPlayback, icon = Icons.Outlined.History)
        if (vehicle != null && vehicle.stop.isBlank()) Text("現在の停留所が取得できていないため、予測の精度は低くなります。", fontSize = 11.sp, color = Brand.Warning)
    }
}

private val SPEEDS = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 30.0)

/** 運行履歴再生（Web: HistoryPlayback.jsx）。記録の無い時間は架空の位置を作らない。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryPlayback(vehicle: Vehicle?, date: String, onDate: (String) -> Unit, onJumpToRoute: () -> Unit) {
    val logs = rememberPositionLogs(vehicle?.name, date)
    var speed by remember { mutableDoubleStateOf(1.0) }
    var playing by remember { mutableStateOf(false) }
    var cursor by remember { mutableLongStateOf(0L) }
    var routeFilter by remember { mutableStateOf("") }
    var jumpTime by remember { mutableStateOf("") }
    val routes = logs.points.mapNotNull { it.str("route")?.ifBlank { null } }.distinct().sorted()
    val usable = logs.points.filter { it.dbl("lat") != null && it.dbl("lon") != null && (routeFilter.isEmpty() || it.str("route") == routeFilter) }
    val start = usable.firstOrNull()?.long("at") ?: 0L
    val end = usable.lastOrNull()?.long("at") ?: 0L

    LaunchedEffect(date, routeFilter, vehicle?.name) { playing = false; cursor = start }
    LaunchedEffect(start) { if (start > 0 && cursor < start) cursor = start }
    LaunchedEffect(playing, speed, end) {
        while (playing && end > 0) {
            delay(250)
            cursor = minOf(end, cursor + (250 * speed).toLong())
            if (cursor >= end) playing = false
        }
    }
    val at = if (cursor > 0) cursor else start
    val visible = usable.filter { (it.long("at") ?: 0) <= at }
    val current = visible.lastOrNull()
    val next = usable.firstOrNull { (it.long("at") ?: 0) > at }
    val passed = visible.mapNotNull { p -> p.str("stop")?.ifBlank { null }?.let { it to (p.long("at") ?: 0L) } }.distinctBy { it.first }.takeLast(14)
    fun move(ms: Long) { if (usable.isNotEmpty()) { playing = false; cursor = (at + ms).coerceIn(start, end) } }
    val gapMinutes = next?.let { Math.round(((it.long("at") ?: 0) - at) / 60000.0) } ?: 0

    Panel(title = "運行履歴再生", action = { Text("記録 ${usable.size} 点", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp)) }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DateField(date, onDate)
            if (routes.size > 1) SelectField(listOf("" to "すべての系統") + routes.map { it to "系統 $it" }, routeFilter, { routeFilter = it })
            SmallButton("再読込", logs.reload, enabled = !logs.loading, icon = Icons.Outlined.Refresh)
        }
        if (logs.error.isNotBlank()) Text(logs.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        if (usable.isEmpty()) {
            Text(if (logs.loading) "位置履歴を読み込んでいます…" else "この日の位置記録はありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            return@Panel
        }
        val line = usable.map { it.dbl("lat")!! to it.dbl("lon")!! }
        val pins = buildList {
            current?.let { add(MapPin("cur", it.dbl("lat")!!, it.dbl("lon")!!, vehicle?.name.orEmpty(), "${BusFormat.time(it.long("at"))} ${it.str("stop").orEmpty()}", MARKER, 20.dp)) }
        }
        OsmMap(Modifier.fillMaxWidth().height(300.dp).padding(vertical = 6.dp), pins = pins, lines = listOf(MapLine(line, INFO.copy(alpha = 0.6f), 5f)), fitKey = "$date:$routeFilter:${vehicle?.name}")
        if (current == null) Note("この時刻の位置記録はありません")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SmallButton("", { move(-10_000) }, icon = Icons.Outlined.SkipPrevious)
            SmallButton("再生", { if (at >= end) cursor = start; playing = true }, icon = Icons.Outlined.PlayArrow, primary = true)
            SmallButton("一時停止", { playing = false }, icon = Icons.Outlined.Pause)
            SmallButton("停止", { playing = false; cursor = start }, icon = Icons.Outlined.Stop)
            SmallButton("", { move(10_000) }, icon = Icons.Outlined.SkipNext)
            SelectField(SPEEDS.map { it to "${if (it % 1.0 == 0.0) it.toInt() else it}倍" }, speed, { speed = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = (at - start).toFloat(), onValueChange = { playing = false; cursor = start + it.toLong() },
                valueRange = 0f..maxOf(1f, (end - start).toFloat()), modifier = Modifier.weight(1f),
            )
            Text(BusFormat.time(at), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TimeField(jumpTime, { jumpTime = it })
            SmallButton("この時刻へジャンプ", {
                if (jumpTime.isNotBlank() && usable.isNotEmpty()) runCatching {
                    val target = java.time.OffsetDateTime.parse("${date}T$jumpTime:00+09:00").toInstant().toEpochMilli()
                    playing = false; cursor = target.coerceIn(start, end)
                }
            })
            SmallButton("経路を表示", onJumpToRoute, icon = Icons.Outlined.Place)
        }
        KeyValue("再生時刻", BusFormat.dateTime(at))
        KeyValue("車両番号", vehicle?.name ?: "—")
        KeyValue("系統・行先", "${current?.str("route")?.ifBlank { null } ?: "—"} ${current?.str("destination").orEmpty()}")
        KeyValue("速度", "取得できません（シートに速度の記録なし）")
        KeyValue("停車・走行状態", current?.str("stop")?.ifBlank { null }?.let { "停車中（$it）" } ?: "記録なし")
        KeyValue("次の記録", next?.let { "${BusFormat.time(it.long("at"))}（${it.str("stop")}）" } ?: "この日の記録は終了")
        if (gapMinutes >= 5) Note("次の位置記録まで約 $gapMinutes 分あります。記録が無い時間帯は位置を作らず、最後に記録された位置を表示しています。")
        Text("通過した停留所", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        if (passed.isEmpty()) Text("この時刻までの記録はありません", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            passed.forEach { (stop, t) -> Tag("${BusFormat.time(t)} $stop", Brand.MutedFg) }
        }
    }
}

