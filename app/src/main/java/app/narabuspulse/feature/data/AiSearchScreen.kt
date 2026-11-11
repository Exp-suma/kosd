package app.narabuspulse.feature.data

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.userMessage
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement

private const val STORAGE_KEY = "nara-bus-ai-search-history"
private val EXAMPLES = listOf(
    "先月の土曜日に運行していた車両を全部表示して",
    "過去1年間で最も多く運行された系統は？",
    "この車両の過去30日間の運行履歴を比較して",
    "今月の運行状況を先月と比較して",
    "過去に雪の日に撮影した記録を表示して",
)

/** 端末に保存する短い履歴（Web の localStorage と同じ用途）。 */
class LocalHistory(context: Context, private val key: String, private val max: Int = 8) {
    private val prefs = context.getSharedPreferences("local_history", Context.MODE_PRIVATE)
    private val ser = ListSerializer(String.serializer())
    fun read(): List<String> = runCatching { kotlinx.serialization.json.Json.decodeFromString(ser, prefs.getString(key, "[]") ?: "[]") }.getOrDefault(emptyList()).take(max)
    fun push(value: String): List<String> {
        val next = (listOf(value) + read().filter { it != value }).take(max)
        prefs.edit().putString(key, kotlinx.serialization.json.Json.encodeToString(ser, next)).apply()
        return next
    }
}

/** AI自然言語検索（Web: ai-search.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSearchScreen() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LocalHistory(context, STORAGE_KEY) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JsonElement?>(null) }
    var error by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(store.read()) }

    fun run(text: String = query) {
        val v = text.trim()
        if (v.length < 2) return
        busy = true; error = ""; result = null
        scope.launch {
            try { result = Api.rpc("ai/server-fns#nlSearch", jsonOf("query" to v)); history = store.push(v) }
            catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage().ifBlank { "検索できませんでした" } }
            finally { busy = false }
        }
    }

    ScreenList {
        item { Heading("AI自然言語検索", "日本語の質問を検索条件に変換し、実際のデータベースを検索した結果だけをもとに回答します。") }
        item {
            OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("例: 昨年の11月に雪が降った日に運行していた車両を調べて", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }))
            SmallButton("検索する", { run() }, enabled = !busy, icon = Icons.Outlined.AutoAwesome, primary = true, modifier = Modifier.padding(top = 6.dp))
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { EXAMPLES.forEach { ex -> AssistChip({ query = ex; run(ex) }, label = { Text(ex, fontSize = 11.sp) }) } }
        }
        if (history.isNotEmpty()) item {
            Column {
                Text("検索履歴", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { history.forEach { h -> AssistChip({ query = h; run(h) }, label = { Text(if (h.length > 28) h.take(28) + "…" else h, fontSize = 11.sp) }) } }
            }
        }
        if (error.isNotBlank()) item { Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        if (busy) item { Text("質問を検索条件に変換し、データベースを検索しています…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        result?.let { r ->
            val ev = r.objAt("evidence")
            item {
                Panel(title = "✨ 回答") {
                    Text(r.str("answer").orEmpty(), fontSize = 13.sp)
                    Text("根拠データ", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    KeyValue("対象期間", "${ev.str("range.from")} 〜 ${ev.str("range.to")}")
                    KeyValue("該当する運行記録", "${ev.int("matchedRuns") ?: 0}件")
                    KeyValue("該当する撮影記録", "${ev.int("matchedPhotos") ?: 0}件")
                    KeyValue("検索した運行記録", "${ev.int("scannedRuns") ?: 0}件")
                    Text("検索実行 ${BusFormat.dateTime(r.long("queriedAt"))} / 期間内の全記録から条件に一致したものを集計しています。到着時刻・乗降人数は保持していません。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val tv = ev.list("topVehicles"); val tr = ev.list("topRoutes")
                    if (tv.isNotEmpty()) { Text("車両別", fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(tv.joinToString(" / ") { "${it.str("name")} ${it.int("count")}件" }, fontSize = 11.sp) }
                    if (tr.isNotEmpty()) { Text("系統別", fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(tr.joinToString(" / ") { "${it.str("name")} ${it.int("count")}件" }, fontSize = 11.sp) }
                }
            }
            val runs = ev.items("runs")
            if (runs.isNotEmpty()) item {
                Panel(title = "該当した運行記録", action = { TextButton(onClick = { nav.go("/analytics/continuity") }) { Text("運行分析で詳しく見る", fontSize = 11.sp) } }) {
                    runs.forEachIndexed { i, run ->
                        if (i > 0) RowDivider()
                        Text(listOfNotNull(run.str("date"), run.str("vehicle"), run.str("route"), run.str("destination"),
                            "${run.long("start")?.let { BusFormat.time(it) }.orEmpty()}${run.long("end")?.let { "〜${BusFormat.time(it)}" }.orEmpty()}").filter { it.isNotBlank() }.joinToString("　"), fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    Text("表示は先頭40件までです。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
