package app.narabuspulse.feature.fleet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.bool
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.EmptyState
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.StatTile
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.launch

@Composable
private fun PageTitle(title: String, description: String) {
    Column {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** お気に入り車両・追跡（Web: fleet.favorites.jsx + components/fleet/FavoriteVehicleList.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FleetFavoritesScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val query = rememberLoad("fleet-fav") { Api.rpc("fleet/fns#listFleetFavorites", jsonOf()) }
    var busyId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val data = query.data
    val items = data.items("items")
    val running = items.count { it.bool("running") == true }

    ScreenList {
        item { PageTitle("お気に入り車両・追跡", "登録した車両の現在の運行状況をまとめて確認できます。通知を有効にした車両は、運行開始・指定地点への接近を既存の車両出現通知AIが判定します。") }
        item { FleetNav("/fleet/favorites") }
        if (message.isNotBlank()) item { Text(message, fontSize = 12.sp) }
        if (query.error != null) item { Text("お気に入りを読み込めませんでした: ${query.error}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                StatTile("登録車両", items.size.toString(), Modifier.weight(1f), "台")
                StatTile("運行中", running.toString(), Modifier.weight(1f), "台", if (running > 0) Brand.Success else Brand.MutedFg)
                StatTile("位置を取得できない", (items.size - running).toString(), Modifier.weight(1f), "台", if (items.size - running > 0) Brand.Warning else Brand.MutedFg)
                StatTile("最終取得", fleetClock(data.long("fetchedAt")), Modifier.weight(1f), tone = if (data.bool("stale") == true) Brand.Warning else null)
                StatTile("公式データ", (data.long("scanned") ?: 0).toString(), Modifier.weight(1f), "台")
            }
        }
        if (query.loading && data == null) item { Text("読み込んでいます…", fontSize = 12.sp) }
        if (data != null && items.isEmpty()) item {
            EmptyState("お気に入り車両はまだありません", description = "車番検索の結果の★、または車両詳細画面の「お気に入り」から登録すると、ここで現在の運行状況をまとめて確認できます。", actionLabel = "車番検索へ", onAction = { nav.go("/fleet") })
        }
        items.forEach { item ->
            item {
                val live = item.objAt("live")
                val id = item.str("id").orEmpty()
                Panel {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(item.str("plate_no").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        item.str("car_code")?.let { Text("車両コード $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (live != null) Tag(if (live.bool("stale") == true) "位置情報が古い" else "運行中", if (live.bool("stale") == true) Brand.Warning else Brand.Success)
                        else Tag("現在位置を取得できません", Brand.MutedFg)
                        val notify = item.bool("notify_start") == true || item.bool("notify_approach") == true
                        Icon(if (notify) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff, if (notify) "通知あり" else "通知なし", Modifier.size(16.dp), tint = if (notify) Brand.Orange else Brand.MutedFg)
                    }
                    if (live != null) {
                        Text("系統 ${live.str("route")?.ifBlank { null } ?: "不明"}${live.str("destination")?.let { " ・ $it 行" } ?: ""}", fontSize = 12.sp)
                        Text("位置 ${live.str("stop")?.ifBlank { null } ?: "停留所情報なし"}", fontSize = 12.sp)
                        Text("最終更新 ${fleetClock(live.long("at"))}（${fleetAge(live.long("at"))}）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Text("公式バスロケーションに現在出ていないため、現在の系統・行先・位置は表示できません。${item.str("note")?.let { "（$it）" } ?: ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    item.str("label")?.takeIf { it.isNotBlank() }?.let { Text("呼び名: $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        val code = item.str("car_code")
                        if (code != null) SmallButton("詳細", { nav.go("/fleet/$code") }) else SmallButton("図鑑", { nav.go("/vehicle-catalog/${item.str("plate_no")}") })
                        SmallButton("追跡を終了", {
                            busyId = id; message = ""
                            scope.launch {
                                try {
                                    Api.rpc("fleet/fns#deleteFleetFavorite", jsonOf("id" to id, "stopTracking" to true))
                                    message = "${item.str("plate_no")} の追跡を終了しました（出現通知も停止しました）"
                                    query.reload()
                                } catch (e: Throwable) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    message = e.userMessage().ifBlank { "追跡を終了できませんでした" }
                                } finally { busyId = "" }
                            }
                        }, enabled = busyId != id, icon = Icons.Outlined.Delete)
                    }
                }
            }
        }
        item { Text("運行していない車両・公式バスロケで確認できない車両は、現在位置を表示しません。通知の条件（対象地点・範囲・通知する種類）は「車両出現通知」画面で変更できます。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** 全車両ログ（Web: fleet.logs.jsx + RunLogFilters / RunLogList） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FleetLogsScreen() {
    var date by remember { mutableStateOf(BusFormat.dayKey(System.currentTimeMillis())) }
    var vehicle by remember { mutableStateOf("") }
    val query = rememberLoad("fleet-logs", date, vehicle.trim()) { Api.rpc("fleet/fns#listVehicleRunLogs", jsonOf("date" to date, "vehicle" to vehicle.trim(), "limit" to 100)) }
    val data = query.data
    ScreenList {
        item { PageTitle("全車両ログ", "指定した日に記録された全車両の運用記録を、新しい順に並べます。系統・行先・始発停留所・最後に確認した停留所を車両ごとに確認できます。") }
        item { FleetNav("/fleet/logs") }
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                StatTile("運用記録", (data.long("total") ?: 0).toString(), Modifier.weight(1f), "件")
                StatTile("運行中", (data.long("running") ?: 0).toString(), Modifier.weight(1f), "件", Brand.Success)
                StatTile("終了", (data.long("ended") ?: 0).toString(), Modifier.weight(1f), "件", Brand.MutedFg)
            }
        }
        item {
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    DateField(date, { date = it }, label = "日付")
                    SmallButton("更新", { query.reload() }, enabled = !query.loading, icon = Icons.Outlined.Refresh)
                }
                OutlinedTextField(vehicle, { vehicle = it }, label = { Text("車番") }, placeholder = { Text("例 奈良200か196") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        if (query.error != null) item { Text(query.error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        item {
            val rows = data.items("items")
            Panel {
                if (rows.isEmpty()) Text(if (query.loading) "読み込み中…" else "この条件の運用記録はありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                rows.forEachIndexed { i, row ->
                    if (i > 0) RowDivider()
                    val runningRow = row.str("status") == "running"
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.str("vehicle")?.ifBlank { null } ?: "車番不明", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("系統 ${row.str("route")?.ifBlank { null } ?: "—"} · ${row.str("destination")?.ifBlank { null } ?: "行先不明"}", fontSize = 12.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Tag(if (runningRow) "運行中" else "終了", if (runningRow) Brand.Success else Brand.MutedFg)
                                Text("${fleetClock(row.long("start_at"))}〜${row.long("end_at")?.let { fleetClockPlain(it) } ?: "運行中"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("始発 ${row.str("from_stop")?.ifBlank { null } ?: "—"}　最終 ${row.str("last_stop")?.ifBlank { null } ?: row.str("to_stop")?.ifBlank { null } ?: "—"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (data.bool("hasMore") == true) item { Text("直近100件を表示しています。日付や車番で絞り込んでください。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
