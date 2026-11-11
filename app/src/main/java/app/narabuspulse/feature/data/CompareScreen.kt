package app.narabuspulse.feature.data

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.bus.BusGate
import app.narabuspulse.feature.bus.BusSnapshot
import app.narabuspulse.feature.bus.parseOperation
import app.narabuspulse.feature.status.rememberVehicleFavorites
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.theme.Brand

private const val MAX_COMPARE = 4

/** お気に入り・車両比較（Web: compare.jsx） */
@Composable
fun CompareScreen() {
    var selected by remember { mutableStateOf(listOf<String>()) }
    BusGate { data -> CompareBody(data, selected) { selected = it } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompareBody(data: BusSnapshot, selected: List<String>, setSelected: (List<String>) -> Unit) {
    val favorites = rememberVehicleFavorites()
    val now = System.currentTimeMillis()
    val changeCounts = remember(data.logs) { data.logs.filter { it.vehicle.isNotBlank() }.groupingBy { it.vehicle }.eachCount() }
    fun toggle(name: String) = setSelected(if (name in selected) selected - name else if (selected.size >= MAX_COMPARE) selected else selected + name)
    val picked = selected.mapNotNull { n -> data.vehicles.firstOrNull { it.name == n } }
    val favVehicles = data.vehicles.filter { it.key in favorites.list }

    ScreenList {
        item { Heading("お気に入り・車両比較", "最大4台まで選んで、保存済みの情報を横並びで比較します。車両ごとに個別のAPI呼び出しは行いません。") }
        if (favVehicles.isNotEmpty()) item {
            Panel(title = "★ お気に入りから選ぶ") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { favVehicles.forEach { v -> FilterChip(v.name in selected, { toggle(v.name) }, label = { Text(v.name, fontSize = 11.sp) }) } }
            }
        }
        item {
            Panel(title = "比較する車両を選択", action = { Text("${selected.size}/$MAX_COMPARE 台  ", fontSize = 11.sp) }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { data.vehicles.forEach { v -> FilterChip(v.name in selected, { toggle(v.name) }, label = { Text(v.name, fontSize = 11.sp) }) } }
            }
        }
        if (picked.isEmpty()) item { Text("車両を選択すると比較表が表示されます。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else item {
            Panel {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    val labelW = 112.dp; val colW = 150.dp
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("項目", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(labelW))
                        picked.forEach { v ->
                            Row(Modifier.width(colW), verticalAlignment = Alignment.CenterVertically) {
                                Text(v.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                val fav = v.key in favorites.list
                                IconButton(onClick = { favorites.toggle(v.key) }, modifier = Modifier.size(30.dp)) {
                                    Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline, "${v.name} をお気に入り切替", tint = if (fav) Brand.Warning else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    @Composable fun R(label: String, cell: @Composable (app.narabuspulse.feature.bus.Vehicle) -> Unit) {
                        Row(Modifier.padding(vertical = 5.dp)) {
                            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(labelW))
                            picked.forEach { v -> Column(Modifier.width(colW)) { cell(v) } }
                        }
                    }
                    R("運行状態") { v -> val st = v.state(data.staleMinutes, now); Tag(st.label, st.color) }
                    R("現在の系統") { v -> Text(parseOperation(v.operation).route.ifBlank { "—" }, fontSize = 12.sp) }
                    R("行先") { v -> Text(parseOperation(v.operation).destination.ifBlank { "—" }, fontSize = 12.sp) }
                    R("現在の停留所") { v -> Text(v.stop.ifBlank { "—" }, fontSize = 12.sp) }
                    R("最終確認") { v ->
                        if (v.lastCheck != null) { Text(BusFormat.dateTime(v.lastCheck), fontSize = 12.sp); Text(BusFormat.elapsed(v.lastCheck), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        else Text("記録なし", fontSize = 12.sp)
                    }
                    R("最終運行") { v -> Text(v.lastRun?.let { BusFormat.dateTime(it) } ?: "—", fontSize = 12.sp) }
                    R("運行変更回数（${data.logWindowDays()}日）") { v -> Text("${changeCounts[v.name] ?: 0} 件", fontSize = 12.sp) }
                }
            }
        }
    }
}
