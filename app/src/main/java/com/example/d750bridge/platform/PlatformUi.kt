package com.example.d750bridge.platform

import android.Manifest
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.example.d750bridge.AppLog

/** 画面まわりの端末ごとの違い（Android版）。PC版は desktop/ 側に同じAPIの実装がある。 */
object PlatformInfo {
    const val isDesktop = false
    /** 「スマホに保存」など、端末を指す言葉。 */
    const val deviceWord = "スマホ"
}

/** 画面を消灯させない。 */
@Composable
fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

/** Wi-Fi接続に必要な権限（SSID表示のため）を求めてから処理を実行する。拒否されても処理は実行する。 */
@Composable
fun rememberPermissionRunner(): (() -> Unit) -> Unit {
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        AppLog.i("権限: " + res.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
        pending?.invoke(); pending = null
    }
    val perms = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
    }
    return { action -> pending = action; launcher.launch(perms) }
}

/** 保存した動画などを、端末の既定のアプリで開く。 */
@Composable
fun rememberOpenMedia(): (id: String, mime: String) -> Unit {
    val ctx = LocalContext.current
    return { id, mime ->
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(id), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { ctx.startActivity(i) } catch (_: Exception) { }
    }
}

/** テキストを共有（ログの送信）。 */
@Composable
fun rememberShareText(): (title: String, text: String) -> Unit {
    val ctx = LocalContext.current
    return { title, text ->
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        ctx.startActivity(Intent.createChooser(i, title))
    }
}

@Composable
fun appVersionName(): String? {
    val ctx = LocalContext.current
    return remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { null } }
}

private val labelPaint = Paint().apply {
    color = android.graphics.Color.WHITE; isAntiAlias = true
    setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
}

/** ライブビューの上に、影付きの白い文字を描く。 */
fun DrawScope.drawLabel(text: String, x: Float, y: Float, sizePx: Float) {
    labelPaint.textSize = sizePx
    drawContext.canvas.nativeCanvas.drawText(text, x, y, labelPaint)
}
