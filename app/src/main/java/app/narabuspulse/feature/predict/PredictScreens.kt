package app.narabuspulse.feature.predict

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
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
import app.narabuspulse.feature.analytics.DataTable
import app.narabuspulse.feature.analytics.Col
import app.narabuspulse.feature.analytics.ErrorText
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.analytics.fmtNum
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.data.jstTodayKey
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.EmptyState
import app.narabuspulse.ui.kit.MapCircle
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.StatTile
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlin.math.abs

private val TABS = listOf("/predict/next" to "次運用予測", "/predict/intersection" to "交差点通過予測", "/predict/watch" to "車両出現通知", "/predict/report" to "運行レポート", "/predict/accuracy" to "予測精度・履歴", "/timetable" to "交差点時刻表")
val PURPLE = Color(0xFF7B5CC4)

@Composable
fun PredictNav(current: String) {
    val nav = LocalNav.current
    ChipRow(TABS, current, { if (it != current) nav.go(it) })
}

private val LEVELS = mapOf("high" to ("高" to Brand.Success), "normal" to ("中" to INFO), "low" to ("低" to Brand.Warning), "insufficient" to ("データ不足" to Brand.MutedFg))

/** 予測の信頼度（Web: ConfidenceChip.jsx） */
@Composable
fun ConfidenceChip(level: String?, prefix: String = "信頼度") { val (l, c) = LEVELS[level] ?: LEVELS.getValue("insufficient"); Tag("$prefix $l", c) }

fun clockOf(ts: Long?): String = if (ts == null || ts == 0L) "—" else BusFormat.time(ts)
fun minutesLabel(min: Double?): String = when { min == null -> "—"; abs(min) < 1 -> "ほぼ同時刻"; else -> Math.round(min).let { if (it > 0) "+${it}分" else "${it}分" } }
fun formatDistance(m: Double?): String = when { m == null -> "—"; m < 950 -> "${Math.round(m)} m"; else -> "%.1f km".format(m / 1000) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToneStrip(stats: List<Triple<String, String, Color?>>) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
        stats.forEach { (label, value, tone) -> StatTile(label, value, Modifier.weight(1f), null, tone) }
    }
}

@Composable
fun MiniHeader(title: String, description: String, action: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) { Heading(title, description) }
        action?.invoke()
    }
}

private val NEXT_LEVELS = listOf("forecast" to "予測あり", "all" to "すべて（データ不足も含む）", "high" to "信頼度 高", "normal" to "信頼度 中", "low" to "信頼度 低", "insufficient" to "データ不足")

/** 次運用予測（Web: predict.next.jsx） */
@Composable
fun NextRunScreen(initialVehicle: String?) {
    val scope = rememberCoroutineScope()
    val today = jstTodayKey()
    val q = rememberLoad("predict-next", today) { Api.rpc("predict/fns#listNextRunPredictions", jsonOf("date" to today)) }
    var busy by remember { mutableStateOf(false) }
    var runError by remember { mutableStateOf("") }
    var term by remember { mutableStateOf(initialVehicle.orEmpty()) }
    var level by remember { mutableStateOf("forecast") }
    val predictions = q.data.items("predictions")
    val rows = predictions.filter { p ->
        val c = p.str("confidence")
        if (level == "forecast" && c == "insufficient") return@filter false
        if (level != "all" && level != "forecast" && c != level) return@filter false
        val n = term.trim()
        n.isEmpty() || listOf("vehicle_label", "vehicle", "next_route", "next_destination", "area").joinToString(" ") { p.str(it).orEmpty() }.contains(n)
    }
    fun update() {
        busy = true; runError = ""
        scope.launch {
            try { Api.rpc("predict/fns#runNextRunPrediction", jsonOf("date" to today)); q.reload() }
            catch (e: Throwable) { if (e is CancellationException) throw e; runError = e.userMessage().ifBlank { "予測を更新できませんでした" } } finally { busy = false }
        }
    }
    ScreenList {
        item {
            MiniHeader("次運用予測", "車両図鑑に登録された全車両を対象に、過去の運行記録と現在位置から「次に入る系統・行先・開始時刻の範囲・出現エリア」を推定します。履歴が無い車両はデータ不足として表示します。予測は実績ではありません。") {
                SmallButton(if (busy) "更新中…" else "予測を更新", { update() }, enabled = !busy, icon = Icons.Outlined.Refresh, primary = true)
            }
        }
        item { PredictNav("/predict/next") }
        item {
            ToneStrip(listOf(
                Triple("対象車両", "${predictions.size}台", null),
                Triple("信頼度 高", "${predictions.count { it.str("confidence") == "high" }}台", Brand.Success),
                Triple("運行終了の見込み", "${predictions.count { it.bool("likely_end") == true }}台", Brand.Warning),
                Triple("データ不足", "${predictions.count { it.str("confidence") == "insufficient" }}台", Brand.MutedFg),
                Triple("生成時刻", clockOf(q.data.long("generatedAt")), null),
            ))
        }
        item {
            OutlinedTextField(term, { term = it }, leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Search, null) }, placeholder = { Text("車両番号・系統・行先で検索", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                SelectField(NEXT_LEVELS, level, { level = it }); Muted("  ${rows.size} 台", 12)
            }
        }
        if (runError.isNotBlank()) item { ErrorText(runError) }
        q.error?.takeIf { runError.isBlank() }?.let { item { ErrorText("保存済みの予測を読み込めませんでした: $it") } }
        if (q.loading && predictions.isEmpty()) item { Muted("保存済みの予測を確認しています…", 12) }
        else if (rows.isEmpty()) item { EmptyState("まだ予測がありません", description = "「予測を更新」を押すと、車両ごとの次運用を推定します（過去45日の運行記録を使用）。") }
        else items(rows.size) { i -> NextRunCard(rows[i]) }
        item { Muted("予測は過去の運行記録と現在位置に基づく推定です。記録が少ない車両は信頼度が低く表示されます。実際の運行は公式の運行情報を確認してください。", 10) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NextRunCard(p: JsonElement) {
    var open by remember { mutableStateOf(false) }
    val status = p.str("status")
    val (sl, sc) = when (status) { "observed" -> "実績を確認" to Brand.Success; "expired" -> "時間切れ" to Brand.MutedFg; else -> "予測" to INFO }
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            val label = p.str("vehicle_label")?.ifBlank { null } ?: p.str("vehicle").orEmpty()
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (!p.str("vehicle_label").isNullOrBlank() && p.str("vehicle_label") != p.str("vehicle")) Muted("コード ${p.str("vehicle")}", 10)
            Tag(sl, sc); ConfidenceChip(p.str("confidence"))
        }
        if (p.bool("likely_end") == true) Text("この車両は運行を終える可能性があります", fontSize = 12.sp, color = Brand.Warning, fontWeight = FontWeight.Bold)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            val from = p.long("start_from"); val to = p.long("start_to")
            Text(if (from == null || to == null) "時刻は推定できません" else "${clockOf(from)}〜${clockOf(to)}頃", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            p.str("next_route")?.takeIf { it.isNotBlank() }?.let { Tag("系統 $it", INFO) }
            p.str("next_destination")?.takeIf { it.isNotBlank() }?.let { Text("行先 $it", fontSize = 12.sp) }
        }
        Muted("現在: ${p.str("last_run_route")?.takeIf { it.isNotBlank() }?.let { "系統 $it" } ?: "運用は未確認"}　出現エリア: ${p.str("area")?.ifBlank { null } ?: "推定できません"}", 11)
        Muted("根拠: 過去 ${p.int("sample_runs") ?: 0}件・${p.int("sample_days") ?: 0}日　方式: ${when (p.str("method")) { "live" -> "現在位置"; "mixed" -> "現在位置＋履歴"; else -> "過去履歴" }}", 11)
        if (status == "observed" && p.long("observed_start_at") != null) Text("実際の記録: ${clockOf(p.long("observed_start_at"))} 開始${p.str("observed_route")?.takeIf { it.isNotBlank() }?.let { "／系統 $it" } ?: ""}", fontSize = 11.sp, color = Brand.Success)
        val basis = p.list("basis").map { it.text }
        if (basis.isNotEmpty()) {
            TextButton(onClick = { open = !open }) { Text(if (open) "予測の根拠を閉じる" else "予測の根拠を見る", fontSize = 11.sp) }
            if (open) basis.forEach { Muted("・$it", 10) }
        }
        p.str("note")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
    }
}

private val PROMPTS = listOf("この交差点を何時頃にバスが通る？", "今日の15時以降、この交差点を通るバスはある？", "今から30分以内にバスが来る可能性はある？", "この付近を通る大型車両はある？")
private val RADII = listOf(200, 300, 500, 800, 1200, 2000)
private data class Msg(val role: String, val text: String? = null, val error: String? = null, val result: JsonElement? = null)
private data class Pt(val lat: Double, val lon: Double, val name: String)

/** 交差点通過予測AI（Web: predict.intersection.jsx + PassageChat.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntersectionScreen(initialQuery: String?) {
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf(Msg("assistant", "地点を決めてから質問してください。地図をタップするか「現在地を使う」で指定できます。指定地点を何時頃に奈良交通のバスが通るかを、現在位置と過去の運行記録から推定します。")) }
    var text by remember { mutableStateOf(initialQuery.orEmpty()) }
    var point by remember { mutableStateOf<Pt?>(null) }
    var radiusM by remember { mutableStateOf(500) }
    var date by remember { mutableStateOf(jstTodayKey()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var locating by remember { mutableStateOf(false) }
    var parsedNote by remember { mutableStateOf("") }
    val locate = rememberLocationRequest { loc ->
        locating = false
        if (loc == null) error = "現在地を取得できませんでした。地図から地点を指定してください" else point = Pt(loc.lat, loc.lon, "現在地")
    }
    val lastResult = messages.lastOrNull { it.result != null }?.result
    val vehicles = lastResult.items("items").filter { it.dbl("lat") != null && it.dbl("lon") != null }

    fun send(prompt: String? = null) {
        val question = (prompt ?: text).trim()
        val p = point
        if (question.isEmpty() && p == null) { error = "地点を指定するか、質問を入力してください"; return }
        messages += Msg("user", question.ifEmpty { "${p!!.name}を通るバスは？" })
        text = ""; busy = true; error = ""; parsedNote = ""
        scope.launch {
            try {
                val res = Api.rpc("predict/fns#askIntersection", jsonOf("query" to question.ifEmpty { null }, "point" to p?.let { jsonOf("lat" to it.lat, "lon" to it.lon, "name" to it.name) }, "radiusM" to radiusM, "date" to date))
                if (res.bool("ok") != true) {
                    messages += Msg("assistant", error = res.str("error") ?: "予測できませんでした")
                    res.str("parsed.placeName")?.let { parsedNote = "読み取った地点: $it" }
                } else {
                    res.objAt("point")?.let { pt -> val la = pt.dbl("lat"); val lo = pt.dbl("lon"); if (la != null && lo != null) point = Pt(la, lo, pt.str("name") ?: "指定地点") }
                    res.int("result.radiusM")?.let { radiusM = it }
                    res.str("result.targetDate")?.let { date = it }
                    messages += Msg("assistant", result = res.objAt("result"))
                    parsedNote = if (res.str("parsed.source") == "keyword") "AI解析を使わず、キーワードから読み取りました" else ""
                }
            } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "予測できませんでした" } } finally { busy = false }
        }
    }

    ScreenList {
        item { Heading("交差点通過予測AI", "地点を指定して質問すると、現在位置と過去の通過記録から、何時頃に奈良交通のバスが通るかを推定します。時刻は実績ではなく予測です。") }
        item { PredictNav("/predict/intersection") }
        item {
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    SmallButton(if (locating) "取得中…" else "現在地を使う", { locating = true; error = ""; locate() }, enabled = !locating, icon = Icons.Outlined.MyLocation)
                    SelectField(RADII.map { it to "範囲 ${it}m" }, radiusM, { radiusM = it })
                    DateField(date, { date = it })
                }
                Muted("📍 ${point?.let { "${it.name}（${"%.4f".format(it.lat)}, ${"%.4f".format(it.lon)}）" } ?: "地点未指定"}", 12)
            }
        }
        item {
            val pins = buildList {
                point?.let { add(MapPin("point", it.lat, it.lon, it.name, color = Color(0xFFE8B45B), size = 16.dp)) }
                vehicles.forEachIndexed { i, v -> add(MapPin("v$i", v.dbl("lat")!!, v.dbl("lon")!!, v.str("vehicle").orEmpty(), "${v.str("route")?.let { "系統 $it " } ?: ""}${v.str("timeLabel").orEmpty()}", color = Color(0xFF35C98B), size = 11.dp)) }
            }
            OsmMap(
                Modifier.fillMaxWidth().height(300.dp), pins = pins,
                circles = listOfNotNull(point?.let { MapCircle(it.lat, it.lon, radiusM.toDouble(), Color(0xFF6C8CFF)) }),
                center = point?.let { it.lat to it.lon } ?: (34.685 to 135.805), zoom = 14.0, fitKey = point,
                onMapClick = { la, lo -> point = Pt(la, lo, "地図で指定した地点") },
            )
        }
        items(messages.size) { i ->
            val m = messages[i]
            if (m.role == "user") Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(12.dp)) { Text(m.text.orEmpty(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, modifier = Modifier.padding(10.dp)) }
            } else Column {
                m.text?.let { Text(it, fontSize = 12.sp) }
                m.error?.let { ErrorText(it, warning = true) }
                m.result?.let { PassageResultList(it) }
            }
        }
        if (busy) item { Muted("地点と運行記録を照合しています…", 12) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { PROMPTS.forEach { p -> AssistChip({ if (!busy) send(p) }, label = { Text(p, fontSize = 11.sp) }) } }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, placeholder = { Text("例: この交差点を何時頃にバスが通る？", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
                SmallButton("送信", { send() }, enabled = !busy, icon = Icons.AutoMirrored.Outlined.Send, primary = true, modifier = Modifier.padding(start = 6.dp))
            }
            (error.ifBlank { parsedNote }).takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp, color = if (error.isNotBlank()) Brand.Destructive else Brand.MutedFg) }
        }
        item { Muted("✨ 地図をタップすると予測地点を指定できます。青い円が予測対象範囲、緑の点が確認できた車両位置です。通過時刻の計算はサーバー側で行い、AIは質問文の読み取りだけに使います。", 10) }
    }
}

@Composable
private fun Spacer(modifier: Modifier) = androidx.compose.foundation.layout.Spacer(modifier)

private val METHOD = mapOf("live" to ("現在位置から予測" to Brand.Success), "history" to ("過去の運行傾向から予測" to INFO), "mixed" to ("現在位置＋履歴" to PURPLE))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PassageResultList(result: JsonElement) {
    val nav = LocalNav.current
    val items = result.items("items")
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            val (ml, mc) = METHOD[result.str("method")] ?: ("予測できません" to Brand.MutedFg)
            Tag(ml, mc); ConfidenceChip(result.str("confidence"))
            Muted("${result.str("place") ?: "指定地点"}・半径 ${result.int("radiusM") ?: 0}m・${result.str("window.fromLabel").orEmpty()}〜${result.str("window.toLabel").orEmpty()}", 10)
            Muted("最終更新 ${clockOf(result.long("predictedAt"))}", 10)
        }
        if (items.isEmpty()) {
            Text("ⓘ 予測できる通過がありません", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Muted("予測に必要な運行履歴が不足しています。時間帯を広げるか、別の地点でお試しください。")
        }
        items.forEachIndexed { i, it ->
            if (i > 0) RowDivider()
            Column(Modifier.padding(vertical = 4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(it.str("timeLabel").orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    it.str("route")?.takeIf { r -> r.isNotBlank() }?.let { r -> Tag("系統 $r", INFO) }
                    it.str("destination")?.takeIf { d -> d.isNotBlank() }?.let { d -> Text("行先 $d", fontSize = 12.sp) }
                    val v = it.str("vehicle")
                    if (!v.isNullOrBlank()) Text("🚌 $v", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { nav.go("/vehicle-catalog/" + android.net.Uri.encode(v)) })
                    else Muted("車番: 不明")
                    ConfidenceChip(it.str("confidence"))
                }
                Muted(listOfNotNull(it.dbl("distanceM")?.let { d -> "📍 地点まで ${formatDistance(d)}" }, it.str("basis")).joinToString("　"), 10)
                it.str("stop")?.takeIf { s -> s.isNotBlank() }?.let { s -> Muted("現在の停留所: $s", 10) }
            }
        }
        result.list("notes").forEach { Muted("・${it.text}", 10) }
        Muted("表示は予測であり、実際の通過を保証するものではありません。現在位置が取れない場合は過去の運行傾向からの推定です。", 10)
    }
}

private val WEEKDAY = listOf("日", "月", "火", "水", "木", "金", "土")

/** 予測精度・予測履歴（Web: predict.accuracy.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PredictAccuracyScreen() {
    val q = rememberLoad("predict-insights") { Api.rpc("predict/fns#getPredictionInsights", jsonOf()) }
    val data = q.data
    ScreenList {
        item {
            MiniHeader("予測精度・予測履歴", "予測時刻と実際に確認できた通過時刻を照合し、地点・曜日・時間帯ごとの誤差を蓄積します。照合できない予測は判定不能として扱います。") {
                SmallButton("再読み込み", { q.reload() }, icon = Icons.Outlined.Refresh)
            }
        }
        item { PredictNav("/predict/accuracy") }
        q.error?.let { item { ErrorText("読み込めませんでした: $it") } }
        if (q.loading && data == null) item { Muted("精度の記録を確認しています…", 12) }
        else {
            item { Text("精度の集計", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            val acc = data.items("accuracy")
            if (acc.isEmpty()) item { EmptyState("予測精度の記録がまだありません", description = "交差点通過予測を使い、実際の通過が確認できると、予測との誤差が自動で記録されます。") }
            items(acc.size) { i ->
                val row = acc[i]
                val samples = row.int("samples") ?: 0
                fun pc(k: String): Int? = if (samples == 0) null else Math.round((row.dbl(k) ?: 0.0) * 100 / samples).toInt()
                val w5 = pc("within5"); val w10 = pc("within10")
                Panel {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text("🎯 ${row.str("label") ?: row.str("key").orEmpty()}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Tag(if (row.str("kind") == "intersection") "交差点通過" else "次運用", if (row.str("kind") == "intersection") INFO else Brand.MutedFg)
                        Muted("${samples}件の照合")
                    }
                    ToneStrip(listOf(
                        Triple("平均誤差", row.dbl("mae_min")?.let { "${fmtNum(it)}分" } ?: "—", INFO),
                        Triple("90%誤差", row.dbl("p90_min")?.let { "${fmtNum(it)}分" } ?: "—", null),
                        Triple("5分以内", w5?.let { "$it%" } ?: "—", if ((w5 ?: 0) >= 70) Brand.Success else Brand.Warning),
                        Triple("10分以内", w10?.let { "$it%" } ?: "—", if ((w10 ?: 0) >= 85) Brand.Success else Brand.Warning),
                        Triple("方式", when (row.str("method")) { "live" -> "現在位置"; "history" -> "履歴"; else -> "併用" }, Brand.MutedFg),
                    ))
                    val wd = row.items("by_weekday").filter { (it.int("samples") ?: 0) > 0 }
                    if (wd.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        wd.forEach { w -> Tag("${WEEKDAY.getOrNull(w.int("weekday") ?: -1) ?: w.str("weekday")}: ${w.dbl("mae")?.let { fmtNum(it) } ?: "—"}分（${w.int("samples")}件）", Brand.MutedFg) }
                    }
                    val hr = row.items("by_hour").filter { (it.int("samples") ?: 0) > 0 }
                    if (hr.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        hr.forEach { h -> Tag("${h.str("band") ?: h.int("band")}時台: ${h.dbl("mae")?.let { fmtNum(it) } ?: "—"}分（${h.int("samples")}件）", Brand.MutedFg) }
                    }
                }
            }
            item { Text("履歴", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            item { PredictionHistoryList(data.items("predictions"), data.items("passages"), data.items("verifiedRuns")) }
            item { Muted("精度は、定期実行が予測地点の範囲内にいる車両を確認できた場合だけ記録されます。車両を確認できなかった時間帯は「判定不能」であり、誤差には含めません。", 10) }
        }
    }
}

private val METHOD_LABEL = mapOf("live" to "現在位置", "history" to "過去履歴", "mixed" to "併用", "none" to "予測不可")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PredictionHistoryList(predictions: List<JsonElement>, passages: List<JsonElement>, verified: List<JsonElement>) {
    if (predictions.isEmpty() && passages.isEmpty() && verified.isEmpty()) { EmptyState("履歴がまだありません", description = "交差点通過予測を使うと、予測内容と照合結果がここに残ります。"); return }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(title = "交差点通過予測の履歴") {
            if (predictions.isEmpty()) Muted("予測履歴はありません", 12)
            predictions.forEachIndexed { i, r ->
                if (i > 0) RowDivider()
                Column(Modifier.padding(vertical = 4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text("📍 ${r.str("place_name") ?: "地点不明"}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        val m = r.str("method")
                        Tag(METHOD_LABEL[m] ?: m.orEmpty(), when (m) { "live" -> Brand.Success; "history" -> INFO; else -> Brand.MutedFg })
                        ConfidenceChip(r.str("confidence"))
                        Muted(BusFormat.dateTime(r.long("predicted_at")), 10)
                    }
                    r.str("query")?.takeIf { it.isNotBlank() }?.let { Muted("「$it」", 11) }
                    val its = r.items("items")
                    if (its.isNotEmpty()) Muted(its.take(6).joinToString(" / ") { "${it.str("timeLabel").orEmpty()}${it.str("route")?.takeIf { x -> x.isNotBlank() }?.let { x -> "（$x）" } ?: ""}" }, 10)
                }
            }
        }
        Panel(title = "実際に確認した通過") {
            if (passages.isEmpty()) Muted("通過の確認記録はありません。定期実行が、予測地点の範囲内にいる車両を確認して記録します。", 12)
            passages.take(20).forEachIndexed { i, r ->
                if (i > 0) RowDivider()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(r.str("vehicle").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Muted(r.str("route")?.takeIf { it.isNotBlank() }?.let { "系統 $it" } ?: "系統不明")
                    Muted(r.str("place_name").orEmpty()); Muted(BusFormat.dateTime(r.long("at")), 10)
                    if (r.bool("matched") == true) { val e = r.dbl("error_min"); Tag("誤差 ${minutesLabel(e)}", if (abs(e ?: 99.0) <= 5) Brand.Success else Brand.Warning) } else Tag("予測外", Brand.MutedFg)
                }
            }
        }
        if (verified.isNotEmpty()) Panel(title = "実績と照合できた次運用予測") {
            verified.forEachIndexed { i, r ->
                if (i > 0) RowDivider()
                val ok = !r.str("next_route").isNullOrBlank() && r.str("observed_route") == r.str("next_route")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(r.str("vehicle").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Muted("予測 ${r.str("next_route")?.takeIf { it.isNotBlank() }?.let { "系統 $it" } ?: "系統不明"} → 実績 ${r.str("observed_route")?.takeIf { it.isNotBlank() }?.let { "系統 $it" } ?: "系統不明"}")
                    Muted(BusFormat.dateTime(r.long("observed_start_at")), 10)
                    Tag(if (ok) "一致" else "系統が異なる", if (ok) Brand.Success else Brand.Warning)
                }
            }
        }
    }
}

/** 車両運行レポート（Web: predict.report.jsx + ReportView.jsx） */
@Composable
fun PredictReportScreen() {
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(jstTodayKey()) }
    val q = rememberLoad("predict-report", date) { Api.rpc("predict/fns#getVehicleReport", jsonOf("date" to date)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var term by remember { mutableStateOf("") }
    val report = q.data?.objAt("report")
    val dates = q.data.items("dates")
    val stats = report?.objAt("stats")
    fun generate() {
        busy = true; message = ""
        scope.launch {
            try { Api.rpc("predict/fns#getVehicleReport", jsonOf("date" to date, "generate" to true)); q.reload(); message = "$date のレポートを生成しました" }
            catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "レポートを生成できませんでした" } } finally { busy = false }
        }
    }
    ScreenList {
        item { Heading("車両運行レポート", "その日に実際に確認できた車両・系統・時刻を集計し、AIが傾向を分析します。確認できなかった運行は実績として書きません。") }
        item { PredictNav("/predict/report") }
        if (message.isNotBlank()) item { Muted(message, 12) }
        q.error?.let { item { ErrorText("レポートを読み込めませんでした: $it") } }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Muted("対象日", 12); DateField(date, { date = it })
                    SmallButton(if (busy) "生成中…" else "この日のレポートを生成", { generate() }, enabled = !busy, icon = Icons.Outlined.Description, primary = true)
                }
                if (dates.isNotEmpty()) SelectField(listOf("" to "保存済みのレポート…") + dates.mapNotNull { it.str("date") }.map { it to it }, if (dates.any { it.str("date") == date }) date else "", { if (it.isNotBlank()) date = it }, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (q.loading && q.data == null) item { Muted("レポートを確認しています…", 12) }
        else if (report == null) item { EmptyState("この日のレポートはまだありません", description = "「この日のレポートを生成」を押すと、運行記録と位置履歴からその日の実績を集計します。") }
        else {
            item {
                val ch = stats.dbl("comparison.confirmedChange")
                ToneStrip(listOf(
                    Triple("確認できた車両", "${stats.int("confirmedVehicles") ?: 0}台", null),
                    Triple("運行開始を確認", "${stats.int("runningVehicles") ?: 0}台", INFO),
                    Triple("運行終了を確認", "${stats.int("endedVehicles") ?: 0}台", Brand.Success),
                    Triple("運行記録", "${stats.int("runCount") ?: 0}件", null),
                    Triple("前日比（確認車両）", ch?.let { "${if (it > 0) "+" else ""}${fmtNum(it)}%" } ?: "—", Brand.MutedFg),
                ))
                stats.str("comparison.previousDate")?.let { Muted("前日 $it", 10) }
            }
            item {
                Panel(title = "📄 集計結果（実績）") {
                    Text(report.str("summary").orEmpty(), fontSize = 13.sp)
                    stats.objAt("topVehicle")?.let { tv ->
                        val routes = tv.list("routes").map { it.text }
                        Muted("最も確認回数が多かった車両: ${tv.str("vehicle")}（${fmtNum(tv.long("checks") ?: 0)}回${if (routes.isNotEmpty()) "／系統 ${routes.joinToString("・")}" else ""}）", 11)
                    }
                    stats.items("collection.errors").firstOrNull()?.let { Text("⚠ データ収集の失敗: ${it.str("errors") ?: it.int("errors")}", fontSize = 11.sp, color = Brand.Warning) }
                }
            }
            item {
                Panel(title = "✨ AIによる分析（推定・参考情報）") {
                    if (report.bool("ai_ok") == true) Text(report.str("ai_summary").orEmpty(), fontSize = 13.sp)
                    else Muted(report.str("ai_error") ?: "AI分析は生成されていません。集計結果は上に表示されています。", 12)
                    Muted("分析は上の集計値だけを根拠にした推定です。実績として扱わないでください。", 10)
                }
            }
            val rb = stats.items("routeBreakdown")
            if (rb.isNotEmpty()) item {
                Panel(title = "系統ごとの確認状況") {
                    DataTable(listOf(Col("系統", 100.dp), Col("運行記録", 80.dp, true), Col("車両数", 72.dp, true)), rb.take(12).map { listOf(it.str("route").orEmpty(), "${it.int("runs") ?: 0}件", "${it.int("vehicles") ?: 0}台") })
                }
            }
            item {
                val vehicles = report.items("vehicles")
                val n = term.trim()
                val rows = (if (n.isEmpty()) vehicles else vehicles.filter { v -> "${v.str("vehicle")} ${v.list("routes").joinToString(" ") { it.text }} ${v.list("destinations").joinToString(" ") { it.text }}".contains(n) }).take(60)
                Panel(title = "車両ごとの確認内容") {
                    OutlinedTextField(term, { term = it }, placeholder = { Text("車両・系統で絞り込み", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (rows.isEmpty()) Muted("該当する車両がありません", 12)
                    else DataTable(
                        listOf(Col("車両", 110.dp), Col("確認", 52.dp, true), Col("開始", 52.dp), Col("終了", 52.dp), Col("系統", 90.dp), Col("行先", 160.dp), Col("最終停留所", 120.dp)),
                        rows.map { r -> listOf(r.str("vehicle").orEmpty(), "${r.int("checkCount") ?: 0}回", r.str("startLabel")?.ifBlank { null } ?: "—", r.str("endLabel")?.ifBlank { null } ?: "—", r.list("routes").joinToString("・") { it.text }.ifBlank { "—" }, r.list("destinations").joinToString(" / ") { it.text }.ifBlank { "—" }, r.str("lastStop")?.ifBlank { null } ?: "—") },
                    )
                    val failed = stats.list("failedVehicles").map { it.text }
                    if (failed.isNotEmpty()) Muted("位置を取得できなかった車両: ${failed.take(12).joinToString("・")}", 10)
                    report.str("source_note")?.let { Muted(it, 10) }
                    Muted("生成 ${clockOf(report.long("generated_at"))}／${if (report.str("trigger") == "schedule") "定期生成" else "手動生成"}", 10)
                }
            }
        }
    }
}
