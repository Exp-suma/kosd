package app.narabuspulse.feature.sim3d

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Logic
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.rememberLocationRequest
import app.narabuspulse.core.str
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.cos
import kotlin.math.roundToInt

private val TABS = listOf("/sim3d" to "シミュレーター", "/sim3d/saved" to "保存したシミュレーション")
private val FOCALS = listOf(24, 35, 50, 85, 135, 200, 300)
private val SENSORS = listOf("ff" to "フルサイズ", "apsc" to "APS-C", "apsc_canon" to "APS-C（キヤノン）", "m43" to "マイクロフォーサーズ", "one" to "1型")
private val PARTS = listOf("front" to "前面", "front_side" to "前面と側面", "left" to "左側面", "right" to "右側面", "rear" to "後面", "rear_side" to "斜め後面", "all" to "車体全体")
private val STANDS = listOf("sidewalk" to "歩道上", "safe_space" to "安全な撮影スペース", "outside_road" to "歩道外", "road" to "車道上", "unknown" to "不明")
private val VIEWS = listOf("orbit" to "自由", "eye" to "撮影者目線", "top" to "上空", "road" to "道路目線")
private val LAYERS = listOf("terrain" to "地形", "roads" to "道路", "buildings" to "建物", "obstacles" to "樹木・電柱", "bus" to "バス", "frustum" to "画角", "sun" to "太陽")
private val PART_LABEL = mapOf("front" to "前面", "rear" to "後面", "left" to "左側面", "right" to "右側面")
private val DEFAULT_SETUP = jsonOf("place_name" to "近鉄奈良駅前", "lat" to 34.6837, "lon" to 135.827, "azimuth" to 225, "camera_pitch" to 0, "focal_length" to 85, "sensor" to "ff", "orientation" to "landscape", "camera_height" to 1.6,
    "road_distance" to 6, "bus_distance" to 35, "road_bearing" to 45, "target_lane" to "up", "target_part" to "front", "stand_type" to "sidewalk")
private fun jstNow() = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))
private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

@Composable
private fun NumField(label: String, value: Double?, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()) }
    OutlinedTextField(text, { text = it; it.toDoubleOrNull()?.let(onChange) }, label = { Text(label, fontSize = 10.sp) }, singleLine = true, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun SliderRow(label: String, value: Double, range: ClosedFloatingPointRange<Float>, onChange: (Double) -> Unit) {
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Slider(value.toFloat().coerceIn(range), { onChange(it.roundToInt().toDouble()) }, valueRange = range, modifier = Modifier.height(32.dp))
}

/** AI撮影環境3Dシミュレーター（Web: sim3d.index.jsx + SimControls / SimLayers / SimResult） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Sim3dScreen() {
    val nav = LocalNav.current; val scope = rememberCoroutineScope()
    val loaded = remember { Sim3dHandoff.take() }
    var setup by remember {
        mutableStateOf(loaded?.let { row ->
            fun num(k: String, keepZero: Boolean = false) = row.dbl(k)?.takeIf { keepZero || it != 0.0 }
            JsonObject(DEFAULT_SETUP + jsonOf("place_name" to row.str("place_name")?.ifBlank { null }, "lat" to num("lat"), "lon" to num("lon"), "azimuth" to (row.dbl("azimuth") ?: 0.0), "camera_pitch" to (row.dbl("camera_pitch") ?: 0.0), "focal_length" to num("focal_length"),
                "sensor" to row.str("sensor")?.ifBlank { null }, "orientation" to row.str("orientation")?.ifBlank { null }, "camera_height" to num("camera_height"), "road_distance" to num("road_distance"), "bus_distance" to num("bus_distance"),
                "target_part" to row.str("target_part")?.ifBlank { null }, "stand_type" to row.str("stand_type")?.ifBlank { null }))
        } ?: DEFAULT_SETUP)
    }
    val at = loaded.str("at")
    var date by remember { mutableStateOf(at?.take(10) ?: jstNow().toLocalDate().toString()) }
    var hour by remember { mutableIntStateOf(at?.drop(11)?.take(2)?.toIntOrNull()?.takeIf { it > 0 } ?: 9) }
    var layers by remember { mutableStateOf(LAYERS.associate { it.first to true }) }
    var viewMode by remember { mutableStateOf("orbit") }
    var data by remember { mutableStateOf<JsonElement?>(null) }; var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf("") }
    var weather by remember { mutableStateOf<JsonElement?>(null) }; var survey by remember { mutableStateOf<JsonElement?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }; var locating by remember { mutableStateOf(false) }
    var savedId by remember { mutableStateOf(loaded.str("id").orEmpty()) }
    var title by remember { mutableStateOf(if (loaded != null) loaded.str("title").orEmpty() else "近鉄奈良駅前 朝") }; var notes by remember { mutableStateOf(loaded.str("notes").orEmpty()) }
    var saving by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    var reloadTick by remember { mutableIntStateOf(0) }
    var scene by remember { mutableStateOf<SceneModel?>(null) }; var result by remember { mutableStateOf<JsonElement?>(null) }; var draw by remember { mutableStateOf<DrawModel?>(null) }
    val sceneKey = remember { arrayOfNulls<Any>(1) }

    fun set(vararg pairs: Pair<String, Any?>) { setup = JsonObject(setup + jsonOf(*pairs)) }
    val lat = setup.dbl("lat") ?: 34.6837; val lon = setup.dbl("lon") ?: 135.827
    val latKey = "%.4f".format(lat); val lonKey = "%.4f".format(lon)
    val locate = rememberLocationRequest { loc -> locating = false; if (loc == null) message = "現在地を取得できませんでした" else set("lat" to round6(loc.lat), "lon" to round6(loc.lon)) }

    LaunchedEffect(latKey, lonKey, reloadTick) {
        loading = true; error = ""
        try { data = Api.rpc("sim3d/server-fns#getScene", jsonOf("lat" to lat, "lon" to lon, "radius" to 160, "force" to (reloadTick > 0).takeIf { it })) }
        catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "地形・建物データを取得できませんでした" } } finally { loading = false }
    }
    LaunchedEffect(date, "%.3f".format(lat), "%.3f".format(lon)) { weather = try { Api.rpc("sim3d/server-fns#getWeather", jsonOf("lat" to lat, "lon" to lon, "date" to date)) } catch (e: Throwable) { if (e is CancellationException) throw e; null } }
    LaunchedEffect(data, setup, date, hour, weather, survey) {
        delay(50)
        val d = data
        val key = Triple(d, lat, lon)
        if (d != null && sceneKey[0] != key) { scene = parseScene(Logic.call("glue/sim3d", "setScene", d, lat, lon)); sceneKey[0] = key }
        val out = runCatching { Logic.call("glue/sim3d", "compute", jsonOf("setup" to setup, "date" to date, "hour" to hour, "weather" to weather, "survey" to survey)) }.getOrNull() ?: return@LaunchedEffect
        result = out; draw = parseDraw(out.objAt("draw"))
    }
    val r = result; val score = r.objAt("score"); val vis = r.objAt("visibility"); val wh = r.objAt("weatherHour")

    fun save() {
        saving = true; message = ""
        scope.launch {
            try {
                val row = Api.rpc("sim3d/server-fns#saveSimulation", jsonOf(
                    "title" to title.ifBlank { "${setup.str("place_name")?.ifBlank { null } ?: "撮影地点"} $date ${"%02d".format(hour)}" }, "place_name" to setup.str("place_name"), "at" to "${date}T${"%02d".format(hour)}:00", "lat" to lat, "lon" to lon,
                    "elevation" to r.dbl("base")?.takeIf { it != 0.0 }, "camera_height" to setup.dbl("camera_height"), "road_distance" to setup.dbl("road_distance"), "stand_type" to setup.str("stand_type"), "azimuth" to setup.dbl("azimuth"), "camera_pitch" to setup.dbl("camera_pitch"),
                    "focal_length" to setup.dbl("focal_length"), "sensor" to setup.str("sensor"), "orientation" to setup.str("orientation"), "target_part" to setup.str("target_part"), "bus_distance" to setup.dbl("bus_distance"),
                    "survey_location_id" to survey.str("id"), "survey_location_key" to survey.str("key"),
                    "weather" to jsonOf("date" to date, "hour" to hour, "text" to wh.str("text"), "temp" to wh.dbl("temp"), "precipProb" to wh.dbl("precipProb"), "cloud" to wh.dbl("cloud"), "source" to weather.str("source")).toString(),
                    "sun" to jsonOf("azimuth" to (r.dbl("sun.azimuth") ?: 0.0).roundToInt(), "altitude" to Math.round((r.dbl("sun.altitude") ?: 0.0) * 10) / 10.0, "sunrise" to r.str("sunTimes.sunrise"), "sunset" to r.str("sunTimes.sunset"), "noon" to r.str("sunTimes.noon")).toString(),
                    "scene_summary" to jsonOf("terrain" to data.objAt("sources.terrain"), "osm" to data.objAt("sources.osm"), "cached" to (data.bool("cached") == true)).toString(),
                    "result" to jsonOf("parts" to vis.objAt("parts"), "occluders" to JsonArray(vis.list("occluders").take(6)), "terrainBlocked" to vis.bool("terrainBlocked"), "heightsKnown" to vis.bool("heightsKnown"), "occlusion" to r.objAt("occlusion"),
                        "fov" to jsonOf("h" to Math.round((r.dbl("fov.hFov") ?: 0.0) * 10) / 10.0, "v" to Math.round((r.dbl("fov.vFov") ?: 0.0) * 10) / 10.0), "obstaclesInView" to r.int("obstaclesInView"), "light" to r.str("light.label")).toString(),
                    "score" to score.dbl("total"), "score_detail" to JsonArray(score.list("items")).toString(), "notes" to notes, "id" to savedId.ifBlank { null }))
                savedId = row.str("id") ?: savedId; message = "保存しました"
            } catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "保存できませんでした" } } finally { saving = false }
        }
    }

    ScreenList {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRow(TABS, "/sim3d", { if (it != "/sim3d") nav.go(it) })
                Heading("AI撮影環境3Dシミュレーター", "標高・道路・建物・樹木を実データから3D表示し、立ち位置・画角・太陽・交通量から撮影環境を確認します。")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SmallButton("交通量", { pickerOpen = !pickerOpen }, icon = Icons.Outlined.Storage); SmallButton(if (loading) "取得中…" else "再取得", { reloadTick++ }, enabled = !loading, icon = Icons.Outlined.Refresh) }
            }
        }
        if (pickerOpen) item {
            Panel {
                Muted("公開交通量（道路交通センサス）の観測地点を選ぶと、統計に基づく被り率を評価に加えます。", 11)
                var q by remember { mutableStateOf("") }; var rows by remember { mutableStateOf<List<JsonElement>?>(null) }; var busy by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(q, { q = it }, placeholder = { Text("路線名・区間名で検索（例 24号、柏木町）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                    SmallButton(if (busy) "…" else "検索", { busy = true; scope.launch { rows = runCatching { Api.rpc("occlusion/server-fns#searchSurveyLocations", jsonOf("q" to q, "limit" to 20)).items() }.getOrDefault(emptyList()); busy = false } }, enabled = !busy, icon = Icons.Outlined.Search)
                }
                survey?.let { s -> Text("選択中: ${s.str("route_name")} ／ ${s.str("section_name")?.ifBlank { null } ?: "区間名なし"}", fontSize = 12.sp, fontWeight = FontWeight.Bold); SmallButton("選択を解除", { survey = null }) }
                rows?.let { list ->
                    if (list.isEmpty()) Muted("該当する観測地点がありません", 11)
                    list.forEach { row -> Column(Modifier.fillMaxWidth().clickable { survey = row; pickerOpen = false }.padding(vertical = 5.dp)) { Text("${row.str("route_name")} ／ ${row.str("section_name")?.ifBlank { null } ?: "区間名なし"}", fontSize = 12.sp); Muted("12時間交通量 上り${row.str("vol_12h_up") ?: "—"}・下り${row.str("vol_12h_down") ?: "—"}台 ／ 車線 ${row.str("lanes") ?: "—"}", 10) } }
                }
            }
        }
        if (error.isNotBlank()) item { Text(error, fontSize = 12.sp, color = Brand.Destructive) }
        item {
            Box {
                Scene3D(scene, draw, layers, viewMode, (r.dbl("fov.vFov") ?: 30.0).toFloat(), r.dbl("sun.altitude") ?: 30.0, Modifier.fillMaxWidth().height(400.dp)) { x, z ->
                    set("lat" to round6(lat + z / 111320.0), "lon" to round6(lon + x / (111320.0 * cos(Math.toRadians(lat)))))
                }
                if (loading) Tag("地形・建物を取得中", Brand.MutedFg, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { VIEWS.forEach { (v, l) -> FilterChip(viewMode == v, { viewMode = v }, label = { Text(l, fontSize = 11.sp) }) } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { LAYERS.forEach { (k, l) -> FilterChip(layers[k] != false, { layers = layers + (k to (layers[k] == false)) }, label = { Text(l, fontSize = 10.sp) }) } }
            Muted("ドラッグで回転・ピンチで拡大縮小。地面をタップすると立ち位置が移動します。地形は25点の標高、建物はOSMの外形と高さ（無い場合は4mの仮表示）です。", 10)
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Muted("撮影環境の総合評価", 11); Row(verticalAlignment = Alignment.Bottom) { Text(score.dbl("total")?.roundToInt()?.toString() ?: "—", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Brand.Orange); Muted("/100", 11) } }
                    Muted("評価 ${score.int("available") ?: 0}項目\n評価不能 ${score.int("missing") ?: 0}項目", 10)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (vis.objAt("parts") as? JsonObject)?.forEach { (k, p) -> Tag("${PART_LABEL[k] ?: k} 遮蔽${((p.dbl("ratio") ?: 0.0) * 100).roundToInt()}%", if (p.bool("blocked") == true) Brand.Destructive else Brand.MutedFg) } }
                if (vis.bool("terrainBlocked") == true) Text("地形（斜面・盛土）が視線上にかかっています", fontSize = 11.sp, color = Brand.Destructive)
                r.str("light.label")?.let { Text("光線：$it", fontSize = 12.sp, fontWeight = FontWeight.Bold) }; r.str("light.detail")?.let { Muted(it, 10) }
                Text("評価の内訳と根拠", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                score.items("items").forEach { item ->
                    val v = item.dbl("value")
                    Row { Text(item.str("label").orEmpty(), fontSize = 11.sp, modifier = Modifier.weight(1f)); Text(v?.roundToInt()?.toString() ?: "評価不能", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (v == null) Brand.MutedFg else androidx.compose.ui.graphics.Color.Unspecified) }
                    if (v != null) LinearProgressIndicator({ (v / 100).toFloat().coerceIn(0.02f, 1f) }, Modifier.fillMaxWidth().height(4.dp), color = Brand.Orange)
                    Muted(item.str("reason").orEmpty(), 10)
                }
            }
        }
        item {
            Panel(title = "データの取得状況") {
                val buildings = data.items("osm.buildings"); val obstacles = data.items("osm.obstacles")
                Muted("地形：${if (data.bool("terrain.ok") == true) "${data.int("terrain.points")}/${data.int("terrain.total")}点 取得（${data.str("terrain.hsrc") ?: "標高API"}）" else "未取得"}${data.str("sources.terrain.source")?.let { "／$it" } ?: ""}", 10)
                Muted("道路：${data.items("osm.roads").size}本（${data.str("sources.osm.source") ?: "OpenStreetMap"}）${data.str("sources.osm.error")?.let { "／エラー：$it" } ?: ""}", 10)
                Muted("建物：${buildings.size}棟（高さタグ ${buildings.count { it.str("heightSource") == "tag" }}・階数から推定 ${buildings.count { it.str("heightSource") == "levels" }}・高さ不明 ${buildings.count { it.str("heightSource") == "unknown" }}）", 10)
                Muted("樹木・電柱など：${obstacles.size}件（${listOf("tree", "pole", "lamp", "signal", "barrier").joinToString("・") { k -> "$k ${obstacles.count { it.str("kind") == k }}" }}）", 10)
                Muted(if (data.bool("cached") == true) "地形・建物データはキャッシュから表示（7日以内）" else "地形・建物データを新規取得", 10)
                Muted("交通量（統計）：${r.dbl("occlusion.areaRate")?.let { "被り面積率 ${(it * 100).roundToInt()}%" } ?: "観測地点が未選択"}", 10)
                Text("高さの無い建物は4mの仮表示（半透明）で、遮蔽判定は参考値です。バスは標準寸法の仮モデルで、個車の外観は再現していません。交通量は5年ごとの公開統計に基づく推定です。", fontSize = 10.sp, color = Brand.Warning)
            }
        }
        item {
            Panel(title = if (savedId.isNotBlank()) "シミュレーションを更新" else "シミュレーションを保存") {
                OutlinedTextField(title, { title = it }, placeholder = { Text("名前（例 近鉄奈良駅前 朝）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, placeholder = { Text("メモ（現地の様子・注意点など）", fontSize = 12.sp) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { SmallButton(if (saving) "保存中…" else "保存", { save() }, enabled = !saving, icon = Icons.Outlined.Save, primary = true); if (message.isNotBlank()) Muted("  $message", 11) }
            }
        }
        item {
            Panel(title = "立ち位置とカメラ", action = { SmallButton(if (locating) "取得中…" else "現在地", { locating = true; message = ""; locate() }, enabled = !locating, icon = Icons.Outlined.MyLocation) }) {
                OutlinedTextField(setup.str("place_name").orEmpty(), { set("place_name" to it) }, label = { Text("撮影地点名", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumField("緯度", lat, Modifier.weight(1f)) { set("lat" to it.coerceIn(-90.0, 90.0)) }; NumField("経度", lon, Modifier.weight(1f)) { set("lon" to it.coerceIn(-180.0, 180.0)) } }
                Muted("3D上の地面をタップすると、その場所へ立ち位置を移します。", 10)
                SelectField(STANDS, setup.str("stand_type") ?: "sidewalk", { set("stand_type" to it) }, label = "立ち位置の種類")
                Muted("車道上は推奨しません。現地の状況を必ず確認してください。", 10)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumField("カメラの高さ（m）", setup.dbl("camera_height"), Modifier.weight(1f)) { set("camera_height" to it.coerceIn(0.0, 10.0)) }; NumField("道路までの距離（m）", setup.dbl("road_distance"), Modifier.weight(1f)) { set("road_distance" to it.coerceIn(0.5, 300.0)) } }
                val az = setup.dbl("azimuth") ?: 0.0
                SliderRow("カメラの向き　${az.roundToInt()}°", az, 0f..359f) { set("azimuth" to it) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0 to "北", 90 to "東", 180 to "南", 270 to "西").forEach { (deg, l) -> SmallButton(l, { set("azimuth" to deg) }) } }
                val pitch = setup.dbl("camera_pitch") ?: 0.0
                SliderRow("カメラの仰角　${pitch.roundToInt()}°", pitch, -20f..40f) { set("camera_pitch" to it) }
                val focal = (setup.dbl("focal_length") ?: 50.0).roundToInt()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    SelectField(FOCALS.map { "$it" to "${it}mm" } + ("custom" to "その他（右で指定）"), if (focal in FOCALS) "$focal" else "custom", { v -> v.toIntOrNull()?.let { set("focal_length" to it) } }, label = "焦点距離（35mm判換算）")
                    NumField("mm", focal.toDouble(), Modifier.fillMaxWidth(0.3f)) { set("focal_length" to it.coerceIn(4.0, 1200.0)) }
                    SelectField(SENSORS, setup.str("sensor") ?: "ff", { set("sensor" to it) }, label = "センサー")
                    SelectField(listOf("landscape" to "横位置", "portrait" to "縦位置"), setup.str("orientation") ?: "landscape", { set("orientation" to it) })
                    SelectField(PARTS, setup.str("target_part") ?: "front", { set("target_part" to it) }, label = "撮影対象")
                    SelectField(listOf("up" to "上り（進行方向）", "down" to "下り（対向）"), setup.str("target_lane") ?: "up", { set("target_lane" to it) }, label = "対象車線")
                }
                val bd = setup.dbl("bus_distance") ?: 30.0
                SliderRow("バスまでの距離　${bd.roundToInt()}m", bd, 8f..150f) { set("bus_distance" to it) }
                val rb = setup.dbl("road_bearing") ?: 0.0
                SliderRow("道路の進行方位　${rb.roundToInt()}°", rb, 0f..359f) { set("road_bearing" to it) }
            }
        }
        item {
            Panel(title = "日時", action = { SmallButton("現在", { val now = jstNow(); date = now.toLocalDate().toString(); hour = now.hour }) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { DateField(date, { date = it }); SelectField((0..23).map { "$it" to "%02d:00".format(it) }, "$hour", { hour = it.toInt() }) }
                Slider(hour.toFloat(), { hour = it.roundToInt() }, valueRange = 0f..23f, steps = 22, modifier = Modifier.height(32.dp))
                Muted("日の出 ${r.str("sunTimes.sunrise") ?: "—"}　日の入り ${r.str("sunTimes.sunset") ?: "—"}　太陽方位 ${r.dbl("sun.azimuth")?.roundToInt()?.let { "$it°" } ?: "—"}　太陽高度 ${r.dbl("sun.altitude")?.let { "%.1f°".format(it) } ?: "—"}", 11)
                Muted(if (wh != null) "${wh.str("text")}・${wh.str("temp") ?: "—"}℃・降水${wh.str("precipProb") ?: "—"}%・雲量${wh.str("cloud") ?: "—"}%" else "天気：取得していません", 11)
            }
        }
    }
}

/** 保存したシミュレーション（Web: sim3d.saved.jsx） */
@Composable
fun Sim3dSavedScreen() {
    val nav = LocalNav.current; val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<JsonElement>?>(null) }; var busyId by remember { mutableStateOf("") }
    suspend fun load() { rows = try { Api.rpc("sim3d/server-fns#listSimulations", jsonOf()).let { (it as? JsonArray)?.toList() ?: it.items("rows") } } catch (e: Throwable) { if (e is CancellationException) throw e; emptyList() } }
    LaunchedEffect(Unit) { load() }
    ScreenList {
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { ChipRow(TABS, "/sim3d/saved", { if (it != "/sim3d/saved") nav.go(it) }); Heading("保存したシミュレーション", "保存した撮影環境シミュレーションを呼び出して、条件を変えて再計算できます。") } }
        if (rows == null) item { Muted("読み込み中…", 12) }
        if (rows?.isEmpty() == true) item { Panel { Muted("保存されたシミュレーションはありません。シミュレーターで条件を作成し「保存」してください。", 12) } }
        rows.orEmpty().forEach { row ->
            item(key = "sim-${row.str("id")}") {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(row.str("title").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Tag(row.dbl("score")?.let { "${it.roundToInt()}点" } ?: "評価なし") }
                    Muted("${row.str("place_name")?.ifBlank { null } ?: "地点名なし"}／${row.str("at")?.replace("T", " ") ?: "日時なし"}", 11)
                    Muted("${row.str("focal_length") ?: "—"}mm・方位${row.dbl("azimuth")?.let { "${it.roundToInt()}°" } ?: "—"}・距離${row.str("bus_distance") ?: "—"}m ／ 保存 ${row.long("computed_at")?.let { BusFormat.dateTime(it) } ?: "—"}", 10)
                    row.str("notes")?.takeIf { it.isNotBlank() }?.let { Muted(it, 11) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton("開く", { Sim3dHandoff.pending = row as? JsonObject; nav.go("/sim3d") })
                        SmallButton("削除", { busyId = row.str("id").orEmpty(); scope.launch { try { Api.rpc("sim3d/server-fns#deleteSimulation", jsonOf("id" to row.str("id"))); load() } catch (e: Throwable) { if (e is CancellationException) throw e } finally { busyId = "" } } }, enabled = busyId != row.str("id"), icon = Icons.Outlined.Delete)
                    }
                }
            }
        }
    }
}
