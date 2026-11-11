package app.narabuspulse.feature.expedition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Json
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.feature.analytics.Col
import app.narabuspulse.feature.analytics.DataTable
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.kit.rememberConfirmer
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private fun parse(text: String?): JsonElement? = if (text.isNullOrBlank()) null else runCatching { Json.parse(text) }.getOrNull()?.takeIf { it !is JsonNull }
private fun yen(v: Number?) = "${NumberFormat.getInstance(Locale.JAPAN).format(Math.round(v?.toDouble() ?: 0.0))} 円"
private val STATUS = mapOf("planned" to ("計画中" to Brand.MutedFg), "confirmed" to ("決定" to Brand.Success), "done" to ("実行済み" to Brand.MutedFg))

@Composable
private fun Num(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) =
    OutlinedTextField(value, { onChange(it.filter { c -> c.isDigit() }) }, label = { Text(label, fontSize = 10.sp) }, singleLine = true, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

/** 遠征の基本条件（Web: expedition/PlanForm.jsx）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanForm(initial: JsonElement?, busy: Boolean, onSubmit: (JsonObject) -> Unit, onCancel: () -> Unit) {
    fun s(k: String, d: String = "") = initial.str(k) ?: d
    fun n(k: String) = (initial.long(k) ?: 0).toString()
    var title by remember { mutableStateOf(s("title")) }
    var start by remember { mutableStateOf(s("start_date")) }
    var end by remember { mutableStateOf(s("end_date")) }
    var depart by remember { mutableStateOf(s("depart_time", "07:00")) }
    var ret by remember { mutableStateOf(s("return_time", "20:00")) }
    var nights by remember { mutableStateOf(n("nights")) }
    var party by remember { mutableStateOf((initial.long("party_size") ?: 1).toString()) }
    var budget by remember { mutableStateOf(n("budget")) }
    var lodgingCost by remember { mutableStateOf(n("lodging_cost_per_night")) }
    var foodCost by remember { mutableStateOf(n("food_cost_per_day")) }
    var transportCost by remember { mutableStateOf(n("transport_cost_total")) }
    val otherCost = n("other_cost_total")
    var lodgingName by remember { mutableStateOf(s("lodging_name")) }
    var lodgingUrl by remember { mutableStateOf(s("lodging_url")) }
    var lodgingNote by remember { mutableStateOf(s("lodging_note")) }
    var note by remember { mutableStateOf(s("note")) }
    var error by remember { mutableStateOf("") }
    Panel {
        OutlinedTextField(title, { title = it }, label = { Text("遠征名", fontSize = 11.sp) }, placeholder = { Text("奈良交通 撮影遠征", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Muted("開始日", 11); DateField(start, { start = it }); Muted("終了日", 11); DateField(end, { end = it }, min = start.ifBlank { null })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Muted("出発予定", 11); TimeField(depart, { depart = it }); Muted("帰宅希望", 11); TimeField(ret, { ret = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Num("宿泊数", nights, { nights = it.take(2) }, Modifier.weight(1f)); Num("人数", party, { party = it.take(2) }, Modifier.weight(1f)); Num("予算（円・全体）", budget, { budget = it.take(8) }, Modifier.weight(1.4f)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Num("宿泊費（1泊1人）", lodgingCost, { lodgingCost = it.take(7) }, Modifier.weight(1f)); Num("食費（1日1人）", foodCost, { foodCost = it.take(7) }, Modifier.weight(1f)) }
        Num("交通費の見込み（1人合計）", transportCost, { transportCost = it.take(8) }, Modifier.fillMaxWidth())
        OutlinedTextField(lodgingName, { lodgingName = it }, label = { Text("宿泊先（候補）", fontSize = 11.sp) }, placeholder = { Text("未定", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(lodgingUrl, { lodgingUrl = it }, label = { Text("宿泊先の予約サイトURL", fontSize = 11.sp) }, placeholder = { Text("https://", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(lodgingNote, { lodgingNote = it }, label = { Text("宿泊のメモ（料金・空室は各自で確認）", fontSize = 11.sp) }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("遠征全体のメモ", fontSize = 11.sp) }, minLines = 2, modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = Brand.Destructive)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton(if (busy) "保存中…" else "保存", {
                when {
                    title.isBlank() -> error = "遠征名を入力してください"
                    start.isBlank() || end.isBlank() -> error = "開始日と終了日を指定してください"
                    end < start -> error = "終了日は開始日以降にしてください"
                    else -> {
                        error = ""
                        onSubmit(jsonOf("title" to title, "start_date" to start, "end_date" to end, "depart_time" to depart, "return_time" to ret, "nights" to (nights.toIntOrNull() ?: 0),
                            "party_size" to maxOf(1, party.toIntOrNull() ?: 1), "budget" to (budget.toLongOrNull() ?: 0), "lodging_cost_per_night" to (lodgingCost.toLongOrNull() ?: 0), "food_cost_per_day" to (foodCost.toLongOrNull() ?: 0),
                            "transport_cost_total" to (transportCost.toLongOrNull() ?: 0), "other_cost_total" to (otherCost.toLongOrNull() ?: 0), "lodging_name" to lodgingName, "lodging_url" to lodgingUrl, "lodging_note" to lodgingNote, "note" to note))
                    }
                }
            }, enabled = !busy, icon = Icons.Outlined.Save, primary = true)
            SmallButton("キャンセル", onCancel)
        }
        Muted("宿泊費・交通費は入力した見込み額です。実際の料金・空室・時刻表はこの画面では取得しません。", 10)
    }
}

/** 総合遠征プランナー（Web: expedition.index.jsx） */
@Composable
fun ExpeditionListScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val confirm = rememberConfirmer()
    val load = rememberLoad { Api.rpc("expedition/server-fns#listExpeditionPlans", jsonOf()) }
    var creating by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun act(ok: String, block: suspend () -> Unit) {
        busy = true
        scope.launch { try { block(); load.reload(); snackbar.showSnackbar(ok) } catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage()) } finally { busy = false } }
    }
    val plans = load.data.items()
    ScreenList {
        item {
            Heading("総合遠征プランナー", "撮影対象の車両・日程・宿泊・寄り道先をまとめて、日別の運行傾向と順光・気象を確認しながら遠征計画を作ります。")
            SmallButton("新しい遠征", { creating = !creating }, icon = Icons.Outlined.Add, primary = true, modifier = Modifier.padding(top = 6.dp))
        }
        if (creating) item { PlanForm(null, busy, { v -> act("遠征計画を作成しました") { Api.rpc("expedition/server-fns#saveExpeditionPlan", v); creating = false } }, { creating = false }) }
        if (load.loading && load.data == null) item { Muted("読み込み中…", 12) }
        load.error?.let { item { Text(it, fontSize = 12.sp, color = Brand.Destructive) } }
        if (!load.loading && load.error == null && plans.isEmpty()) item { Panel { Text("まだ遠征計画がありません", fontSize = 13.sp, fontWeight = FontWeight.Bold); Muted("「新しい遠征」から開始日・終了日を指定して作成してください。", 11) } }
        plans.forEach { p ->
            item(key = p.str("id")) {
                val (sl, sc) = STATUS[p.str("status")] ?: STATUS.getValue("planned")
                Panel(action = {
                    Column(horizontalAlignment = Alignment.End) {
                        Tag(sl, sc)
                        IconButton(onClick = { confirm.ask("「${p.str("title")}」を削除します。よろしいですか？") { act("削除しました") { Api.rpc("expedition/server-fns#deleteExpeditionPlan", jsonOf("planId" to p.str("id"))) } } }, enabled = !busy) { Icon(Icons.Outlined.Delete, "削除") }
                    }
                }) {
                    TextButton(onClick = { nav.go("/expedition/${p.str("id")}") }) { Text(p.str("title").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    Muted("${p.str("start_date")} 〜 ${p.str("end_date")}（${p.int("dayCount") ?: 0}日）${if ((p.int("nights") ?: 0) > 0) "・${p.int("nights")}泊" else "・日帰り"}", 11)
                    Muted("対象車両 ${p.int("targetCount") ?: 0}台・${p.int("party_size") ?: 1}人", 11)
                }
            }
        }
    }
}

private val STOP_TYPES = listOf("photo" to "撮影地点", "station" to "駅", "bus_stop" to "バス停", "sightseeing" to "観光地", "pass" to "峠", "mountain" to "山間部", "village" to "集落", "viewpoint" to "展望地", "other" to "その他")
private val COMPASS = listOf("北", "北北東", "北東", "東北東", "東", "東南東", "南東", "南南東", "南", "南南西", "南西", "西南西", "西", "西北西", "北西", "北北西")

/** 遠征計画の詳細（Web: expedition.$planId.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpeditionDetailScreen(planId: String) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val load = rememberLoad(planId) { Api.rpc("expedition/server-fns#getExpeditionPlan", jsonOf("planId" to planId)) }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var analyzingAll by remember { mutableStateOf(false) }
    var analyzingDate by remember { mutableStateOf("") }
    var savingDay by remember { mutableStateOf("") }
    fun run(ok: String?, block: suspend () -> Unit) {
        busy = true
        scope.launch { try { block(); load.reload(); ok?.let { snackbar.showSnackbar(it) } } catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "処理できませんでした" }) } finally { busy = false } }
    }
    val data = load.data
    ScreenList {
        if (data == null) {
            item { if (load.loading) Muted("読み込み中…", 12) else load.error?.let { Text(it, fontSize = 12.sp, color = Brand.Destructive) } ?: Muted("この遠征計画は見つかりませんでした。", 12) }
            return@ScreenList
        }
        val plan = data.objAt("plan")
        val targets = data.items("targets"); val stops = data.items("stops"); val days = data.items("days")
        val dates = data.list("dates").map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
        val dayByDate = days.associateBy { it.str("date") }
        item {
            TextButton(onClick = { nav.go("/expedition") }) { Icon(Icons.Outlined.ArrowBack, null); Text(" 遠征一覧へ", fontSize = 11.sp) }
            Text(plan.str("title").orEmpty(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Muted("${plan.str("start_date")} 〜 ${plan.str("end_date")}（${dates.size}日・${plan.int("nights") ?: 0}泊）／${plan.int("party_size") ?: 1}人／出発 ${plan.str("depart_time") ?: "—"}・帰宅 ${plan.str("return_time") ?: "—"}", 11)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                SmallButton("条件を編集", { editing = !editing }, icon = Icons.Outlined.Edit)
                SmallButton("経路検索", { nav.go("/transit?planId=${plan.str("id")}&date=${dates.firstOrNull().orEmpty()}") }, icon = Icons.Outlined.Train)
                SmallButton(if (analyzingAll) "分析中…" else "全期間を分析", {
                    analyzingAll = true
                    scope.launch {
                        try { val r = Api.rpc("expedition/server-fns#analyzeExpeditionDays", jsonOf("planId" to planId)); snackbar.showSnackbar("${r.int("analyzed") ?: 0}日分を分析しました"); load.reload() }
                        catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "分析できませんでした" }) } finally { analyzingAll = false }
                    }
                }, enabled = !analyzingAll, icon = Icons.Outlined.AutoAwesome, primary = true)
            }
        }
        if (editing) item { PlanForm(plan, busy, { v -> run("条件を更新しました") { Api.rpc("expedition/server-fns#saveExpeditionPlan", JsonObject(v + ("id" to kotlinx.serialization.json.JsonPrimitive(plan.str("id").orEmpty())))); editing = false } }, { editing = false }) }
        item { TargetPanel(targets, busy, { v -> run("対象車両を追加しました") { Api.rpc("expedition/server-fns#saveExpeditionTarget", JsonObject(v + ("plan_id" to kotlinx.serialization.json.JsonPrimitive(planId)))) } }, { t -> run("削除しました") { Api.rpc("expedition/server-fns#deleteExpeditionTarget", jsonOf("id" to t.str("id"))) } }) }
        item { StopPanel(stops, busy, { v -> run("地点を追加しました") { Api.rpc("expedition/server-fns#saveExpeditionStop", JsonObject(v + ("plan_id" to kotlinx.serialization.json.JsonPrimitive(planId)))) } }, { s -> run("削除しました") { Api.rpc("expedition/server-fns#deleteExpeditionStop", jsonOf("id" to s.str("id"))) } }) }
        item { BudgetCard(plan, days) }
        item {
            Text("日別の分析と行程", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Muted("日付ごとに運行実績・気象・順光を別々に計算します（28日の結果を29日に流用しません）。気象は予報の配信期間外の場合、その旨を表示します。", 11)
        }
        dates.forEach { date ->
            item(key = "day-$date") {
                val day = dayByDate[date]
                DayCard(date, planId, day, analyzingDate == date, savingDay.isNotEmpty() && savingDay == day.str("id"), onAnalyze = {
                    analyzingDate = date
                    scope.launch {
                        try { Api.rpc("expedition/server-fns#analyzeExpeditionDays", jsonOf("planId" to planId, "dates" to listOf(date))); snackbar.showSnackbar("$date を分析しました"); load.reload() }
                        catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "分析できませんでした" }) } finally { analyzingDate = "" }
                    }
                }, onSaveDay = { dayId, payload ->
                    savingDay = dayId
                    scope.launch {
                        try { Api.rpc("expedition/server-fns#saveExpeditionDay", JsonObject(payload + ("id" to kotlinx.serialization.json.JsonPrimitive(dayId)))); snackbar.showSnackbar("この日の内容を保存しました"); load.reload() }
                        catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "保存できませんでした" }) } finally { savingDay = "" }
                    }
                })
            }
        }
    }
}

/** 撮影対象車両（Web: TargetPanel.jsx）。 */
@Composable
private fun TargetPanel(targets: List<JsonElement>, busy: Boolean, onSave: (JsonObject) -> Unit, onDelete: (JsonElement) -> Unit) {
    var vehicle by remember { mutableStateOf("") }; var plate by remember { mutableStateOf("") }; var priority by remember { mutableStateOf("1") }
    var date by remember { mutableStateOf("") }; var time by remember { mutableStateOf("") }; var place by remember { mutableStateOf("") }
    Panel(title = "撮影対象車両", subtitle = "車番は監視シートの車両名と同じ表記にすると、運行記録と照合できます。") {
        if (targets.isEmpty()) Muted("まだ登録されていません。", 12)
        targets.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("優先${t.int("priority") ?: 1}　${t.str("vehicle").orEmpty()}${t.str("plate")?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Muted(listOfNotNull(t.str("preferred_date"), t.str("preferred_time"), t.str("preferred_place")).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "指定なし" }, 11)
                }
                IconButton(onClick = { onDelete(t) }, enabled = !busy) { Icon(Icons.Outlined.Delete, "削除") }
            }
        }
        OutlinedTextField(vehicle, { vehicle = it }, placeholder = { Text("車番（例 奈良200か115）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(plate, { plate = it }, placeholder = { Text("登録番号", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
            Num("優先順位", priority, { priority = it.take(2) }, Modifier.weight(0.6f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Muted("希望日", 11); DateField(date, { date = it }); if (date.isNotBlank()) TextButton(onClick = { date = "" }) { Text("クリア", fontSize = 11.sp) } }
        OutlinedTextField(time, { time = it }, placeholder = { Text("希望時間帯（例 9:00-12:00）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(place, { place = it }, placeholder = { Text("希望撮影地点", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SmallButton("追加", {
            if (vehicle.isBlank()) return@SmallButton
            onSave(jsonOf("vehicle" to vehicle.trim(), "plate" to plate, "priority" to ((priority.toIntOrNull() ?: 1).coerceIn(1, 20)), "preferred_date" to date, "preferred_time" to time, "preferred_place" to place))
            vehicle = ""; plate = ""; priority = "1"; date = ""; time = ""; place = ""
        }, enabled = !busy, icon = Icons.Outlined.Add, primary = true)
    }
}

/** 寄り道先・撮影地点（Web: StopPanel.jsx）。 */
@Composable
private fun StopPanel(stops: List<JsonElement>, busy: Boolean, onSave: (JsonObject) -> Unit, onDelete: (JsonElement) -> Unit) {
    var name by remember { mutableStateOf("") }; var type by remember { mutableStateOf("photo") }; var lat by remember { mutableStateOf("") }; var lon by remember { mutableStateOf("") }
    var heading by remember { mutableStateOf("") }; var stay by remember { mutableStateOf("30") }
    Panel(title = "寄り道先・撮影地点", subtitle = "優先順位と滞在時間を設定できます。指定した地点はAIが勝手に削除しません（入りきらない場合は理由を表示します）。") {
        if (stops.isEmpty()) Muted("まだ登録されていません。", 12)
        stops.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("優先${s.int("priority") ?: 2}　${s.str("name").orEmpty()}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    val h = s.dbl("road_heading")
                    Muted("${STOP_TYPES.toMap()[s.str("stop_type")] ?: s.str("stop_type").orEmpty()}／滞在${s.int("stay_min") ?: 0}分${h?.let { "／進行方位 ${COMPASS[(Math.round((it % 360) / 22.5) % 16).toInt()]}" } ?: ""}", 11)
                }
                IconButton(onClick = { onDelete(s) }, enabled = !busy) { Icon(Icons.Outlined.Delete, "削除") }
            }
        }
        OutlinedTextField(name, { name = it }, placeholder = { Text("地点名", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SelectField(STOP_TYPES, type, { type = it }, label = "種類")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(lat, { lat = it }, placeholder = { Text("緯度（例 34.68）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(lon, { lon = it }, placeholder = { Text("経度（例 135.83）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Num("進行方位 0-359（度）", heading, { heading = it.take(3) }, Modifier.weight(1f)); Num("滞在分", stay, { stay = it.take(4) }, Modifier.weight(1f)) }
        SmallButton("追加", {
            if (name.isBlank()) return@SmallButton
            onSave(jsonOf("name" to name, "stop_type" to type, "lat" to lat.toDoubleOrNull()?.takeIf { kotlin.math.abs(it) <= 90 }, "lon" to lon.toDoubleOrNull()?.takeIf { kotlin.math.abs(it) <= 180 },
                "road_heading" to heading.toIntOrNull()?.coerceIn(0, 359), "priority" to 2, "stay_min" to (stay.toIntOrNull() ?: 0).coerceIn(0, 1440)))
            name = ""; type = "photo"; lat = ""; lon = ""; heading = ""; stay = "30"
        }, enabled = !busy, icon = Icons.Outlined.Add, primary = true)
    }
}

/** 予算の見込みと実入力の合計（Web: BudgetCard.jsx）。 */
@Composable
private fun BudgetCard(plan: JsonElement?, days: List<JsonElement>) {
    val party = maxOf(1, plan.int("party_size") ?: 1); val nights = maxOf(0, plan.int("nights") ?: 0); val dayCount = maxOf(1, days.size)
    val estimate = nights * (plan.dbl("lodging_cost_per_night") ?: 0.0) + dayCount * (plan.dbl("food_cost_per_day") ?: 0.0) + (plan.dbl("transport_cost_total") ?: 0.0) + (plan.dbl("other_cost_total") ?: 0.0)
    val estimateTotal = estimate * party
    val sums = DoubleArray(4)
    days.forEach { d -> val c = parse(d.str("costs")); listOf("transport", "lodging", "food", "other").forEachIndexed { i, k -> sums[i] += c.dbl(k) ?: 0.0 } }
    val entered = sums.sum()
    val budget = plan.dbl("budget") ?: 0.0
    val reference = if (entered > 0) entered * party else estimateTotal
    val over = budget > 0 && reference > budget
    Panel(title = "予算", subtitle = "入力した見込み額の集計です。実際の運賃・宿泊料金・空室状況は取得していません。") {
        Text("日別入力の合計（1人）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg)
        Text(if (entered > 0) yen(entered) else "未入力", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (entered > 0) Muted("交通 ${yen(sums[0])}／宿泊 ${yen(sums[1])}／食費 ${yen(sums[2])}／その他 ${yen(sums[3])}", 10)
        Text("見込み（プラン設定から）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg, modifier = Modifier.padding(top = 6.dp))
        Text("${yen(estimate)} / 人", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Muted("${party}人で ${yen(estimateTotal)}（宿泊${nights}泊・${dayCount}日）", 10)
        if (budget > 0) {
            Text("予算 ${yen(budget)} に対して ${yen(reference)}（${if (over) "超過" else "内側"}）", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (over) Brand.Warning else Brand.Success, modifier = Modifier.padding(top = 6.dp))
            if (over) Text("超過分は ${yen(reference - budget)} です。宿泊数を減らす・移動区間を絞る・宿泊費の見込みを下げる、のいずれかで調整してください。", fontSize = 11.sp, color = Brand.Warning)
        } else Muted("予算を入力すると超過の判定を表示します。", 11)
    }
}

private val CONFIDENCE = mapOf("high" to ("傾向あり（3日以上の実績）" to Brand.Success), "low" to ("実績が少ない" to Brand.Warning), "none" to ("実績なし" to Brand.MutedFg))
private fun weatherLabel(code: Int?): String = when {
    code == null -> "—"; code == 0 -> "晴"; code <= 2 -> "晴時々曇"; code == 3 -> "曇"; code in 71..77 -> "雪"; code in 85..86 -> "にわか雪"
    code >= 61 -> "雨"; code >= 51 -> "霧雨"; code >= 45 -> "霧"; else -> "曇"
}
private fun fmt(v: Double?, unit: String) = v?.let { "${Math.round(it * 10) / 10.0}$unit".replace(".0$unit", unit) } ?: "—"
private val HM = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

/** 1日分のカード（Web: DayCard.jsx + DayAnalysis.jsx + DayItinerary.jsx）。 */
@Composable
private fun DayCard(date: String, planId: String, day: JsonElement?, analyzing: Boolean, saving: Boolean, onAnalyze: () -> Unit, onSaveDay: (String, JsonObject) -> Unit) {
    val nav = LocalNav.current
    var open by remember { mutableStateOf(false) }
    val analysis = parse(day.str("analysis")) as? JsonArray
    val weather = parse(day.str("weather")); val light = parse(day.str("light"))
    val costs = parse(day.str("costs"))
    val cost = listOf("transport", "lodging", "food", "other").sumOf { costs.dbl(it) ?: 0.0 }
    Panel(action = {
        Row {
            SmallButton("経路", { nav.go("/transit?planId=$planId&date=$date") }, icon = Icons.Outlined.Train)
            IconButton(onClick = { open = !open }, enabled = day != null) { Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, "開閉") }
        }
    }) {
        Text("$date  ${day.str("weekday").orEmpty()}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Muted((day.long("generated_at")?.let { "分析: ${HM.format(Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Tokyo")))} 時点" } ?: "この日はまだ分析していません") + if (cost > 0) "／費用 ${yen(cost)}" else "", 11)
        SmallButton(if (analyzing) "分析中…" else "この日を分析", onAnalyze, enabled = !analyzing, icon = Icons.Outlined.AutoAwesome)
        if (open && day != null) {
            if (analysis == null) Muted("分析データを読み取れませんでした。もう一度実行してください。", 11) else DayAnalysis(analysis, weather, light)
            DayItinerary(parse(day.str("itinerary")).items(), costs, saving) { onSaveDay(day.str("id").orEmpty(), it) }
        }
    }
}

@Composable
private fun DayAnalysis(analysis: JsonArray, weather: JsonElement?, light: JsonElement?) {
    Text("車両の運行傾向（同じ曜日の実績）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    if (analysis.isEmpty()) Muted("撮影対象車両が未登録です。", 11)
    analysis.forEach { row ->
        val (cl, cc) = CONFIDENCE[row.str("confidence")] ?: CONFIDENCE.getValue("none")
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(row.str("vehicle").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(cl, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cc) }
            row.str("preferredPlace")?.takeIf { it.isNotBlank() }?.let { Muted("希望: $it${row.str("preferredTime")?.takeIf { t -> t.isNotBlank() }?.let { t -> " / $t" } ?: ""}", 10) }
            val pats = row.list("patterns")
            if (pats.isEmpty()) Muted(row.str("note").orEmpty(), 11)
            else DataTable(listOf(Col("系統", 70.dp), Col("行先", 110.dp), Col("時間帯", 100.dp), Col("実績", 50.dp, true)),
                pats.map { p -> listOf(p.str("route") ?: "—", p.str("destination") ?: "—", "${p.str("typicalStart") ?: "—"}〜${p.str("typicalEnd") ?: "—"}", "${p.int("observedDays") ?: 0}日") })
        }
    }
    Text("気象（${weather.str("source") ?: "Open-Meteo"}）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    weather.str("error")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp, color = Brand.Warning) } ?: weather.list("points").forEach { pt ->
        Text(pt.str("name").orEmpty(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (pt.str("available") != "true") Muted("この地点の気象データは取得できませんでした。", 10)
        else DataTable(listOf(Col("時刻", 50.dp), Col("天気", 70.dp), Col("気温", 52.dp, true), Col("降水%", 52.dp, true), Col("降水量", 60.dp, true), Col("降雪", 52.dp, true), Col("積雪深", 56.dp, true), Col("雲量", 52.dp, true)),
            pt.list("hours").map { h -> listOf("${h.str("h")}:00", weatherLabel(h.int("code")), fmt(h.dbl("temp"), "℃"), fmt(h.dbl("pop"), "%"), fmt(h.dbl("prec"), "mm"), fmt(h.dbl("snow"), "cm"), fmt(h.dbl("snowDepth"), "cm"), fmt(h.dbl("cloud"), "%")) })
    }
    Text("時間帯別の順光判定", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Muted(light.str("note").orEmpty(), 10)
    DataTable(listOf(Col("時刻", 50.dp), Col("太陽方位", 90.dp), Col("高度", 50.dp, true), Col("前面が順光になる進行方位", 160.dp), Col("左側面が順光になる進行方位", 160.dp)),
        light.list("rows").map { r -> listOf("${r.str("hour")}:00", r.dbl("sunAzimuth")?.let { "${Math.round(it)}° ${r.str("compass").orEmpty()}" } ?: "—", r.str("sunAltitude")?.let { "$it°" } ?: "—",
            r.str("frontHeading")?.let { "$it° ${r.str("frontSunLabel").orEmpty()}" } ?: "—", r.str("leftHeading")?.let { "$it° ${r.str("leftSunLabel").orEmpty()}" } ?: "—") })
    val ls = light.list("stops")
    if (ls.isNotEmpty()) { Muted("登録地点の正午時点の判定", 10); ls.forEach { r -> Text("${r.str("name")}（進行方位 ${r.str("heading")}°）… 前面 ${r.str("front")} / 左側面 ${r.str("left")}${if (r.str("both") == "true") "（両面が明るい条件）" else ""}", fontSize = 10.sp) } }
}

private val COST_KEYS = listOf("transport" to "交通費", "lodging" to "宿泊費", "food" to "食費", "other" to "その他")

@Composable
private fun DayItinerary(itinerary: List<JsonElement>, costs: JsonElement?, busy: Boolean, onSave: (JsonObject) -> Unit) {
    var rows by remember(itinerary) { mutableStateOf(itinerary.map { (it.str("time").orEmpty()) to (it.str("title").orEmpty()) }) }
    var money by remember(costs) { mutableStateOf(COST_KEYS.associate { (k, _) -> k to (costs.long(k) ?: 0L).toString() }) }
    var dTime by remember { mutableStateOf("") }; var dTitle by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    Text("この日の行程", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
    if (rows.isEmpty()) Muted("まだ行程がありません。時刻と内容を追加してください。", 11)
    rows.forEachIndexed { i, (t, title) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(t, { v -> rows = rows.mapIndexed { j, r -> if (j == i) v to r.second else r }; dirty = true }, placeholder = { Text("09:30", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.width(76.dp))
            OutlinedTextField(title, { v -> rows = rows.mapIndexed { j, r -> if (j == i) r.first to v else r }; dirty = true }, placeholder = { Text("移動・撮影など", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClick = { if (i > 0) { rows = rows.toMutableList().also { val x = it[i]; it[i] = it[i - 1]; it[i - 1] = x }; dirty = true } }) { Icon(Icons.Outlined.KeyboardArrowUp, "上へ") }
            IconButton(onClick = { if (i < rows.size - 1) { rows = rows.toMutableList().also { val x = it[i]; it[i] = it[i + 1]; it[i + 1] = x }; dirty = true } }) { Icon(Icons.Outlined.KeyboardArrowDown, "下へ") }
            IconButton(onClick = { rows = rows.filterIndexed { j, _ -> j != i }; dirty = true }) { Icon(Icons.Outlined.Delete, "削除") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TimeField(dTime, { dTime = it })
        OutlinedTextField(dTitle, { dTitle = it }, placeholder = { Text("行程を追加（例 奈良駅 9:12発）", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
        IconButton(onClick = { if (dTitle.isNotBlank()) { rows = (rows + (dTime to dTitle.trim())).sortedBy { it.first.ifBlank { "99:99" } }; dTime = ""; dTitle = ""; dirty = true } }) { Icon(Icons.Outlined.Add, "追加") }
    }
    Text("この日の費用（1人あたり・円）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { COST_KEYS.forEach { (k, l) -> Num(l, money[k].orEmpty(), { v -> money = money + (k to v.take(7)); dirty = true }, Modifier.weight(1f)) } }
    Text("合計 ${yen(money.values.sumOf { it.toLongOrNull() ?: 0L })}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
    SmallButton(if (busy) "保存中…" else "この日の内容を保存", {
        onSave(jsonOf("itinerary" to JsonArray(rows.map { jsonOf("time" to it.first, "title" to it.second) }), "costs" to JsonObject(money.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value.toLongOrNull() ?: 0L) })))
        dirty = false
    }, enabled = !busy && dirty, icon = Icons.Outlined.Save, primary = true)
}
