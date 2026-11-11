package app.narabuspulse.feature.occlusion

import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import app.narabuspulse.core.Json
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
import app.narabuspulse.core.text
import app.narabuspulse.core.toJson
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.MapLine
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val TABS = listOf("/occlusion" to "解析", "/occlusion/hourly" to "時間帯別", "/occlusion/compare" to "角度・画角の比較", "/occlusion/ranking" to "地点ランキング", "/occlusion/verify" to "予測と実績", "/occlusion/data" to "交通量データ")
private val HOURS = (7..19).toList()
private val STAND_LABEL = listOf("sidewalk" to "歩道上", "outside_road" to "歩道外（敷地内など）", "safe_space" to "安全な撮影スペース", "road" to "道路上（危険）", "unknown" to "不明")
private val SENSOR_LABEL = listOf("ff" to "フルサイズ", "apsc" to "APS-C（ニコン・ソニー等）", "apsc_canon" to "APS-C（キヤノン）", "m43" to "マイクロフォーサーズ", "one" to "1型")
private val PARTS = listOf("front" to "前面", "front_side" to "斜め前面", "left" to "左側面", "right" to "右側面", "rear_side" to "斜め後面", "rear" to "後面", "all" to "車体全体")
private val PART_LABEL = PARTS.toMap()
private val GRADES = listOf("none" to "被りなし", "slight" to "軽微な被り", "partial" to "部分被り", "mostly" to "大部分被り", "full" to "完全被り", "unknown" to "算出不能")
private fun gradeLabel(k: String?) = GRADES.toMap()[k] ?: "被りなし"
private fun gradeColor(k: String?) = when (k) { "slight" -> INFO; "partial" -> Brand.Warning; "mostly", "full" -> Brand.Destructive; else -> Brand.MutedFg }
private val CONFIDENCE = mapOf("measured" to "実測交通量ベース", "reference" to "参考推定", "insufficient" to "データ不足")
private fun pct(v: Double?, digits: Int = 0) = v?.let { "%.${digits}f%%".format(it * 100) } ?: "—"
private fun num(v: Double?) = v?.let { java.text.NumberFormat.getInstance(java.util.Locale.JAPAN).format(it.toLong()) } ?: "—"

/** 編集中の撮影条件（Web: lib/occlusion/state.js。localStorage "occlusion:draft:v1" と同じ形で端末に保存）。 */
object OcclusionDraft {
    private val EMPTY = jsonOf("name" to "", "place_name" to "", "note" to "", "lat" to 34.6835, "lon" to 135.827, "camera_height" to 1.5, "road_distance" to 6, "stand_type" to "sidewalk", "road_bearing" to 0,
        "target_lane" to "up", "bus_distance" to 30, "target_part" to "front", "azimuth" to 170, "focal_length" to 50, "sensor" to "ff", "orientation" to "landscape", "obstacles" to "[]", "aim_mode" to "azimuth",
        "survey_location_id" to "", "survey_location_key" to "", "survey_location_auto" to false, "survey_location_distance" to 0, "survey_location_data" to "")
    var value by mutableStateOf(EMPTY)
        private set
    var savedId by mutableStateOf("")
    private var loaded = false
    private fun prefs(c: Context) = c.getSharedPreferences("logic_storage", Context.MODE_PRIVATE)
    fun load(c: Context) {
        if (loaded) return; loaded = true
        runCatching { (Json.parse(prefs(c).getString("occlusion:draft:v1", null) ?: return) as? JsonObject)?.let { value = JsonObject(EMPTY + it) } }
    }
    fun set(c: Context, vararg patch: Pair<String, Any?>) = replace(c, JsonObject(value + patch.associate { it.first to toJson(it.second) }))
    fun replace(c: Context, next: JsonObject) { value = next; runCatching { prefs(c).edit().putString("occlusion:draft:v1", next.toString()).apply() } }
    fun fromSetup(row: JsonElement): JsonObject = JsonObject(EMPTY + ((row as? JsonObject) ?: emptyMap()).filterValues { it !is JsonNull } + mapOf("aim_mode" to toJson(if (row.str("aim_mode") == "distance") "distance" else "azimuth"), "obstacles" to toJson(row.str("obstacles") ?: "[]")))
}

private fun presetAzimuth(roadBearing: Double, kind: String): Double = ((roadBearing + (mapOf("front" to 170.0, "side" to 90.0, "rear" to 10.0)[kind] ?: 90.0)) % 360 * 10).roundToInt() / 10.0

private fun destination(lat: Double, lon: Double, bearing: Double, dist: Double): Pair<Double, Double> {
    val r = 6371000.0; val b = Math.toRadians(bearing); val la = Math.toRadians(lat); val lo = Math.toRadians(lon); val d = dist / r
    val la2 = asin(sin(la) * cos(d) + cos(la) * sin(d) * cos(b))
    val lo2 = lo + atan2(sin(b) * sin(d) * cos(la), cos(d) - sin(la) * sin(la2))
    return Math.toDegrees(la2) to Math.toDegrees(lo2)
}

/** 選択中の観測地点（保存済みの控えを使い、取得できたら最新に置き換える）。Web: useSurveyLocation */
@Composable
private fun rememberSurveyLocation(draft: JsonObject): JsonElement? {
    val id = draft.str("survey_location_id").orEmpty()
    val fallback = remember(id, draft.str("survey_location_data")) { draft.str("survey_location_data")?.takeIf { it.isNotBlank() && id.isNotBlank() }?.let { runCatching { Json.parse(it) }.getOrNull() }?.takeIf { it.str("id") == id } }
    val fetched = produceState<JsonElement?>(null, id) { value = if (id.isBlank()) null else runCatching { Api.rpc("occlusion/server-fns#getSurveyLocations", jsonOf("ids" to listOf(id))).items().firstOrNull() }.getOrNull() }.value
    return fetched ?: fallback
}

@Composable
private fun rememberPrediction(draft: JsonObject, location: JsonElement?, observations: JsonElement?, hour: Int): JsonElement? =
    produceState<JsonElement?>(null, draft, location, observations, hour) {
        value = Logic.call("occlusion/model", "predictOcclusion", jsonOf("setup" to draft, "location" to location, "observations" to (observations ?: JsonObject(emptyMap())), "hour" to hour)).takeIf { it is JsonObject }
    }.value

@Composable
private fun OcclusionScaffold(current: String, title: String, description: String, content: LazyListScope.() -> Unit) {
    val nav = LocalNav.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { OcclusionDraft.load(context) }
    ScreenList {
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { ChipRow(TABS, current, { if (it != current) nav.go(it) }); Heading(title, description) } }
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HourChips(hour: Int, onPick: (Int) -> Unit) = FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
    Muted("時間帯", 11); HOURS.forEach { h -> FilterChip(h == hour, { onPick(h) }, label = { Text("${h}時", fontSize = 11.sp) }) }
}

@Composable
private fun NumF(label: String, value: Double?, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    var text by remember(label) { mutableStateOf(value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()) }
    LaunchedEffect(value) { if (text.toDoubleOrNull() != value) text = value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty() }
    OutlinedTextField(text, { text = it; it.toDoubleOrNull()?.let(onChange) }, label = { Text(label, fontSize = 10.sp) }, singleLine = true, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun Kv(label: String, value: String, sub: String = "") = Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(label, fontSize = 11.sp, color = Brand.MutedFg, modifier = Modifier.weight(1f)); Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold); if (sub.isNotBlank()) Muted("  $sub", 10) }

/** AI撮影環境・被り率解析（Web: occlusion.index.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OcclusionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = OcclusionDraft.value
    fun set(vararg p: Pair<String, Any?>) = OcclusionDraft.set(context, *p)
    var hour by remember { mutableStateOf(8) }
    var message by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var autoBusy by remember { mutableStateOf(false) }
    var autoMessage by remember { mutableStateOf("") }
    var autoKey by remember { mutableStateOf("") }
    val savedId = OcclusionDraft.savedId
    val location = rememberSurveyLocation(draft)
    val summary = produceState<JsonElement?>(null, savedId) { value = if (savedId.isBlank()) null else runCatching { Api.rpc("occlusion/server-fns#getObservationSummary", jsonOf("setup_id" to savedId)) }.getOrNull() }.value
    val result = rememberPrediction(draft, location, summary, hour)
    val locate = rememberLocationRequest { loc -> locating = false; if (loc == null) message = "現在地を取得できませんでした" else set("lat" to "%.6f".format(loc.lat).toDouble(), "lon" to "%.6f".format(loc.lon).toDouble()) }

    // 立ち位置から最寄りの観測地点を自動で選ぶ（手動で選んだ地点は上書きしない）
    val latKey = ((draft.dbl("lat") ?: 0.0) * 1000).roundToInt() / 1000.0; val lonKey = ((draft.dbl("lon") ?: 0.0) * 1000).roundToInt() / 1000.0
    val autoAllowed = draft.str("survey_location_id").isNullOrBlank() || draft.bool("survey_location_auto") == true
    LaunchedEffect(autoAllowed, latKey, lonKey) {
        val key = "$latKey,$lonKey"
        if (!autoAllowed || latKey == 0.0 || lonKey == 0.0 || autoKey == key) return@LaunchedEffect
        delay(700); autoBusy = true; autoMessage = ""
        try {
            var res: JsonElement? = null
            for (i in 0 until 10) { res = Api.rpc("occlusion/server-fns#findNearestSurveyLocation", jsonOf("lat" to latKey, "lon" to lonKey, "batch" to 80)); if ((res.int("remaining") ?: 0) == 0) break }
            autoKey = key
            val nearest = res.objAt("nearest")
            if (nearest == null) autoMessage = "立ち位置周辺の観測地点を特定できませんでした。地名で地点を検索して選んでください"
            else OcclusionDraft.set(context, "survey_location_id" to nearest.str("id"), "survey_location_key" to nearest.str("key"), "survey_location_auto" to true, "survey_location_distance" to (nearest.dbl("distance")?.roundToInt() ?: 0), "survey_location_data" to nearest.toString())
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { autoMessage = "最寄りの観測地点を取得できませんでした" } finally { autoBusy = false }
    }
    suspend fun savePrediction(hourValue: Int, inputs: JsonObject) = Api.rpc("occlusion/server-fns#savePrediction", jsonOf("setup_id" to savedId, "setup_name" to draft.str("name"), "target_part" to draft.str("target_part"), "hour" to hourValue,
        "occurrence_rate" to result.dbl("occurrenceRate"), "area_rate" to result.dbl("areaRate"), "grade" to (result.str("grade") ?: "unknown"), "confidence" to (result.str("confidence") ?: "insufficient"),
        "by_class" to (result.objAt("byClass") ?: JsonObject(emptyMap())).toString(), "by_part" to (result.objAt("byPart") ?: JsonObject(emptyMap())).toString(), "inputs" to inputs.toString(), "note" to result.list("notes").joinToString(" / ") { it.text }.take(900)))

    OcclusionScaffold("/occlusion", "AI撮影環境・被り率解析", "立ち位置・カメラの向き・画角と公開交通量（道路交通センサス）から、バスが他の交通に遮られる確率を算出します。") {
        item { OcclusionMap(draft, result) { la, lo -> set("lat" to "%.6f".format(la).toDouble(), "lon" to "%.6f".format(lo).toDouble()) } }
        item { HourChips(hour) { hour = it } }
        item { PredictionCard(result) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallButton(if (saving) "記録中…" else "この予測を記録", {
                    if (savedId.isBlank()) { message = "先に撮影条件を保存してください"; return@SmallButton }
                    saving = true
                    scope.launch {
                        try { savePrediction(hour, jsonOf("azimuth" to draft.dbl("azimuth"), "focal_length" to draft.dbl("focal_length"), "sensor" to draft.str("sensor"), "road_distance" to draft.dbl("road_distance"), "distance" to result.dbl("distance"), "location_key" to draft.str("survey_location_key"))); message = "予測を記録しました" }
                        catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "記録できませんでした" } } finally { saving = false }
                    }
                }, enabled = !saving, icon = Icons.Outlined.Save)
                if (message.isNotBlank()) Muted("  $message", 11)
            }
        }
        item { ActualForm(draft, savedId, result, { h, inputs -> savePrediction(h, inputs) }) { message = "実績を記録しました" } }
        item {
            // 条件の保存（Web: SetupSaveBar.jsx）
            var busy by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf("") }
            Panel {
                OutlinedTextField(draft.str("name").orEmpty(), { set("name" to it) }, label = { Text("条件の名前", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.str("place_name").orEmpty(), { set("place_name" to it) }, label = { Text("場所の名前", fontSize = 10.sp) }, placeholder = { Text("例 近鉄奈良駅前・北向き", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallButton(if (busy) "保存中…" else if (savedId.isNotBlank()) "上書き保存" else "この条件を保存", {
                        if (draft.str("name").isNullOrBlank()) { msg = "条件の名前を入力してください"; return@SmallButton }
                        busy = true; msg = ""
                        scope.launch {
                            try {
                                val row = Api.rpc("occlusion/server-fns#saveShootingSetup", JsonObject((draft - "part" - "id") + (if (savedId.isNotBlank()) mapOf("id" to toJson(savedId)) else emptyMap())))
                                OcclusionDraft.savedId = row.str("id").orEmpty(); set("id" to row.str("id")); msg = "保存しました"
                            } catch (e: Throwable) { if (e is CancellationException) throw e; msg = e.userMessage().ifBlank { "保存できませんでした" } } finally { busy = false }
                        }
                    }, enabled = !busy, icon = Icons.Outlined.Save, primary = true)
                    if (msg.isNotBlank()) Muted("  $msg", 11)
                }
            }
        }
        item {
            // 立ち位置・道路・交通量データ（Web: PositionControls.jsx + TrafficPicker.jsx）
            Panel(title = "立ち位置", action = { SmallButton(if (locating) "取得中…" else "現在地", { locating = true; message = ""; locate() }, enabled = !locating, icon = Icons.Outlined.MyLocation) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumF("緯度", draft.dbl("lat"), Modifier.weight(1f)) { set("lat" to it) }; NumF("経度", draft.dbl("lon"), Modifier.weight(1f)) { set("lon" to it) } }
                Muted("地図をタップすると、その場所へ立ち位置を移します。道路上には立たず、歩道や安全な場所を選んでください。", 10)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectField(STAND_LABEL, draft.str("stand_type") ?: "sidewalk", { set("stand_type" to it) }, label = "立ち位置の区分")
                    SelectField(listOf("up" to "上り（道路方位の向き）", "down" to "下り（逆方向）"), draft.str("target_lane") ?: "up", { set("target_lane" to it) }, label = "撮影する車線")
                }
                if (draft.str("stand_type") == "road") Text("道路上での撮影は危険です。歩道・敷地内・安全なスペースに移動してください。", fontSize = 11.sp, color = Brand.Destructive)
                Text("道路と対象車線", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumF("道路の方位（上り・°）", draft.dbl("road_bearing"), Modifier.weight(1f)) { set("road_bearing" to ((it % 360) + 360) % 360) }
                    NumF("立ち位置から対象車線まで（m）", draft.dbl("road_distance"), Modifier.weight(1f)) { set("road_distance" to it.coerceIn(0.5, 80.0)) }
                }
                Muted("道路の方位は「上り（対象車線）の車が進む向き」。0=北、90=東、180=南、270=西。", 10)
                Text("交通量データ（道路交通センサス）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Muted("立ち位置から最も近い観測地点を自動で選びます。名前で検索して選ぶこともできます。", 10)
                var q by remember { mutableStateOf("") }; var rows by remember { mutableStateOf<List<JsonElement>?>(null) }; var busy by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it }, placeholder = { Text("路線名・区間名で検索（例 24号、柏木町）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { busy = true; scope.launch { rows = runCatching { Api.rpc("occlusion/server-fns#searchSurveyLocations", jsonOf("q" to q, "limit" to 20)).items() }.getOrDefault(emptyList()); busy = false } }, enabled = !busy) { Icon(Icons.Outlined.Search, "検索") }
                }
                if (autoBusy) Muted("立ち位置から最寄りの観測地点を探しています…", 11) else if (autoMessage.isNotBlank()) Text(autoMessage, fontSize = 11.sp, color = Brand.Warning)
                location?.let { s ->
                    Column(Modifier.fillMaxWidth().background(Brand.Orange.copy(alpha = 0.06f), RoundedCornerShape(8.dp)).padding(8.dp)) {
                        Text("${s.str("route_name")} ／ ${s.str("section_name")?.ifBlank { null } ?: "区間名なし"}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (draft.bool("survey_location_auto") == true) { val d = draft.dbl("survey_location_distance") ?: 0.0; Muted("立ち位置から自動選択${if (d > 0) "・" + (if (d >= 1000) "約%.1fkm".format(d / 1000) else "約${d.roundToInt()}m") else ""}", 10) }
                        Muted("12時間交通量 ${num(s.dbl("vol_12h_up"))}台（上り）・${num(s.dbl("vol_12h_down"))}台（下り）／車線 ${s.str("lanes") ?: "—"} ／ 旅行速度 ${s.str("travel_speed_day") ?: "—"}km/h", 10)
                    }
                }
                rows?.let { list ->
                    if (list.isEmpty()) Muted("該当する観測地点がありません", 11)
                    list.forEach { row ->
                        Column(Modifier.fillMaxWidth().clickable {
                            autoKey = ""
                            scope.launch {
                                val rd = runCatching { Logic.call("occlusion/model", "defaultRoadDistance", row, draft.str("target_lane") ?: "up").text.toDoubleOrNull() }.getOrNull()
                                OcclusionDraft.set(context, "survey_location_id" to row.str("id"), "survey_location_key" to row.str("key"), "survey_location_auto" to false, "survey_location_distance" to 0, "survey_location_data" to row.toString(),
                                    "place_name" to (draft.str("place_name")?.ifBlank { null } ?: "${row.str("route_name").orEmpty()} ${row.str("section_name").orEmpty()}".trim()), "road_distance" to (rd ?: draft.dbl("road_distance")))
                            }
                            rows = null
                        }.padding(vertical = 5.dp)) {
                            Text("${row.str("route_name")}　${row.str("section_name")}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Muted("${num(row.dbl("vol_12h_up"))}台/12h・車線${row.str("lanes") ?: "—"}・${row.str("travel_speed_day") ?: "—"}km/h", 10)
                        }
                    }
                }
            }
        }
        item { CameraControls(draft, result) { p -> set(*p) } }
    }
}

/** 立ち位置・画角・対象車線の地図（Web: OcclusionMap.jsx） */
@Composable
private fun OcclusionMap(draft: JsonObject, result: JsonElement?, onMove: (Double, Double) -> Unit) {
    val lat = draft.dbl("lat") ?: return; val lon = draft.dbl("lon") ?: return
    val azimuth = result.dbl("azimuth") ?: draft.dbl("azimuth") ?: 0.0
    val distance = result.dbl("distance")?.let { maxOf(4.0, it) } ?: 30.0
    val far = (distance * 1.8).coerceIn(40.0, 240.0)
    val half = maxOf(3.0, (result.dbl("fov.h") ?: 40.0) / 2)
    val wedge = listOf(lat to lon) + (0..10).map { destination(lat, lon, azimuth - half + half * 2 * it / 10, far) } + listOf(lat to lon)
    val bus = destination(lat, lon, azimuth, distance)
    val busBearing = result.dbl("busBearing") ?: draft.dbl("road_bearing") ?: 0.0
    val back = (azimuth + 180) % 360
    val laneW = result.dbl("laneWidth") ?: 3.25
    fun lane(lateral: Double): List<Pair<Double, Double>> { val s = destination(bus.first, bus.second, back, lateral); return listOf(destination(s.first, s.second, busBearing + 180, 70.0), destination(s.first, s.second, busBearing, 70.0)) }
    OsmMap(Modifier.fillMaxWidth().height(300.dp),
        pins = listOf(MapPin("bus", bus.first, bus.second, "バス（${distance.roundToInt()}m）", color = Color(0xFFF97316), size = 22.dp, label = "🚌"), MapPin("cam", lat, lon, "立ち位置（横 ${"%.1f".format(result.dbl("lateral") ?: draft.dbl("road_distance") ?: 6.0)}m）", color = Color(0xFFD94F2B), size = 20.dp, label = "撮")),
        lines = listOf(MapLine(wedge, Brand.Warning, 3f), MapLine(lane(0.0), Color(0xFF2563EB), 8f), MapLine(lane(laneW), Brand.MutedFg, 5f, dashed = true), MapLine(listOf(lat to lon, bus), Color(0xFFD94F2B), 4f, dashed = true)),
        center = lat to lon, zoom = 17.0, fitKey = "%.4f,%.4f".format(lat, lon), onMapClick = onMove)
    Muted("黄：画角 ${"%.1f".format(result.dbl("fov.h") ?: 0.0)}°　青：対象車線（交通の流れ ${busBearing.roundToInt()}°）　点線：隣接車線（約 ${"%.1f".format(laneW)}m 間隔）・撮影距離 ${"%.1f".format(distance)}m", 10)
}

/** 予測結果（Web: PredictionCard.jsx） */
@Composable
private fun PredictionCard(r: JsonElement?) {
    if (r == null) return
    if (r.dbl("areaRate") == null) { Panel { Text("⚠ この条件では算出できません", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Brand.Warning); Muted(r.str("reason") ?: r.list("notes").firstOrNull()?.text.orEmpty(), 11) }; return }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Muted("予測される被り面積率", 11); Text(pct(r.dbl("areaRate"), 1), fontSize = 34.sp, fontWeight = FontWeight.Bold) }
            Column(horizontalAlignment = Alignment.End) { Tag(gradeLabel(r.str("grade")), gradeColor(r.str("grade"))); Muted("被り発生率 ${pct(r.dbl("occurrenceRate"), 1)}", 11) }
        }
        Kv("撮影距離", "${"%.1f".format(r.dbl("distance") ?: 0.0)} m"); Kv("水平画角", "${"%.1f".format(r.dbl("fov.h") ?: 0.0)}°"); Kv("バス投影幅", "${"%.1f".format(r.dbl("wBus") ?: 0.0)}°")
        Kv("立ち位置の横距離", "${"%.1f".format(r.dbl("lateral") ?: 0.0)} m"); Kv("対象車線", r.dbl("busBearing")?.let { "${it.roundToInt()}°" } ?: "—"); Kv("方位角", "${r.dbl("azimuth")?.roundToInt()}°")
        Muted("ℹ ${CONFIDENCE[r.str("confidence")] ?: "—"}${if (r.str("aimMode") == "distance") "／距離指定モード" else ""}", 10)
        Text("見え方（カメラから見た向き）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        listOf("front" to "前面", "rear" to "後面", "left" to "左側面", "right" to "右側面").forEach { (k, l) ->
            val v = (r.dbl("facing.$k") ?: 0.0).toFloat()
            Row(verticalAlignment = Alignment.CenterVertically) { Text(l, fontSize = 11.sp, modifier = Modifier.width(56.dp)); LinearProgressIndicator(progress = { v.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(6.dp)); Muted("  ${(v * 100).roundToInt()}%", 10) }
        }
        Text("交通参加者別の内訳", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        val auto = r.objAt("byClass.auto"); val ped = r.objAt("byClass.pedestrian"); val bike = r.objAt("byClass.bicycle")
        Kv("自動車（公開交通量）", auto?.let { pct(it.dbl("probability"), 1) } ?: "算出なし", auto?.let { "期待 ${"%.2f".format(it.dbl("expected") ?: 0.0)} 台" }.orEmpty())
        Kv("歩行者（実測）", ped?.let { pct(it.dbl("probability"), 1) } ?: "未登録", ped?.let { "期待 ${"%.2f".format(it.dbl("expected") ?: 0.0)} 人" }.orEmpty())
        Kv("自転車（実測）", bike?.let { pct(it.dbl("probability"), 1) } ?: "未登録", bike?.let { "期待 ${"%.2f".format(it.dbl("expected") ?: 0.0)} 台" }.orEmpty())
        r.list("byClass.obstacle").forEach { ob -> Kv("固定障害物：${ob.str("name")}", "覆う ${pct(ob.dbl("cover"))}") }
        if (r.list("notes").isNotEmpty()) { Text("データの前提", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)); r.list("notes").forEach { Muted("・${it.text}", 10) } }
        Text("部位別の被りやすさ", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        r.objAt("byPart")?.forEach { (k, v) -> Kv(mapOf("front" to "前面", "rear" to "後面", "left" to "左側面", "right" to "右側面")[k] ?: k, pct(v.dbl("areaRate"), 1), gradeLabel(v.str("grade"))) }
    }
}

/** 実際の撮影結果の記録（Web: ActualForm.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActualForm(draft: JsonObject, savedId: String, result: JsonElement?, savePrediction: suspend (Int, JsonObject) -> JsonElement, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now = remember { Instant.now().atZone(ZoneId.of("Asia/Tokyo")).toLocalDateTime().toString().take(16) }
    var capturedAt by remember { mutableStateOf(now) }; var vehicle by remember { mutableStateOf("") }; var grade by remember { mutableStateOf("none") }
    var occluders by remember { mutableStateOf(setOf<String>()) }; var note by remember { mutableStateOf("") }; var photo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: throw IllegalStateException("写真を読み込めませんでした")
                photo = Api.uploadPrivateFile("occlusion.${mime.substringAfter('/')}", mime, bytes); message = "写真を添付しました（非公開ストレージに保存）"
            } catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "写真をアップロードできませんでした" } } finally { busy = false }
        }
    }
    Panel(title = "実際の撮影結果を記録") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(capturedAt, { capturedAt = it }, label = { Text("撮影日時", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(vehicle, { vehicle = it }, label = { Text("車両（車番など）", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Muted("被りの程度", 11)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("none", "slight", "partial", "mostly", "full").forEach { g -> FilterChip(grade == g, { grade = g }, label = { Text(gradeLabel(g), fontSize = 10.sp) }) } }
        if (grade != "none") { Muted("被った相手", 11); FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("auto" to "自動車", "pedestrian" to "歩行者", "bicycle" to "自転車", "obstacle" to "固定障害物").forEach { (k, l) -> FilterChip(k in occluders, { occluders = if (k in occluders) occluders - k else occluders + k }, label = { Text(l, fontSize = 10.sp) }) } } }
        OutlinedTextField(note, { note = it }, label = { Text("メモ", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { SmallButton("写真を添付", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy); Muted(if (photo.isNotBlank()) "  添付済み" else "  （任意・非公開保存）", 10) }
        Muted("予測値：被り面積率 ${pct(result.dbl("areaRate"), 1)} ／ 発生率 ${pct(result.dbl("occurrenceRate"), 1)}", 11)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton(if (busy) "記録中…" else "実績を記録", {
                if (savedId.isBlank()) { message = "先に撮影条件を保存してください"; return@SmallButton }
                busy = true; message = ""
                scope.launch {
                    try {
                        Api.rpc("occlusion/server-fns#saveActual", jsonOf("setup_id" to savedId, "setup_name" to draft.str("name"), "captured_at" to capturedAt, "vehicle" to vehicle, "target_part" to draft.str("target_part"), "occluded" to (grade != "none"), "grade" to grade,
                            "occluders" to JsonArray(occluders.map { toJson(it) }).toString(), "predicted_occurrence" to result.dbl("occurrenceRate"), "predicted_area" to result.dbl("areaRate"), "photo_uri" to photo.ifBlank { null }, "note" to note))
                        savePrediction(java.time.LocalTime.now().hour, jsonOf("azimuth" to draft.dbl("azimuth"), "focal" to draft.dbl("focal_length"), "distance" to result.dbl("distance")))
                        message = "実績を記録しました"; onSaved()
                    } catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "記録できませんでした" } } finally { busy = false }
                }
            }, enabled = !busy, primary = true)
            if (message.isNotBlank()) Muted("  $message", 11)
        }
    }
}

/** カメラの向き・レンズ・固定障害物（Web: CameraControls.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraControls(draft: JsonObject, result: JsonElement?, set: (Array<Pair<String, Any?>>) -> Unit) {
    fun s(vararg p: Pair<String, Any?>) = set(arrayOf(*p))
    val az = draft.dbl("azimuth") ?: 0.0; val rb = draft.dbl("road_bearing") ?: 0.0
    val obstacles = remember(draft.str("obstacles")) { runCatching { (Json.parse(draft.str("obstacles") ?: "[]") as JsonArray).mapNotNull { it as? JsonObject } }.getOrDefault(emptyList()) }
    fun update(list: List<JsonObject>) = s("obstacles" to JsonArray(list.filter { it.dbl("azimuth") != null }.take(12)).toString())
    Panel(title = "カメラの向き（方位角）") {
        Muted("方位角 ${az.roundToInt()}°", 11); Slider(az.toFloat(), { s("azimuth" to it.roundToInt()) }, valueRange = 0f..359f)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("front" to "前面", "side" to "側面", "rear" to "後面").forEach { (k, l) -> FilterChip(az == presetAzimuth(rb, k), { s("azimuth" to presetAzimuth(rb, k)) }, label = { Text("${l}向き", fontSize = 11.sp) }) } }
        Muted("狙い方", 11)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(draft.str("aim_mode") != "distance", { s("aim_mode" to "azimuth") }, label = { Text("方位角から距離を決める", fontSize = 11.sp) })
            FilterChip(draft.str("aim_mode") == "distance", { s("aim_mode" to "distance") }, label = { Text("距離から狙う", fontSize = 11.sp) })
        }
        if (draft.str("aim_mode") == "distance") { val d = draft.dbl("bus_distance") ?: 30.0; Muted("撮影対象までの距離 ${d.roundToInt()}m", 11); Slider(d.toFloat(), { s("bus_distance" to it.roundToInt()) }, valueRange = 5f..150f) }
        else Muted("カメラの向きと対象車線が交わる位置がバスの位置になり、撮影距離が決まります（現在 ${result.dbl("distance")?.let { "%.1f".format(it) } ?: "—"}m）。", 10)
        Text("レンズと画角", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        val focal = draft.dbl("focal_length") ?: 50.0
        Muted("焦点距離（35mm判換算） ${focal.roundToInt()}mm", 11); Slider(focal.toFloat(), { s("focal_length" to it.roundToInt()) }, valueRange = 14f..400f)
        Muted("水平画角 ${result.dbl("fov.h")?.let { "%.1f".format(it) } ?: "—"}°　垂直 ${result.dbl("fov.v")?.let { "%.1f".format(it) } ?: "—"}°", 11)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectField(SENSOR_LABEL, draft.str("sensor") ?: "ff", { s("sensor" to it) }, label = "センサー")
            SelectField(listOf("landscape" to "横構図", "portrait" to "縦構図"), draft.str("orientation") ?: "landscape", { s("orientation" to it) }, label = "構図")
            SelectField(PARTS, draft.str("target_part") ?: "front", { s("target_part" to it) }, label = "撮影対象")
        }
        NumF("カメラの高さ（m）", draft.dbl("camera_height"), Modifier.width(160.dp)) { s("camera_height" to it.coerceIn(0.0, 5.0)) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("固定障害物（電柱・標識など）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            SmallButton("追加", { update(obstacles + jsonOf("name" to "電柱", "azimuth" to az.roundToInt(), "width_deg" to 8, "distance" to 8)) }, icon = Icons.Outlined.Add)
        }
        if (obstacles.isEmpty()) Muted("登録した障害物だけを確定的に扱います。無い場合は交通による被りだけを算出します。", 10)
        obstacles.forEachIndexed { i, ob ->
            fun patch(k: String, v: Any?) = update(obstacles.mapIndexed { j, o -> if (j == i) JsonObject(o + (k to toJson(v))) else o })
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ob.str("name").orEmpty(), { patch("name", it) }, placeholder = { Text("名称", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { update(obstacles.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Outlined.Delete, "削除") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumF("方位(°)", ob.dbl("azimuth"), Modifier.weight(1f)) { patch("azimuth", it.coerceIn(0.0, 359.0)) }; NumF("幅(°)", ob.dbl("width_deg"), Modifier.weight(1f)) { patch("width_deg", it.coerceIn(0.5, 120.0)) }; NumF("距離(m)", ob.dbl("distance"), Modifier.weight(1f)) { patch("distance", it.coerceIn(0.5, 300.0)) }
            }
        }
    }
}

/** 時間帯別の被り率（Web: occlusion.hourly.jsx + HourlyTable.jsx） */
@Composable
fun OcclusionHourlyScreen() {
    val draft = OcclusionDraft.value
    val location = rememberSurveyLocation(draft)
    val rows = produceState(emptyList<JsonElement>(), draft, location) { value = Logic.call("occlusion/model", "predictHours", jsonOf("setup" to draft, "location" to location, "observations" to JsonObject(emptyMap()))).items() }.value
    val measured = rows.filter { it.dbl("areaRate") != null }
    OcclusionScaffold("/occlusion/hourly", "時間帯別の被り率", "${draft.str("name")?.ifBlank { null } ?: "現在の撮影条件"}${location?.let { "／${it.str("route_name")} ${it.str("section_name")}" } ?: ""}") {
        if (location == null) item { Panel { Muted("交通量の観測地点が未選択です。「解析」画面で地点を選ぶと時間帯別の値を算出します。", 12) } }
        if (measured.isNotEmpty()) item {
            val best = measured.minBy { it.dbl("areaRate")!! }; val worst = measured.maxBy { it.dbl("areaRate")!! }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { Panel { Muted("最も被りにくい時間帯", 10); Text("${best.int("hour")}時台", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Brand.Success); Muted("被り面積率 ${pct(best.dbl("areaRate"), 1)}", 10) } }
                Column(Modifier.weight(1f)) { Panel { Muted("最も被りやすい時間帯", 10); Text("${worst.int("hour")}時台", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Brand.Destructive); Muted("被り面積率 ${pct(worst.dbl("areaRate"), 1)}", 10) } }
            }
        }
        item {
            Panel {
                rows.forEach { row ->
                    val v = row.dbl("areaRate")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text("${row.int("hour")}時", fontSize = 12.sp, modifier = Modifier.width(44.dp))
                        LinearProgressIndicator(progress = { (v ?: 0.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(6.dp)); Muted("  ${pct(v, 1)}  ", 11)
                        Tag(if (v == null) "算出外" else gradeLabel(row.str("grade")), gradeColor(row.str("grade")))
                    }
                }
                Muted("時間帯別交通量は道路交通センサスの実測（7〜19時）。算出外の時間帯は公開データが無いため、推測値は表示していません。", 10)
            }
        }
    }
}

/** 撮影角度・画角の比較（Web: occlusion.compare.jsx） */
@Composable
fun OcclusionCompareScreen() {
    val context = LocalContext.current
    val nav = LocalNav.current
    val draft = OcclusionDraft.value
    val location = rememberSurveyLocation(draft)
    var hour by remember { mutableStateOf(8) }
    val current = rememberPrediction(draft, location, null, hour)
    val candidates = produceState(emptyList<JsonElement>(), draft, location, hour) { value = Logic.call("occlusion/model", "buildCandidates", jsonOf("setup" to draft, "location" to location, "observations" to JsonObject(emptyMap()), "hour" to hour)).items() }.value
    OcclusionScaffold("/occlusion/compare", "撮影角度・画角の比較", "同じ立ち位置のまま、方位角・焦点距離・撮影対象を変えた組み合わせを総当たりで比べ、最も被りにくい条件を探します。") {
        item { HourChips(hour) { hour = it } }
        item { Panel(title = "現在の条件") { Text("${PART_LABEL[draft.str("target_part")] ?: "前面"}・${draft.dbl("focal_length")?.roundToInt()}mm・方位角${draft.dbl("azimuth")?.roundToInt()}°", fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted("被り面積率 ${pct(current.dbl("areaRate"), 1)}（${gradeLabel(current.str("grade"))}）", 11) } }
        if (location == null) item { Panel { Muted("交通量の観測地点が未選択です。「解析」画面で地点を選ぶと比較できます。", 12) } }
        candidates.take(12).forEachIndexed { i, c ->
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${i + 1}. ${PART_LABEL[c.str("part")] ?: c.str("part")}・${c.dbl("focal")?.roundToInt()}mm・方位角${c.dbl("azimuth")?.roundToInt()}°", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Muted("距離 ${"%.1f".format(c.dbl("distance") ?: 0.0)}m ／ 面積率 ${pct(c.dbl("areaRate"), 1)} ／ 発生率 ${pct(c.dbl("occurrenceRate"), 1)}", 10)
                        }
                        Tag(gradeLabel(c.str("grade")), gradeColor(c.str("grade")))
                        SmallButton("適用", { OcclusionDraft.set(context, "azimuth" to c.dbl("azimuth"), "focal_length" to c.dbl("focal"), "target_part" to c.str("part"), "aim_mode" to "azimuth", "road_distance" to (c.dbl("lateral")?.takeIf { it > 0 } ?: draft.dbl("road_distance"))); nav.go("/occlusion") }, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        if (candidates.isEmpty() && location != null) item { Muted("この地点では比較できる組み合わせが見つかりませんでした。方位角や立ち位置を変えてお試しください。", 12) }
        item { Muted("比較は方位角15°刻み・焦点距離35/50/85/135/200mm・6種類の撮影対象の組み合わせです。立ち位置は動かしていません。", 10) }
    }
}

/** 撮影地点ランキング（Web: occlusion.ranking.jsx） */
@Composable
fun OcclusionRankingScreen() {
    val context = LocalContext.current
    val nav = LocalNav.current
    var hour by remember { mutableStateOf(8) }
    val load = rememberLoad {
        val setups = Api.rpc("occlusion/server-fns#listShootingSetups", jsonOf()).items()
        val ids = setups.mapNotNull { it.str("survey_location_id")?.takeIf { s -> s.isNotBlank() } }.distinct().take(40)
        setups to (if (ids.isEmpty()) emptyList() else Api.rpc("occlusion/server-fns#getSurveyLocations", jsonOf("ids" to ids)).items())
    }
    val ranked = produceState<List<Triple<JsonElement, JsonElement?, JsonElement>>>(emptyList(), load.data, hour) {
        val (setups, locations) = load.data ?: return@produceState
        val byId = locations.associateBy { it.str("id") }
        value = setups.mapNotNull { s -> val loc = byId[s.str("survey_location_id")]; val r = Logic.call("occlusion/model", "predictOcclusion", jsonOf("setup" to OcclusionDraft.fromSetup(s), "location" to loc, "observations" to JsonObject(emptyMap()), "hour" to hour)); if (r.dbl("areaRate") != null) Triple(s, loc, r) else null }.sortedBy { it.third.dbl("areaRate") }
    }.value
    OcclusionScaffold("/occlusion/ranking", "撮影地点ランキング", "保存した撮影条件を同じ時間帯で比べ、被りにくい地点・条件の順に並べます。") {
        item { HourChips(hour) { hour = it } }
        val setups = load.data?.first
        if (setups == null) item { Muted(load.error ?: "読み込み中…", 12) }
        else if (setups.isEmpty()) item { Panel { Muted("保存された撮影条件がありません。「解析」画面で条件を作成して保存してください。", 12) } }
        else if (ranked.isEmpty()) item { Panel { Muted("交通量データのある地点が無いため、ランキングを作成できません。各条件に観測地点を設定してください。", 12) } }
        ranked.forEachIndexed { i, (setup, loc, r) ->
            item(key = setup.str("id")) {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${i + 1}. ${setup.str("name")}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Muted("${loc?.let { "${it.str("route_name")} ${it.str("section_name")}" } ?: "交通量データ未設定"}／${PART_LABEL[setup.str("target_part")] ?: "前面"}・${setup.dbl("focal_length")?.roundToInt()}mm", 10)
                            Muted("距離 ${"%.1f".format(r.dbl("distance") ?: 0.0)}m ／ 面積率 ${pct(r.dbl("areaRate"), 1)}", 10)
                        }
                        Tag(gradeLabel(r.str("grade")), gradeColor(r.str("grade")))
                        SmallButton("開く", { OcclusionDraft.replace(context, OcclusionDraft.fromSetup(setup)); OcclusionDraft.savedId = setup.str("id").orEmpty(); nav.go("/occlusion") }, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

/** 予測と実績の検証（Web: occlusion.verify.jsx） */
@Composable
fun OcclusionVerifyScreen() {
    val load = rememberLoad { Api.rpc("occlusion/server-fns#listActuals", jsonOf()).items() to Api.rpc("occlusion/server-fns#listPredictions", jsonOf()).items() }
    val actuals = load.data?.first
    val stats = produceState<JsonElement?>(null, actuals) { value = Logic.call("occlusion/model", "accuracyOf", JsonArray(actuals ?: emptyList())) }.value
    OcclusionScaffold("/occlusion/verify", "予測と実績の検証", "実際に撮影した結果と予測を突き合わせ、予測の当たりやすさを確認します。件数が少ないうちは参考値として扱ってください。") {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("記録された実績" to (stats.str("total") ?: "—"), "予測と一致した件数" to (stats.str("hit") ?: "—"), "一致率" to pct(stats.dbl("rate"))).forEach { (l, v) -> Column(Modifier.weight(1f)) { Panel { Muted(l, 10); Text(v, fontSize = 20.sp, fontWeight = FontWeight.Bold) } } }
            }
            Muted(if (stats.bool("reliable") == true) "10件以上のため参考にできます" else "件数が少ないため参考値", 10)
        }
        stats.dbl("bias")?.let { bias -> item { Panel { Text("予測と実績の偏り：" + if (bias > 0.05) "予測が実際より ${pct(bias, 1)} 高めに出ています" else if (bias < -0.05) "予測が実際より ${pct(-bias, 1)} 低めに出ています" else "ほぼ偏りはありません", fontSize = 12.sp) } } }
        if (actuals == null) item { Muted(load.error ?: "読み込み中…", 12) }
        else if (actuals.isEmpty()) item { Panel { Muted("実績の記録がありません。「解析」画面の「実際の撮影結果を記録」から追加できます。", 12) } }
        actuals.orEmpty().forEach { a ->
            item(key = a.str("id")) {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(a.str("setup_name") ?: a.str("setup_id").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted("${a.str("captured_at")}　${a.str("vehicle")?.ifBlank { null } ?: "車両未記入"}", 10) }
                        Muted("予測 ${pct(a.dbl("predicted_area"), 1)}  ", 11); Tag("実績 ${gradeLabel(a.str("grade"))}", gradeColor(a.str("grade")))
                    }
                    a.str("note")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
                }
            }
        }
        val predictions = load.data?.second.orEmpty()
        if (predictions.isNotEmpty()) item { Panel(title = "保存された予測（直近）") { predictions.take(12).forEach { p -> Row { Text("${p.str("setup_name") ?: p.str("setup_id")}${p.int("hour")?.let { "　${it}時台" } ?: ""}", fontSize = 11.sp, modifier = Modifier.weight(1f)); Muted(pct(p.dbl("area_rate"), 1), 11) } } } }
    }
}

/** 交通量データ（Web: occlusion.data.jsx） */
@Composable
fun OcclusionDataScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val load = rememberLoad { Api.rpc("occlusion/server-fns#getCensusStatus", jsonOf()) }
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    val status = load.data; val source = status.list("sources").firstOrNull()
    OcclusionScaffold("/occlusion/data", "交通量データ", "国土交通省「全国道路・街路交通情勢調査（道路交通センサス）」の奈良県データを取り込みます。値の生成はせず、公開値のみを使います。") {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("収録地点数" to (status.str("total") ?: "—"), "時間帯別あり" to (status.str("withHourly") ?: "—"), "最終取り込み" to (source.long("imported_at")?.let { BusFormat.dateTime(it) } ?: "未取り込み")).forEach { (l, v) -> Column(Modifier.weight(1f)) { Panel { Muted(l, 10); Text(v, fontSize = 14.sp, fontWeight = FontWeight.Bold) } } }
            }
        }
        item {
            Panel(title = "データの出典") {
                Muted("出典：国土交通省「令和3年度 全国道路・街路交通情勢調査」（一般交通量調査）奈良県データ。交通量・旅行速度・車線数・幅員は同調査の公開値です。歩行者・自転車の交通量は同調査に収録されていないため、本アプリでは実測登録がある場合のみ評価に含めます。", 11)
                source.str("page_url")?.let { url -> SmallButton("公開ページを開く ↗", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallButton(if (busy) "取り込み中…" else "公開データを取り込む", {
                        busy = true; message = ""
                        scope.launch {
                            try { val res = Api.rpc("occlusion/server-fns#importCensus", jsonOf()); message = if (res.bool("ok") == true) "取り込み完了：${res.str("parsed")}地点（新規${res.str("created")}・更新${res.str("updated")}）" else "取り込みに失敗しました：${res.str("error")}"; load.reload() }
                            catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "取り込みに失敗しました" } } finally { busy = false }
                        }
                    }, enabled = !busy, icon = Icons.Outlined.Download, primary = true)
                    Muted("  管理者のみ実行できます", 10)
                }
                if (message.isNotBlank()) Muted(message, 11)
            }
        }
        val logs = status.list("logs")
        if (logs.isNotEmpty()) item {
            Panel(title = "取り込み履歴") {
                logs.forEach { log ->
                    val ok = log.str("status") == "success"
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Column(Modifier.weight(1f)) { Text(BusFormat.dateTime(log.long("started_at")), fontSize = 11.sp); Muted(if (ok) "解析${log.int("parsed") ?: 0}行／新規${log.int("created") ?: 0}・更新${log.int("updated") ?: 0}" else log.str("error") ?: "失敗", 10) }
                        Tag(if (ok) "成功" else "失敗", if (ok) Brand.Success else Brand.Destructive)
                    }
                }
            }
        }
        item { Box(Modifier.fillMaxWidth().background(Brand.Warning.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(8.dp)) { Text("⚠ 道路交通センサスは5年ごとの調査です。値が古い場合は「参考」として扱い、撮影当日の実測（歩行者・自転車）や現地確認と併用してください。", fontSize = 11.sp, color = Brand.Warning) } }
    }
}
