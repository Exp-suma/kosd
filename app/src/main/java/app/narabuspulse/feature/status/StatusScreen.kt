package app.narabuspulse.feature.status

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.bus.BusGate
import app.narabuspulse.feature.bus.Vehicle
import app.narabuspulse.feature.bus.VehicleState
import app.narabuspulse.feature.bus.parseOperation
import app.narabuspulse.feature.bus.rememberNow
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.EmptyState
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.theme.Brand
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

const val MAX_FAVORITES = 20

/** お気に入り車両（端末にのみ保存。Web の lib/bus/favorites.js と同じ最大 20 台）。 */
class VehicleFavorites(private val context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    private val json = kotlinx.serialization.json.Json
    var list by mutableStateOf(load())
        private set

    private fun load(): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), prefs.getString("vehicles", "[]") ?: "[]")
    }.getOrDefault(emptyList())

    fun toggle(key: String) {
        val next = if (key in list) list - key else if (list.size >= MAX_FAVORITES) list else list + key
        prefs.edit().putString("vehicles", json.encodeToString(ListSerializer(String.serializer()), next)).apply()
        list = next
    }
}

@Composable
fun rememberVehicleFavorites(): VehicleFavorites {
    val ctx = LocalContext.current
    return remember { VehicleFavorites(ctx.applicationContext) }
}

private val FILTERS = listOf("all" to "すべて", "running" to "運行中", "idle" to "運行なし", "stale" to "要確認")
private val SORTS = listOf("name" to "車両番号順", "lastCheck" to "最終確認時刻順")

/** 車両状態一覧（Web: status.jsx + components/bus/VehicleStatusTable.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusScreen() {
    val favorites = rememberVehicleFavorites()
    var term by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("name") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf("all") }
    var destination by remember { mutableStateOf("") }

    BusGate { data ->
        val now = rememberNow(30_000)
        val collator = remember { java.text.Collator.getInstance(java.util.Locale.JAPANESE) }
        val decorated = data.vehicles.map { v -> Triple(v, v.state(data.staleMinutes, now), parseOperation(v.operation)) }
        val counts = mapOf(
            "all" to decorated.size,
            "running" to decorated.count { it.second == VehicleState.Running },
            "idle" to decorated.count { it.second == VehicleState.Idle },
            "stale" to decorated.count { it.second == VehicleState.Stale },
        )
        val stateKey = { s: VehicleState -> when (s) { VehicleState.Running -> "running"; VehicleState.Idle -> "idle"; VehicleState.Stale -> "stale"; VehicleState.Error -> "error"; VehicleState.Off -> "off" } }
        val rows = decorated.filter { (v, st, op) ->
            val needle = term.trim()
            (needle.isEmpty() || "${v.name} ${op.route} ${op.destination}".contains(needle)) &&
                (filter == "all" || stateKey(st) == filter) &&
                (!favoritesOnly || v.key in favorites.list) &&
                (route == "all" || op.route == route) &&
                (destination.isBlank() || op.destination.contains(destination.trim()))
        }.sortedWith { a, b ->
            if (sort == "lastCheck") (b.first.lastCheck ?: 0).compareTo(a.first.lastCheck ?: 0)
            else naturalCompare(a.first.name, b.first.name, collator)
        }
        val routes = data.vehicles.map { parseOperation(it.operation).route }.filter { it.isNotBlank() }.distinct().sorted()
        val favRows = rows.filter { it.first.key in favorites.list }
        val otherRows = rows.filter { it.first.key !in favorites.list }

        ScreenList {
            item {
                Column {
                    Text("車両状態一覧", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("メインシートの内容を一括で読み取り、状態を判定しています。検索・絞り込み・並べ替えは端末内で処理します。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Panel {
                    OutlinedTextField(term, { term = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("車両番号・車両名で検索", fontSize = 13.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SelectField(listOf("all" to "系統：すべて") + routes.map { it to "系統 $it" }, route, { route = it })
                        SelectField(SORTS, sort, { sort = it })
                    }
                    OutlinedTextField(destination, { destination = it }, placeholder = { Text("行先で絞り込み", fontSize = 13.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    ChipRow(FILTERS.map { (k, l) -> k to "$l ${counts[k] ?: 0}" }, filter, { filter = it }, Modifier.padding(top = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        SmallButton("お気に入りのみ", { favoritesOnly = !favoritesOnly }, icon = Icons.Filled.Star, primary = favoritesOnly)
                        Text("  ${rows.size} 台", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (rows.isEmpty()) item { EmptyState("条件に一致する車両がありません", description = "検索語や絞り込み条件を変更してください。") }
            else {
                if (favRows.isNotEmpty()) item {
                    Panel(title = "★ お気に入り（${favRows.size}/$MAX_FAVORITES）") { VehicleRows(favRows, favorites) }
                }
                item {
                    Panel(title = if (favRows.isNotEmpty()) "その他の車両" else null) {
                        if (otherRows.isEmpty()) EmptyState("該当する車両はありません") else VehicleRows(otherRows, favorites)
                    }
                }
            }
            item {
                Text(
                    "お気に入りはこの端末にのみ保存されます（最大${MAX_FAVORITES}台）。「運行なし」は運行情報が運行なしとして記録された状態、「取得エラー」は最終確認時刻が記録されていない状態です。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VehicleRows(rows: List<Triple<Vehicle, VehicleState, app.narabuspulse.feature.bus.Operation>>, favorites: VehicleFavorites) {
    rows.forEachIndexed { i, (v, st, op) ->
        if (i > 0) RowDivider()
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val fav = v.key in favorites.list
                IconButton(onClick = { favorites.toggle(v.key) }, modifier = Modifier.size(32.dp)) {
                    Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline, "${v.name} をお気に入り${if (fav) "から解除" else "に登録"}", tint = if (fav) Brand.Warning else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(v.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Tag(st.label, st.color)
            }
            Column(Modifier.padding(start = 32.dp)) {
                KeyValue("系統", op.route.ifBlank { "—" })
                KeyValue("行先", op.destination.ifBlank { if (op.noOperation) "—" else v.operation.ifBlank { "—" } })
                KeyValue("停留所", v.stop.ifBlank { "—" })
                KeyValue("最終確認", v.lastCheck?.let { "${BusFormat.dateTime(it)}（${BusFormat.elapsed(it)}）" } ?: "記録なし")
                KeyValue("最終運行", v.lastRun?.let { BusFormat.dateTime(it) } ?: "—")
                KeyValue("監視", v.onOff.ifBlank { "ON" })
            }
        }
    }
}

/** 数字を数値として比べる並べ替え（localeCompare の numeric: true 相当）。 */
fun naturalCompare(a: String, b: String, collator: java.text.Collator): Int {
    val re = Regex("(\\d+)|(\\D+)")
    val pa = re.findAll(a).map { it.value }.toList()
    val pb = re.findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(pa.size, pb.size)) {
        val x = pa[i]; val y = pb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else collator.compare(x, y)
        if (c != 0) return c
    }
    return pa.size - pb.size
}
