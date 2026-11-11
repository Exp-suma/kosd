package app.narabuspulse.feature.notify

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.long
import app.narabuspulse.core.str
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.EmptyState
import app.narabuspulse.ui.kit.ErrorBox
import app.narabuspulse.ui.kit.Loading
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.launch

private data class Kind(val label: String, val icon: ImageVector, val color: Color)

private val KIND = mapOf(
    "report" to Kind("レポート", Icons.Outlined.NotificationsActive, Brand.Orange),
    "board" to Kind("掲示板", Icons.Outlined.Forum, Color(0xFF2F6FB0)),
    "rain" to Kind("雨雲", Icons.Outlined.Thunderstorm, Brand.Success),
    "ride" to Kind("乗車", Icons.Outlined.DirectionsBus, Brand.Orange),
)
private val FILTERS = listOf("all" to "すべて", "unread" to "未読", "rain" to "雨雲", "ride" to "乗車", "board" to "掲示板")

/** 「M/d HH:mm」（Web の when() と同じ表記）。 */
private fun whenLabel(ts: Long?) = if (ts == null || ts <= 0) "—" else "${BusFormat.shortDate(ts)} ${BusFormat.time(ts)}"

/** 通知センター（Web: notifications.jsx + components/notify/NotificationCenter.jsx） */
@Composable
fun NotificationsScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val query = rememberLoad("notifications") { Api.rpc("notify/fns#listNotifications", jsonOf()) }
    var filter by remember { mutableStateOf("all") }
    var busy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val data = query.data
    val items = data.items("items")
    val counts = mapOf(
        "all" to items.size, "unread" to (data.int("unread") ?: 0),
        "rain" to (data.int("counts.rain") ?: 0), "ride" to (data.int("counts.ride") ?: 0), "board" to (data.int("counts.board") ?: 0),
    )
    val visible = items.filter { when (filter) { "unread" -> it.str("read_at") == null; "all" -> true; else -> it.str("kind") == filter } }

    ScreenList {
        item {
            Column {
                Text("通知センター", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("雨雲の接近、乗車予定、掲示板のお知らせをまとめて表示します。未読の確認と削除ができます。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SmallButton("通知の設定", { nav.go("/settings/notify") }, icon = Icons.Outlined.Settings, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (data == null && query.error == null) { item { Loading(text = "通知を読み込んでいます…") }; return@ScreenList }
        if (data == null && query.error != null) { item { ErrorBox(query.error!!, onRetry = { query.reload() }) }; return@ScreenList }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.NotificationsActive, null, tint = Brand.Orange, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Text("未読 ${counts["unread"]} 件", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("雨雲 ${counts["rain"]}・乗車 ${counts["ride"]}・掲示板 ${counts["board"]}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SmallButton("すべて既読", {
                        busy = "mark"
                        scope.launch { try { Api.rpc("notify/fns#markAllNotifications", jsonOf()); query.reload() } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage() } finally { busy = "" } }
                    }, enabled = busy.isEmpty() && (counts["unread"] ?: 0) > 0, icon = Icons.Outlined.DoneAll)
                }
                ChipRow(FILTERS.map { (k, l) -> k to "$l ${counts[k] ?: 0}" }, filter, { filter = it }, Modifier.padding(top = 6.dp))
                Text("${visible.size} 件を表示", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (error.isNotBlank()) item {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f)); SmallButton("再取得", { query.reload() }) }
        }
        if (visible.isEmpty()) item {
            EmptyState(if (filter == "unread") "未読の通知はありません" else "この条件の通知はありません", description = "雨雲の接近・乗車予定・掲示板のお知らせが届くとここに表示されます。")
        }
        visible.forEach { item ->
            item {
                val meta = if (item.str("type") == "report") KIND["report"]!! else KIND[item.str("kind")] ?: KIND["board"]!!
                val unread = item.str("read_at") == null
                val id = item.str("id").orEmpty()
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (unread) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                        .border(1.dp, if (unread) Brand.Orange.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                        .padding(10.dp),
                ) {
                    Box(Modifier.size(26.dp).background(meta.color.copy(alpha = 0.1f), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                        Icon(meta.icon, null, tint = meta.color, modifier = Modifier.size(16.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(meta.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = meta.color)
                            Text("  ${whenLabel(item.long("at"))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (unread) Box(Modifier.padding(start = 6.dp).size(7.dp).background(Brand.Orange, CircleShape))
                            Box(Modifier.weight(1f))
                            IconButton(onClick = {
                                busy = id
                                scope.launch {
                                    try { Api.rpc("notify/fns#deleteNotification", jsonOf("kind" to item.str("kind"), "id" to id)); query.reload() }
                                    catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage().ifBlank { "通知を削除できませんでした" } }
                                    finally { busy = "" }
                                }
                            }, enabled = busy != id, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.Delete, "この通知を削除", Modifier.size(16.dp)) }
                        }
                        item.str("title")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                        Text(
                            item.str("body")?.ifBlank { null } ?: "詳細を確認する",
                            fontSize = 12.sp,
                            color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { nav.go(item.str("href")?.ifBlank { null } ?: "/notifications") },
                        )
                        item.str("vehicle_number")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
