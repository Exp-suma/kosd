package app.narabuspulse.feature.push

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.rememberNotifyPermission
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.theme.Brand

/**
 * アプリを閉じていても届く通知の状態（Web: components/rides/PushSubscribeCard.jsx）。
 * Web はブラウザのプッシュ通知、ネイティブ版は Firebase Cloud Messaging（FCM）で受け取る。
 */
@Composable
fun PushStatusCard() {
    val perm = rememberNotifyPermission()
    val push = PushRegistration.state
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Smartphone, null, tint = Brand.Orange)
            Text(" アプリを閉じていても届く通知", fontSize = 13.sp, modifier = androidx.compose.ui.Modifier.weight(1f))
            when (push) {
                "on" -> Tag("この端末は通知ON", Brand.Success)
                "unconfigured" -> Tag("準備中", Brand.MutedFg)
                else -> Tag("この端末は未登録", Brand.Warning)
            }
        }
        Text(
            "この端末を登録すると、雨雲の接近・乗車予定の接近や発車間近・定期レポートを、アプリを閉じていても受け取れます。通知の判定はサーバー側で5分ごとに行います。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (push == "unconfigured") Text(
            "アプリを閉じている間の通知は、Firebase の設定が完了すると有効になります。アプリを開いている間の判定と通知は、今もこの画面で行います。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (perm.state != "granted") SmallButton("端末の通知を許可する", perm.request)
    }
}

/** FCM の登録状態（Firebase 設定前は "unconfigured"）。 */
object PushRegistration {
    @Volatile var state: String = "unconfigured"
}
