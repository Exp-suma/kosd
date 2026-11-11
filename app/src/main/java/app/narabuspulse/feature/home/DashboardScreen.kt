package app.narabuspulse.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.rain.RainWatchPanel
import app.narabuspulse.feature.weather.OutfitCard
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.rememberRpc

private val QUICK_LINKS = listOf(
    Triple("/vehicles", "車両位置", Icons.Outlined.DirectionsBus),
    Triple("/transit", "経路検索", Icons.Outlined.Train),
    Triple("/weather", "市町村の天気", Icons.Outlined.WbCloudy),
    Triple("/radar", "気象レーダー", Icons.Outlined.Thunderstorm),
    Triple("/nara/stops", "停留所一覧", Icons.Outlined.Place),
    Triple("/notifications", "通知", Icons.Outlined.Notifications),
    Triple("/settings/notify", "通知の設定", Icons.Outlined.Settings),
)

/** パーソナルダッシュボード（Web: src/routes/_authed/dashboard.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen() {
    val nav = LocalNav.current
    val summary = rememberRpc("personal/fns#getPersonalSummary", jsonOf("code" to "nara"))
    val data = summary.data
    val next = data.objAt("rides").objAt("next")
    ScreenList {
        item {
            Column {
                Text("パーソナルダッシュボード", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    data.long("generated_at")?.let { "天気・服装・雨雲・通知をまとめて表示しています（${BusFormat.time(it)} 更新）" } ?: "今日の天気・服装・雨雲・通知をまとめて確認できます。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { OutfitCard() }
        item { RainWatchPanel() }
        item {
            Panel(
                title = "通知・今日の予定",
                subtitle = if (data != null) "未読 ${data.int("notifications.unread") ?: 0} 件・乗車予定 ${data.int("rides.planned") ?: 0} 件" else "読み込み中…",
                action = { TextButton(onClick = { nav.go("/notifications") }) { Text("通知一覧", fontSize = 12.sp) } },
            ) {
                if (next != null) {
                    Text("次の乗車予定", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${next.str("departure_time")?.ifBlank { null } ?: "—"} ${next.str("route")?.takeIf { it.isNotBlank() }?.let { "系統 $it" } ?: ""}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text((next.str("boarding_stop")?.ifBlank { null } ?: "乗車停留所未設定") + (next.str("alighting_stop")?.takeIf { it.isNotBlank() }?.let { " → $it" } ?: "") + (next.str("vehicle")?.takeIf { it.isNotBlank() }?.let { "・$it" } ?: ""), fontSize = 12.sp)
                    TextButton(onClick = { nav.go("/rides/plans") }) { Text("乗車予定・通知の設定", fontSize = 12.sp) }
                } else {
                    Text("今日の乗車予定はありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { nav.go("/rides/plans") }) { Text("予定を登録", fontSize = 12.sp) }
                }
                val latest = data.objAt("notifications").items("latest")
                if (latest.isEmpty()) Text("新しい通知はありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                latest.forEachIndexed { i, row ->
                    if (i > 0) RowDivider()
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(row.str("message").orEmpty(), fontSize = 12.sp)
                        Text(BusFormat.time(row.long("at")), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Panel(title = "クイックアクセス", subtitle = "よく使う画面へ直接移動します") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_LINKS.forEach { (path, label, icon) -> SmallButton(label, { nav.go(path) }, icon = icon) }
                }
            }
        }
    }
}
