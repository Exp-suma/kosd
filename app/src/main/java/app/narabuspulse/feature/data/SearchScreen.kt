package app.narabuspulse.feature.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.notify.CheckRow
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.StatStrip
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.kit.rememberLoad
import kotlinx.serialization.json.JsonElement

private val WEEKDAYS = listOf("日", "月", "火", "水", "木", "金", "土")
private val RATINGS = listOf("" to "指定なし", "favorite" to "お気に入り", "success" to "成功", "failed" to "失敗", "none" to "未評価")
private val RATING_LABEL = mapOf("favorite" to "お気に入り", "success" to "成功", "failed" to "失敗", "none" to "未評価")

private fun splitText(t: String) = t.split(Regex("[,、\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.take(20)

private data class Draft(
    val operator: String = "and", val weekdays: List<Int> = emptyList(), val vehicles: List<String> = emptyList(), val routes: List<String> = emptyList(),
    val stopsText: String = "", val destinationsText: String = "", val weatherText: String = "", val timeFrom: String = "", val timeTo: String = "",
    val minDuration: String = "", val maxDuration: String = "", val minTemp: String = "", val maxTemp: String = "", val rating: String = "", val includePhotos: Boolean = true,
)

private fun buildPayload(range: DateRange, d: Draft) = jsonOf(
    "filters" to jsonOf(
        "from" to range.from, "to" to range.to, "weekdays" to d.weekdays, "vehicles" to d.vehicles, "routes" to d.routes,
        "stops" to splitText(d.stopsText), "destinations" to splitText(d.destinationsText), "weather" to splitText(d.weatherText),
        "timeFrom" to d.timeFrom.ifBlank { null }, "timeTo" to d.timeTo.ifBlank { null },
        "minDuration" to d.minDuration.toDoubleOrNull(), "maxDuration" to d.maxDuration.toDoubleOrNull(),
        "minTemp" to d.minTemp.toDoubleOrNull(), "maxTemp" to d.maxTemp.toDoubleOrNull(), "rating" to d.rating.ifBlank { null },
    ),
    "operator" to d.operator,
    "includePhotos" to d.includePhotos,
)

private fun <T : Comparable<T>> List<T>.toggle(v: T) = if (v in this) this - v else (this + v).sorted()

/** 自由条件検索（Web: search.jsx + SearchFilterForm / SearchResults） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen() {
    val today = jstTodayKey()
    var range by remember { mutableStateOf(DateRange(addDays(today, -29), today)) }
    var draft by remember { mutableStateOf(Draft()) }
    var applied by remember { mutableStateOf(Draft()) }
    val options by produceState<JsonElement?>(null) { value = runCatching { Api.rpc("analytics/server-fns#getSearchOptions") }.getOrNull() }
    val query = rememberLoad("search", range, applied) { Api.rpc("analytics/server-fns#searchData", buildPayload(range, applied)) }
    val data = query.data

    ScreenList {
        item { Heading("自由条件検索", "期間・曜日・車両・系統・停留所・時刻・運行時間などの条件を組み合わせて、蓄積データから該当する運行と撮影記録を探します。") }
        item { RangePicker(range, { range = it }) }
        item {
            Panel {
                Text("条件の組み合わせ", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("and" to "すべて満たす（AND）", "or" to "いずれかを満たす（OR）").forEach { (v, l) ->
                        FilterChip(draft.operator == v, { draft = draft.copy(operator = v) }, label = { Text(l, fontSize = 11.sp) })
                    }
                }
                Text("曜日", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WEEKDAYS.forEachIndexed { i, l -> FilterChip(i in draft.weekdays, { draft = draft.copy(weekdays = draft.weekdays.toggle(i)) }, label = { Text(l, fontSize = 11.sp) }) }
                }
                Text("車両（候補から選択・複数可）", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                val vs = options.list("vehicles").map { it.text }
                if (vs.isEmpty()) Text("運行記録がまだありません", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { vs.forEach { v -> FilterChip(v in draft.vehicles, { draft = draft.copy(vehicles = draft.vehicles.toggle(v)) }, label = { Text(v, fontSize = 11.sp) }) } }
                Text("系統（候補から選択・複数可）", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                val rs = options.list("routes").map { it.text }
                if (rs.isEmpty()) Text("系統の記録がまだありません", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { rs.forEach { r -> FilterChip(r in draft.routes, { draft = draft.copy(routes = draft.routes.toggle(r)) }, label = { Text(r, fontSize = 11.sp) }) } }
                OutlinedTextField(draft.stopsText, { draft = draft.copy(stopsText = it) }, label = { Text("停留所（カンマ区切り）", fontSize = 12.sp) }, placeholder = { Text("例: 奈良駅, 近鉄奈良") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.destinationsText, { draft = draft.copy(destinationsText = it) }, label = { Text("行先（カンマ区切り）", fontSize = 12.sp) }, placeholder = { Text("例: 高山") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    TimeField(draft.timeFrom, { draft = draft.copy(timeFrom = it) }, label = "運行開始")
                    TimeField(draft.timeTo, { draft = draft.copy(timeTo = it) }, label = "〜")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("運行時間の下限（分）", draft.minDuration, Modifier.weight(1f)) { draft = draft.copy(minDuration = it) }
                    NumField("運行時間の上限（分）", draft.maxDuration, Modifier.weight(1f)) { draft = draft.copy(maxDuration = it) }
                }
                CheckRow("撮影記録も検索する", draft.includePhotos) { draft = draft.copy(includePhotos = it) }
                if (draft.includePhotos) {
                    OutlinedTextField(draft.weatherText, { draft = draft.copy(weatherText = it) }, label = { Text("天気", fontSize = 12.sp) }, placeholder = { Text("雨, 雪") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumField("気温の下限（℃）", draft.minTemp, Modifier.weight(1f)) { draft = draft.copy(minTemp = it) }
                        NumField("気温の上限（℃）", draft.maxTemp, Modifier.weight(1f)) { draft = draft.copy(maxTemp = it) }
                    }
                    SelectField(RATINGS, draft.rating, { draft = draft.copy(rating = it) }, label = "評価")
                }
                SmallButton(if (query.loading) "検索しています…" else "この条件で検索", { applied = draft }, enabled = !query.loading, primary = true, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (query.loading) item { Text("検索しています…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (query.error != null) item { Text(query.error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        if (!query.loading && data != null) {
            item {
                StatStrip(listOf(
                    Triple("該当する運行", (data.int("totals.runs") ?: 0).toString(), "件"),
                    Triple("該当する撮影", (data.int("totals.photos") ?: 0).toString(), "枚"),
                    Triple("検索した運行", (data.int("totals.scannedRuns") ?: 0).toString(), "件"),
                    Triple("検索条件", if (data.str("operator") == "or") "OR" else "AND", null),
                ))
                data.str("note")?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            }
            item {
                val runs = data.items("runs")
                Panel(title = "運行記録（${data.int("totals.runs") ?: 0} 件）") {
                    if (runs.isEmpty()) Text("条件に一致する運行記録がありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    runs.forEachIndexed { i, run ->
                        if (i > 0) RowDivider()
                        Column(Modifier.padding(vertical = 5.dp)) {
                            Text("${run.str("date")}　${run.str("vehicle")}　系統 ${run.str("route")?.ifBlank { null } ?: "—"}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("${run.str("destination")?.ifBlank { null } ?: "—"}／${run.str("fromStop")?.ifBlank { null } ?: "—"} → ${run.str("toStop")?.ifBlank { null } ?: "—"}", fontSize = 11.sp)
                            Text("${BusFormat.time(run.long("start"))} 〜 ${BusFormat.time(run.long("end"))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if ((data.int("totals.runs") ?: 0) > runs.size) Text("表示は先頭 ${runs.size} 件です。条件を追加して絞り込んでください。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if ((data.int("totals.photos") ?: 0) > 0) item {
                Panel(title = "撮影記録（${data.int("totals.photos")} 枚）") {
                    data.items("photos").forEachIndexed { i, p ->
                        if (i > 0) RowDivider()
                        Text(listOf(p.str("captured_date").orEmpty(), p.str("place_name")?.ifBlank { null } ?: "場所未設定", p.str("vehicle") ?: "—", p.str("route") ?: "—", p.str("weather")?.ifBlank { null } ?: "天気未設定", RATING_LABEL[p.str("rating")] ?: "未評価").joinToString("　"), fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) =
    OutlinedTextField(value, { onChange(it.filter { c -> c.isDigit() || c == '-' || c == '.' }) }, label = { Text(label, fontSize = 11.sp) }, singleLine = true, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
