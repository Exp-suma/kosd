package app.narabuspulse.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.bus.BusGate
import app.narabuspulse.feature.bus.BusSnapshot
import app.narabuspulse.feature.bus.VehicleState
import app.narabuspulse.feature.bus.logKindColor
import app.narabuspulse.feature.weather.OutfitCard
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.EmptyState
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.ListRow
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.StatTile
import app.narabuspulse.ui.kit.Tag
import androidx.compose.foundation.layout.Column

private val QUICK_LINKS = listOf(
    "/status" to "車両状態一覧",
    "/analysis" to "時間帯別分析",
    "/calendar" to "稼働カレンダー",
    "/compare" to "車両比較",
    "/rides/plans" to "乗車予定・通知",
    "/rides/history" to "乗車履歴",
)

/** ホーム（Web: src/routes/_authed/index.jsx） */
@Composable
fun HomeScreen() {
    BusGate { data -> HomeBody(data) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeBody(data: BusSnapshot) {
    val nav = LocalNav.current
    val summary = remember(data) {
        val now = System.currentTimeMillis()
        val counts = data.vehicles.groupingBy { it.state(data.staleMinutes, now) }.eachCount()
        val dayAgo = now - 86_400_000L
        Triple(counts, data.logs.count { (it.at ?: 0) >= dayAgo }, data.logs.take(8))
    }
    val (counts, churn, recent) = summary

    ScreenList {
        item {
            Column {
                Text("運行状況サマリー", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("5分間隔の監視処理がスプレッドシートへ保存した情報を、そのまま集計しています。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { OutfitCard() }
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                VehicleState.entries.forEach { st ->
                    StatTile(st.label, (counts[st] ?: 0).toString(), Modifier.weight(1f), unit = "台", tone = st.color)
                }
            }
        }
        item {
            Panel(
                title = "直近の運行変更",
                subtitle = "24時間で $churn 件",
                action = { TextButton(onClick = { nav.go("/logs") }) { Text("履歴を検索", fontSize = 12.sp) } },
                padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                if (recent.isEmpty()) {
                    EmptyState("運行変更はありません", description = "読み込み期間内に運行開始・終了・行先変更の記録はありません。")
                } else recent.forEachIndexed { i, log ->
                    if (i > 0) RowDivider()
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(BusFormat.dateTime(log.at), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(104.dp))
                            Text(log.vehicle.ifEmpty { "—" }, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.width(8.dp))
                            Tag(log.kinds.joinToString("・"), logKindColor(log.kind))
                        }
                        Text("${log.from.ifEmpty { "—" }} → ${log.to.ifEmpty { "—" }}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item {
            Panel(title = "監視の状態") {
                KeyValue("シート最終更新", BusFormat.dateTime(data.updatedAt))
                KeyValue("データ取得時刻", "${BusFormat.time(data.fetchedAt)}（${BusFormat.elapsed(data.fetchedAt)}）")
                KeyValue("24時間の変更", "$churn 件")
                KeyValue("監視車両", "${data.vehicles.size} 台")
                KeyValue("メインシート", data.mainSheet)
                Text(
                    "この画面は監視シートを読むだけです。車両の実測値はデータ収集ページで蓄積します。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item {
            Panel(title = "よく使う画面", padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                QUICK_LINKS.forEachIndexed { i, (path, label) ->
                    if (i > 0) RowDivider()
                    ListRow(label, onClick = { nav.go(path) })
                }
            }
        }
        item {
            Text(
                "車両 ${data.vehicles.size} 台を監視 ・ 要確認は最終確認から ${data.staleMinutes} 分超 ・ 取得エラーは最終確認時刻なし ・ 監視OFFは監視対象外",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
