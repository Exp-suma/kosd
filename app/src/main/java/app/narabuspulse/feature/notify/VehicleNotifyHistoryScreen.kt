package app.narabuspulse.feature.notify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Logic
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.ui.kit.EmptyState
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberConfirmer
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 車両通知の履歴（Web: vehicle-notify.history.jsx + NotifyHistoryList.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleNotifyHistoryScreen() {
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    var q by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var unreadOnly by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(q) { delay(300); debounced = q }
    val query = rememberLoad("vn-history", debounced, type, unreadOnly) {
        Api.rpc("notify/vehicle-fns#getVehicleNotifyHistory", jsonOf("q" to debounced, "type" to type, "unreadOnly" to unreadOnly, "limit" to 150))
    }
    val typeOptions by produceState(emptyList<Pair<String, String>>()) {
        value = Logic.constant("notify/vehicleLabels", "NOTIFY_TYPE_OPTIONS").items().map { it.str("value").orEmpty() to it.str("label").orEmpty() }
    }
    val (types, send) = rememberVehicleLabels()
    val items = query.data.items("items")
    val unread = items.filter { it.str("read_at") == null }

    fun action(ids: List<String>, kind: String) {
        if (ids.isEmpty()) return
        val run = {
            busy = true; error = ""
            scope.launch {
                try { Api.rpc("notify/vehicle-fns#updateVehicleNotifyHistory", jsonOf("ids" to ids, "action" to kind)); query.reload() }
                catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage().ifBlank { "操作できませんでした" } }
                finally { busy = false }
            }
            Unit
        }
        if (kind == "delete") confirm.ask("${ids.size}件の通知を削除します。よろしいですか？", run) else run()
    }

    ScreenList {
        item { PageHeading("車両通知の履歴", "通知日時・車番・営業所・通知内容・対象地点・行先・系統・送信結果を確認できます。表示は本人の通知だけです。") }
        item { VehicleNotifyNav("/vehicle-notify/history") }
        item {
            Panel {
                OutlinedTextField(q, { q = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("車番・通知内容・地点・行先で検索", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    SelectField(listOf("" to "すべての通知") + typeOptions, type, { type = it })
                    CheckRow("未読のみ表示", unreadOnly) { unreadOnly = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${items.size}件（未読 ${unread.size}件）${if (query.loading) "　読み込み中…" else ""}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (unread.isNotEmpty()) SmallButton("すべて既読", { action(unread.mapNotNull { it.str("id") }, "read") }, enabled = !busy, icon = Icons.Outlined.DoneAll)
                }
            }
        }
        if (error.isNotBlank()) item { Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        if (query.error != null) item { Text("履歴を読み込めませんでした: ${query.error}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        if (items.isEmpty() && query.data != null) item { EmptyState("通知履歴がありません", description = "条件に合う通知が発生すると、ここに残ります。") }
        items.forEach { row ->
            item {
                val type0 = types.objAt(row.str("type").orEmpty())
                val st = send.objAt(row.str("send_status").orEmpty()) ?: send.objAt("stored")
                val id = row.str("id").orEmpty()
                val read = row.str("read_at") != null
                Panel {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Tag(type0.str("label") ?: row.str("type").orEmpty(), toneColor(type0.str("tone")))
                        if (!read) Tag("未読", Brand.Warning)
                        Tag(st.str("label").orEmpty(), toneColor(st.str("tone")))
                        Text(logicText("notify/vehicleLabels", "notifyTimeText", row.long("at")), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(row.str("title").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    row.str("body")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOfNotNull(
                            row.str("vehicle"), row.str("garage"), row.str("route")?.let { "系統 $it" }, row.str("destination")?.let { "行先 $it" },
                            row.str("place_name")?.let { "📍 $it" },
                        ).filter { it.isNotBlank() && !it.endsWith(" ") }.forEach { Text(it, fontSize = 11.sp) }
                        Text("✉ " + logicText("notify/vehicleLabels", "channelsText", row), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    row.str("send_detail")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        SmallButton(if (read) "未読に戻す" else "既読にする", { action(listOf(id), if (read) "unread" else "read") }, enabled = !busy, icon = Icons.Outlined.DoneAll)
                        SmallButton("", { action(listOf(id), "delete") }, enabled = !busy, icon = Icons.Outlined.Delete)
                    }
                }
            }
        }
    }
}
