package app.narabuspulse.feature.discover

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Json
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
import app.narabuspulse.core.toJson
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.sim3d.Sim3dHandoff
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberConfirmer
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

private val CONFIDENCE = mapOf("measured" to "実測データに基づく", "reference" to "一部は公開データの参考値", "insufficient" to "データ不足")
private val SAFETY = mapOf("sidewalk" to ("歩道あり（OSM）" to Brand.Success), "unverified" to ("安全性未確認" to Brand.Warning), "caution" to ("注意が必要" to Brand.Destructive))
private val LIGHT = mapOf("front" to "逆光", "back" to "順光", "side" to "斜光", "night" to "夜間・太陽低い")
private val ANALYSIS_KEYS = listOf("components", "light", "road", "obstacles", "elevation", "relief", "slopePct", "cameraAzimuth", "buildings", "frontHours")
private fun hours(list: List<JsonElement>) = if (list.isEmpty()) "—" else list.joinToString("・") { "${it.text}時" }
private fun tone(score: Double?): Pair<String, Color> = when { score == null -> "未評価" to Brand.MutedFg; score >= 75 -> "有望" to Brand.Success; score >= 55 -> "検討可" to INFO; else -> "条件次第" to Brand.Warning }
private fun n(v: Double?) = v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
private fun field(e: JsonElement?, key: String): JsonElement? = when (val v = (e as? JsonObject)?.get(key)) { is JsonPrimitive -> runCatching { Json.parse(v.content) }.getOrNull(); else -> v }
private fun stringify(e: JsonElement?, keys: List<String>) = JsonObject(keys.mapNotNull { k -> (e as? JsonObject)?.get(k)?.let { k to it } }.toMap()).toString()

private fun simDraft(name: String, lat: Double?, lon: Double?, analysis: JsonElement?, safety: String?, notes: String) = jsonOf("place_name" to name, "lat" to lat, "lon" to lon, "azimuth" to (analysis.dbl("cameraAzimuth") ?: 180.0),
    "road_distance" to (analysis.dbl("road.distance") ?: 8.0), "bus_distance" to 30, "target_part" to "front", "stand_type" to (if (safety == "sidewalk") "sidewalk" else "outside_road"), "title" to "$name 候補", "notes" to notes)

private suspend fun analyze(key: String?, name: String?, lat: Double?, lon: Double?, pass: Int, routes: List<String>) =
    Api.rpc("discover/server-fns#analyzeDiscoveryCandidate", jsonOf("key" to key.orEmpty(), "name" to name, "lat" to lat, "lon" to lon, "passCount" to pass, "routes" to routes.take(10), "withAi" to true))

/** 撮影地新規開拓AI（Web: discover.index.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen() {
    val nav = LocalNav.current; val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }; var route by remember { mutableStateOf("") }; var minPass by remember { mutableStateOf("0") }; var onlyUnregistered by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<JsonElement?>(null) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }; var message by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<JsonElement?>(null) }; var analysis by remember { mutableStateOf<JsonElement?>(null) }
    var analyzing by remember { mutableStateOf(false) }; var saving by remember { mutableStateOf(false) }; var savedKey by remember { mutableStateOf("") }
    var showLight by remember { mutableStateOf(false) }
    fun run() {
        busy = true; error = ""; message = ""
        scope.launch {
            try {
                val res = Api.rpc("discover/server-fns#searchDiscoveryCandidates", jsonOf("q" to q, "route" to route, "minPass" to (minPass.toIntOrNull() ?: 0).coerceIn(0, 999), "onlyUnregistered" to onlyUnregistered, "limit" to 120))
                result = res
                if (selected != null && res.items("rows").none { it.str("key") == selected.str("key") }) { selected = null; analysis = null }
            } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "候補を取得できませんでした" } } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { run() }
    fun pick(row: JsonElement) { selected = row; analysis = null; message = ""; savedKey = "" }
    val rows = result.items("rows"); val sel = selected; val a = analysis
    ScreenList {
        item { Heading("撮影地新規開拓AI", "既存の停留所座標・運行履歴・登録済み撮影地から未開拓の候補を抽出し、標高・道路・障害物・順光の時間帯を実際に取得して評価します。候補は保存しても確定登録にはなりません。"); SmallButton("保存した候補", { nav.go("/discover/saved") }) }
        if (error.isNotBlank()) item { Text(error, fontSize = 12.sp, color = Brand.Destructive) }
        if (message.isNotBlank()) item { Text(message, fontSize = 12.sp, color = Brand.Success) }
        item {
            Panel(title = "検索条件") {
                OutlinedTextField(q, { q = it }, label = { Text("停留所名・系統の一部", fontSize = 10.sp) }, placeholder = { Text("例 奈良駅 / 柳生 / 92", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SelectField(listOf("" to "すべて") + result.list("routeOptions").map { it.text to it.text }, route, { route = it }, label = "系統")
                    OutlinedTextField(minPass, { minPass = it }, label = { Text("90日間の観測回数（以上）", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(onlyUnregistered, { onlyUnregistered = it }); Text("登録済みの撮影地（150m以内）を除外する", fontSize = 12.sp) }
                SmallButton(if (busy) "抽出中…" else "候補を抽出", { run() }, enabled = !busy, icon = Icons.Outlined.Search, primary = true)
                result.objAt("availability")?.let { av -> Muted("使用データ: 停留所座標 ${av.int("stops") ?: 0}件 / 運行記録 ${av.int("runs") ?: 0}件 / 登録済み撮影地 ${av.int("registered") ?: 0}件", 10); av.str("note")?.let { Muted(it, 10) } }
            }
        }
        item {
            val pts = rows.filter { it.dbl("lat") != null && it.dbl("lon") != null }
            OsmMap(Modifier.fillMaxWidth().height(290.dp), pins = pts.map { r -> val active = r.str("key") == sel.str("key"); MapPin(r.str("key").orEmpty(), r.dbl("lat")!!, r.dbl("lon")!!, r.str("name").orEmpty(), r.dbl("quick.score")?.let { "${n(it)}点・暫定" }.orEmpty(), color = if (active) Color(0xFF3441B3) else Color(0xFFDB7100), size = if (active) 18.dp else 12.dp) },
                center = sel?.let { (it.dbl("lat") ?: 34.6837) to (it.dbl("lon") ?: 135.827) } ?: (34.6837 to 135.827), zoom = if (sel != null) 15.0 else 11.0, fitKey = "${pts.size}|${sel.str("key")}",
                onPinClick = { p -> rows.firstOrNull { it.str("key") == p.id }?.let(::pick) })
        }
        item {
            if (sel == null) Panel { Muted("候補地点を選ぶと、路線・光線・地形・安全上の注意を表示します。解析すると標高・道路・障害物を実際に取得します。", 12) }
            else Panel {
                val score = a.dbl("score"); val quick = sel.dbl("quick.score"); val (_, color) = tone(score ?: quick)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(sel.str("name").orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.Bold); Muted("${"%.5f".format(sel.dbl("lat") ?: 0.0)}, ${"%.5f".format(sel.dbl("lon") ?: 0.0)} / 出典: ${sel.str("source").orEmpty()}", 10) }
                    Tag(score?.let { "${n(it)}点" } ?: quick?.let { "暫定 ${n(it)}点" } ?: "未評価", color)
                }
                KeyValue("観測回数（90日）", "${sel.int("passCount") ?: 0}回"); KeyValue("上り/下り", "${sel.int("up") ?: 0} / ${sel.int("down") ?: 0}")
                KeyValue("系統", sel.list("routes").take(4).joinToString("・") { it.text }); KeyValue("行先", sel.list("destinations").take(2).joinToString("・") { it.text })
                KeyValue("順光の時間", hours(a.list("frontHours").ifEmpty { sel.list("lightHours") })); KeyValue("信頼度", CONFIDENCE[a.str("confidence") ?: sel.str("confidence")])
                val (st, sc) = SAFETY[a.str("safety") ?: "unverified"] ?: SAFETY.getValue("unverified")
                Text("⚠ ${a.str("safety_note") ?: st}", fontSize = 11.sp, color = sc)
                sel.objAt("registeredNear")?.let { Muted("登録済みの撮影地「${it.str("name")}」から約${it.int("distance")}m。重複の可能性があります。", 11) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallButton(if (analyzing) "解析中…" else if (a != null) "再解析" else "この地点を解析", {
                        analyzing = true; error = ""
                        scope.launch { try { analysis = analyze(sel.str("key"), sel.str("name"), sel.dbl("lat"), sel.dbl("lon"), sel.int("passCount") ?: 0, sel.list("routes").map { it.text }) } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "解析できませんでした" } } finally { analyzing = false } }
                    }, enabled = !analyzing, icon = Icons.Outlined.AutoAwesome, primary = a == null)
                    SmallButton(if (savedKey == sel.str("key")) "保存済み" else if (saving) "保存中…" else "候補を保存", {
                        saving = true; error = ""
                        scope.launch {
                            try {
                                val base = mutableMapOf<String, Any?>("key" to sel.str("key"), "name" to sel.str("name"), "lat" to sel.dbl("lat"), "lon" to sel.dbl("lon"), "source" to sel.str("source"),
                                    "stops" to JsonArray(listOf(jsonOf("name" to sel.str("name"), "codes" to sel.list("codes"), "distance" to 0))).toString(), "routes" to JsonArray(sel.list("routes")).toString(), "destinations" to JsonArray(sel.list("destinations")).toString(),
                                    "pass_count" to (sel.int("passCount") ?: 0), "directions" to jsonOf("up" to (sel.int("up") ?: 0), "down" to (sel.int("down") ?: 0)).toString(), "status" to "saved", "saved_at" to System.currentTimeMillis())
                                if (a != null) {
                                    base += mapOf("analysis" to stringify(a, ANALYSIS_KEYS - "frontHours" + "cached"), "score" to a.dbl("score"), "confidence" to a.str("confidence"), "safety" to a.str("safety"), "safety_note" to a.str("safety_note"),
                                        "missing" to JsonArray(a.list("missing")).toString(), "sources" to JsonArray(a.list("sources")).toString(), "ai_comment" to a.str("ai_comment").orEmpty(), "analyzed_at" to a.long("analyzedAt"))
                                    a.items("duplicates").takeIf { it.isNotEmpty() }?.let { d -> base["note"] = "登録済み撮影地と近接: ${d.joinToString("・") { it.str("name").orEmpty() }}" }
                                }
                                Api.rpc("discover/server-fns#saveDiscoveryCandidate", jsonOf("candidate" to toJson(base.filterValues { it != null })))
                                savedKey = sel.str("key").orEmpty(); message = "候補として保存しました。保存済み一覧で現地調査・撮影の管理ができます"
                            } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "保存できませんでした" } } finally { saving = false }
                        }
                    }, enabled = !saving && savedKey != sel.str("key"), icon = Icons.Outlined.Save)
                    SmallButton("3Dで確認", { Sim3dHandoff.pending = simDraft(sel.str("name").orEmpty(), sel.dbl("lat"), sel.dbl("lon"), a, a.str("safety"), "撮影地新規開拓AIの候補から作成"); nav.go("/sim3d") }, icon = Icons.Outlined.ViewInAr)
                }
                Muted("解析は国土地理院の標高データとOpenStreetMapの道路・建物・障害物を取得します（失敗時は未取得と表示）。保存しても確定登録にはなりません。", 10)
            }
        }
        if (sel != null && a != null) {
            item {
                Panel(title = "評価の内訳") {
                    a.items("components").forEach { c -> Row(Modifier.padding(vertical = 2.dp)) { Column(Modifier.weight(1f)) { Text(c.str("label").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted(c.str("detail").orEmpty(), 10) }; Muted(c.dbl("score")?.let { "${it.roundToInt()}/100" } ?: "算出不可", 11) } }
                    a.objAt("road")?.let { r -> Muted("道路: ${r.str("name") ?: r.str("type")} / 幅員${r.str("width") ?: "未記載"}m / 車線${r.str("lanes") ?: "未記載"} / 中心まで${r.str("distance")}m${if (r.bool("bridge") == true) " / 橋" else ""}${if (r.bool("tunnel") == true) " / トンネル" else ""} / カメラ方位 ${a.str("cameraAzimuth")}°", 11) }
                    Muted("標高 ${a.str("elevation") ?: "未取得"}m / 起伏 ${a.str("relief") ?: "未取得"}m / 60m以内の障害物 ${a.int("obstacles.total") ?: 0}件（高さ4m以上 ${a.int("obstacles.tall") ?: 0}件）/ 建物 ${a.str("buildings") ?: "0"}件", 11)
                    Row(Modifier.fillMaxWidth().clickable { showLight = !showLight }.padding(vertical = 6.dp)) { Text("時間帯ごとの光線", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Muted(if (showLight) "閉じる" else "開く", 11) }
                    if (showLight) a.items("light").forEach { l -> Muted("${l.str("hour")}時　方位 ${l.str("sunAzimuth") ?: "—"}°　高度 ${l.str("sunAltitude") ?: "—"}°　${LIGHT[l.str("state")] ?: l.str("state").orEmpty()}", 11) }
                }
            }
            a.str("ai_comment")?.takeIf { it.isNotBlank() }?.let { c -> item { Panel(title = "構図・アクセスの説明") { Text(c, fontSize = 12.sp, lineHeight = 18.sp) } } }
            item {
                Panel(title = "情報源と未確認項目") {
                    a.items("sources").forEach { s -> Muted("・${s.str("name")} — ${s.str("detail")}（取得 ${BusFormat.dateTime(s.long("at"))}）${if (s.bool("ok") == false) "・取得失敗" else ""}", 10) }
                    a.list("missing").takeIf { it.isNotEmpty() }?.let { m -> Text("未確認・未取得", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.Warning); m.forEach { Muted("・${it.text}", 10) } }
                    a.items("duplicates").takeIf { it.isNotEmpty() }?.let { d -> Muted("登録済み撮影地と近接: ${d.joinToString("・") { "${it.str("name")}（${it.int("distance")}m）" }}", 10) }
                }
            }
        }
        item { Text("候補 ${rows.size}件${if (busy) "（取得中…）" else ""}", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        if (rows.isEmpty() && !busy) item { Panel { Muted("条件に合う候補がありません。条件をゆるめるか、データ収集で運行履歴を蓄積してください。", 12) } }
        rows.forEach { row ->
            item(key = "c-${row.str("key")}") {
                val active = row.str("key") == sel.str("key"); val (tl, tc) = tone(row.dbl("quick.score"))
                Panel(modifier = Modifier.clickable { pick(row) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("📍 ${row.str("name")}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (active) Brand.Orange else Color.Unspecified, modifier = Modifier.weight(1f)); Tag(row.dbl("quick.score")?.let { "${n(it)}点" } ?: tl, tc) }
                    Muted("観測 ${row.int("passCount") ?: 0}回 / 系統 ${row.list("routes").take(3).joinToString("・") { it.text }.ifBlank { "—" }}", 10)
                    Muted("順光 ${hours(row.list("lightHours"))}${row.objAt("registeredNear")?.let { " / 登録済み「${it.str("name")}」まで${it.int("distance")}m" } ?: ""}", 10)
                }
            }
        }
    }
}

private val SAVED_FILTERS = listOf("all" to "すべて", "favorite" to "お気に入り", "surveyed" to "現地調査済み", "shot" to "撮影済み", "unsurveyed" to "未調査", "mountain" to "山間部", "flat" to "起伏が少ない")
private val STATUS = listOf("candidate" to "候補", "saved" to "保存済み", "surveyed" to "現地調査済み", "shot" to "撮影済み")

/** 保存した撮影候補（Web: discover.saved.jsx + SavedCandidateCard.jsx） */
@Composable
fun DiscoverSavedScreen() {
    val nav = LocalNav.current; val scope = rememberCoroutineScope(); val confirm = rememberConfirmer()
    var rows by remember { mutableStateOf<List<JsonElement>?>(null) }; var error by remember { mutableStateOf("") }; var filter by remember { mutableStateOf("all") }; var busyId by remember { mutableStateOf("") }
    suspend fun load() { try { val r = Api.rpc("discover/server-fns#listDiscoveryCandidates", jsonOf()); rows = (r as? JsonArray)?.toList() ?: r.items("rows") } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "一覧を取得できませんでした" } } }
    LaunchedEffect(Unit) { load() }
    fun act(id: String, fallback: String, block: suspend () -> Unit) { busyId = id; error = ""; scope.launch { try { block(); load() } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { fallback } } finally { busyId = "" } } }
    fun patch(row: JsonElement, body: JsonObject) = act(row.str("id").orEmpty(), "更新できませんでした") { Api.rpc("discover/server-fns#updateDiscoveryCandidate", jsonOf("id" to row.str("id"), "patch" to body)) }
    val filtered = rows.orEmpty().filter { r ->
        val a = field(r, "analysis")
        when (filter) { "favorite" -> r.bool("favorite") == true; "surveyed" -> r.str("status") in listOf("surveyed", "shot"); "shot" -> r.str("status") == "shot"; "unsurveyed" -> r.str("status") in listOf("candidate", "saved")
            "mountain" -> a != null && ((a.dbl("elevation") ?: 0.0) >= 200 || (a.dbl("relief") ?: 0.0) >= 20); "flat" -> a != null && (a.dbl("relief") ?: 99.0) < 8; else -> true }
    }
    ScreenList {
        item { Heading("保存した撮影候補", "AIが見つけた候補は、ここで現地調査・撮影の進み具合を管理します。保存しただけでは確定登録されません。"); SmallButton("← 候補を探す", { nav.go("/discover") }) }
        if (error.isNotBlank()) item { Text(error, fontSize = 12.sp, color = Brand.Destructive) }
        item { ChipRow(SAVED_FILTERS, filter, { filter = it }) }
        if (rows == null) item { Muted("読み込み中…", 12) }
        if (rows != null && filtered.isEmpty()) item { Panel { Muted("該当する候補がありません。「候補を探す」から抽出して保存できます。", 12) } }
        filtered.forEach { row ->
            item(key = "s-${row.str("id")}") {
                SavedCard(row, busyId == row.str("id"), { patch(row, it) },
                    { confirm.ask("「${row.str("name")}」を削除しますか？") { act(row.str("id").orEmpty(), "削除できませんでした") { Api.rpc("discover/server-fns#deleteDiscoveryCandidate", jsonOf("id" to row.str("id"))) } } },
                    { Sim3dHandoff.pending = simDraft(row.str("name").orEmpty(), row.dbl("lat"), row.dbl("lon"), field(row, "analysis"), row.str("safety"), row.str("note").orEmpty()); nav.go("/sim3d") },
                    {
                        act(row.str("id").orEmpty(), "再分析できませんでした") {
                            val res = analyze(row.str("key"), row.str("name"), row.dbl("lat"), row.dbl("lon"), row.int("pass_count") ?: 0, (field(row, "routes") as? JsonArray).orEmpty().map { it.text })
                            Api.rpc("discover/server-fns#updateDiscoveryCandidate", jsonOf("id" to row.str("id"), "patch" to jsonOf("analysis" to stringify(res, ANALYSIS_KEYS), "score" to res.dbl("score"), "confidence" to res.str("confidence"), "safety" to res.str("safety"), "safety_note" to res.str("safety_note"),
                                "missing" to JsonArray(res.list("missing")).toString(), "sources" to JsonArray(res.list("sources")).toString(), "ai_comment" to res.str("ai_comment").orEmpty(), "analyzed_at" to res.long("analyzedAt"))))
                        }
                    })
            }
        }
        if (busyId.isNotBlank()) item { Muted("更新中…", 11) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedCard(row: JsonElement, busy: Boolean, onPatch: (JsonObject) -> Unit, onDelete: () -> Unit, onSim: () -> Unit, onReanalyze: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val uri = LocalUriHandler.current
    var note by remember(row.str("note")) { mutableStateOf(row.str("note").orEmpty()) }
    var photoUrl by remember { mutableStateOf("") }; var uploading by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
    val analysis = field(row, "analysis"); val missing = (field(row, "missing") as? JsonArray).orEmpty()
    LaunchedEffect(row.str("photo_uri")) { photoUrl = row.str("photo_uri")?.takeIf { it.isNotBlank() }?.let { runCatching { Api.signedUrl(it) }.getOrNull() }.orEmpty() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        uploading = true; error = ""
        scope.launch {
            try { val mime = context.contentResolver.getType(picked) ?: "image/jpeg"; val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(picked)?.use { it.readBytes() } } ?: return@launch
                onPatch(jsonOf("photo_uri" to Api.uploadPrivateFile("candidate.${mime.substringAfter('/')}", mime, bytes))) }
            catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "写真を登録できませんでした" } } finally { uploading = false }
        }
    }
    Panel {
        val (_, tc) = tone(row.dbl("score"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("${if (row.bool("favorite") == true) "★ " else ""}${row.str("name")}", fontSize = 14.sp, fontWeight = FontWeight.Bold); Muted("${"%.5f".format(row.dbl("lat") ?: 0.0)}, ${"%.5f".format(row.dbl("lon") ?: 0.0)} / 観測 ${row.int("pass_count") ?: 0}回", 10) }
            Tag(row.dbl("score")?.let { "${n(it)}点" } ?: "未評価", tc)
        }
        val (st, sc) = SAFETY[row.str("safety") ?: "unverified"] ?: SAFETY.getValue("unverified")
        Text(row.str("safety_note")?.ifBlank { null } ?: st, fontSize = 11.sp, color = sc)
        Muted("順光 ${hours(analysis.list("frontHours"))} / 信頼度 ${CONFIDENCE[row.str("confidence")] ?: "—"}${row.long("analyzed_at")?.let { " / 解析 ${BusFormat.dateTime(it)}" } ?: " / 未解析"}", 10)
        row.str("ai_comment")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp, lineHeight = 16.sp) }
        if (missing.isNotEmpty()) Muted("未確認: ${missing.take(3).joinToString(" / ") { it.text }}", 10)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SelectField(STATUS, row.str("status") ?: "saved", { onPatch(jsonOf("status" to it)) })
            SmallButton("お気に入り", { onPatch(jsonOf("favorite" to (row.bool("favorite") != true))) }, icon = Icons.Outlined.Star, primary = row.bool("favorite") == true)
            SelectField(listOf("0" to "評価なし") + (1..5).map { "$it" to "評価 $it" }, (row.int("rating") ?: 0).toString(), { v -> v.toInt().takeIf { it > 0 }?.let { onPatch(jsonOf("rating" to it)) } })
            SmallButton(if (busy) "分析中…" else "再分析", onReanalyze, enabled = !busy, icon = Icons.Outlined.Refresh)
            SmallButton("3Dで確認", onSim, icon = Icons.Outlined.ViewInAr); SmallButton("削除", onDelete, icon = Icons.Outlined.Delete)
        }
        OutlinedTextField(note, { note = it }, placeholder = { Text("メモ（実際の構図・気づいた点）", fontSize = 12.sp) }, minLines = 2, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SmallButton("メモを保存", { onPatch(jsonOf("note" to note)) })
            SmallButton(if (uploading) "登録中…" else "現地写真", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !uploading, icon = Icons.Outlined.CameraAlt)
            if (photoUrl.isNotBlank()) SmallButton("登録した写真を開く", { uri.openUri(photoUrl) })
        }
        if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
    }
}
