package app.narabuspulse.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.narabuspulse.MainActivity
import app.narabuspulse.R

/**
 * 端末の通知（Web のブラウザ通知・プッシュ通知に相当）。
 * 通知を押すと route（Web のパス）を開く。
 */
object Notifier {
    const val CH_RIDES = "rides"
    const val CH_VEHICLES = "vehicles"
    const val CH_WEATHER = "weather"
    const val CH_BOARD = "board"
    const val CH_GENERAL = "general"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        listOf(
            Triple(CH_RIDES, "乗車予定", NotificationManager.IMPORTANCE_HIGH),
            Triple(CH_VEHICLES, "車両の出現・接近", NotificationManager.IMPORTANCE_HIGH),
            Triple(CH_WEATHER, "雨雲・気象", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CH_BOARD, "掲示板", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CH_GENERAL, "そのほかのお知らせ", NotificationManager.IMPORTANCE_DEFAULT),
        ).forEach { (id, name, imp) -> nm.createNotificationChannel(NotificationChannel(id, name, imp)) }
    }

    fun permission(context: Context): String = when {
        !NotificationManagerCompat.from(context).areNotificationsEnabled() ->
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) "default" else "denied"
        else -> "granted"
    }

    fun show(context: Context, title: String, body: String = "", route: String? = null, channel: String = CH_GENERAL, id: Int = (title + body).hashCode()) {
        if (permission(context) != "granted") return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (route != null) putExtra("route", route)
        }
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}

/** 通知の許可状態と、許可を求める操作。 */
class NotifyPermission(val state: String, val request: () -> Unit)

@Composable
fun rememberNotifyPermission(): NotifyPermission {
    val context = LocalContext.current
    var state by remember { mutableStateOf(Notifier.permission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state = Notifier.permission(context) }
    return NotifyPermission(state) {
        if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
