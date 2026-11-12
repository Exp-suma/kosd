package com.example.d750bridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.example.d750bridge.platform.rememberOpenMedia
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.d750bridge.MainViewModel
import com.example.d750bridge.ui.components.AnimatedCounter
import com.example.d750bridge.ui.components.CamCard
import com.example.d750bridge.ui.components.CamChip
import com.example.d750bridge.ui.components.CamIcons
import com.example.d750bridge.ui.components.GhostButton
import com.example.d750bridge.ui.components.InfoNote
import com.example.d750bridge.ui.components.LabeledSlider
import com.example.d750bridge.ui.components.PrimaryButton
import com.example.d750bridge.ui.components.SegmentedControl
import com.example.d750bridge.ui.components.SettingSwitch
import com.example.d750bridge.ui.components.SmoothProgress
import com.example.d750bridge.ui.theme.Cam
import com.example.d750bridge.ui.theme.Tnum

val TlColor = Color(0xFF8B7CFF)

private fun dur(sec: Long): String = when {
    sec < 60 -> "${sec}秒"
    sec < 3600 -> "${sec / 60}分${if (sec % 60 > 0) "${sec % 60}秒" else ""}"
    else -> "${sec / 3600}時間${(sec % 3600) / 60}分"
}

/** インターバル撮影・タイムラプス。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun TimelapsePanel(vm: MainViewModel) {
    val cfg by vm.tlCfg.collectAsStateWithLifecycle()
    val t by vm.tl.collectAsStateWithLifecycle()
    val openMedia = rememberOpenMedia()
    val locked = t.running || t.encoding

    // 次の撮影までの残り（100msごと）。このカードの中だけが更新される
    val now by produceState(System.currentTimeMillis(), t.running) {
        while (t.running) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(100) }
    }
    val period = cfg.intervalSec * 1000L
    val left = if (t.running && t.nextAtMs > 0) (t.nextAtMs - now).coerceAtLeast(0) else 0L
    val frac = if (t.running && period > 0) 1f - left.toFloat() / period else 0f

    CamCard(color = Cam.Surface2, padding = 14.dp, border = if (t.running) TlColor.copy(alpha = 0.6f) else Cam.Outline) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CountdownRing(frac.coerceIn(0f, 1f), t.running, left)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TlStat("撮影", t.shots); TlStat("コマ", t.frames); TlStat("スキップ", t.skipped)
                }
                val elapsed = if (t.running && t.startedAtMs > 0) (now - t.startedAtMs) / 1000 else 0
                Text(
                    if (t.running) "経過 ${dur(elapsed)}" else "動画にすると約 %.1f 秒（${cfg.fps}fps）".format(t.frames.toFloat() / cfg.fps),
                    style = MaterialTheme.typography.bodySmall.merge(Tnum), color = Cam.TextDim
                )
            }
        }
        AnimatedContent(t.status, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "tlStatus") {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("停止:") || it.contains("失敗")) Cam.Err else Cam.TextDim)
        }
        // 最近のコマ（新しいコマが左から滑り込む）
        AnimatedVisibility(t.strip.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(t.strip, key = { it.first }) { (no, bmp) ->
                    Box(Modifier.animateItem(fadeInSpec = tween(300), placementSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))) {
                        Image(remember(bmp) { bmp.asImageBitmap() }, "コマ$no", Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        Text("#$no", Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)), style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
        }
        AnimatedContent(t.running, label = "tlBtn") { r ->
            if (r) PrimaryButton("インターバル撮影を停止", vm::stopTimelapse, color = Cam.Err, contentColor = Color.White, icon = CamIcons.Stop, height = 50.dp)
            else PrimaryButton("インターバル撮影を開始", vm::startTimelapse, enabled = !t.encoding, color = TlColor, contentColor = Color.White, icon = CamIcons.Timer, height = 50.dp)
        }
    }

    // ---- 設定 ----
    Text("撮影間隔", style = MaterialTheme.typography.labelLarge, color = Cam.Text)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 2, 5, 10, 30, 60).forEach { v -> CamChip(dur(v.toLong()), cfg.intervalSec == v, { vm.setTlInterval(v) }, color = TlColor, enabled = !locked) }
    }
    LabeledSlider("間隔（細かく）", dur(cfg.intervalSec.toLong()), cfg.intervalSec.toFloat(), 1f..120f, { vm.setTlInterval(it.toInt()) }, enabled = !locked, accent = TlColor)
    Text("開始までの待ち時間", style = MaterialTheme.typography.labelLarge, color = Cam.Text)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 10, 60, 300, 600).forEach { v -> CamChip(if (v == 0) "すぐ" else dur(v.toLong()), cfg.startDelaySec == v, { vm.setTlStartDelay(v) }, color = TlColor, enabled = !locked) }
    }
    SettingSwitch("枚数を指定しない（停止ボタンまで）", cfg.unlimited, vm::setTlUnlimited, enabled = !locked, accent = TlColor)
    AnimatedVisibility(!cfg.unlimited) {
        LabeledSlider("撮影回数", "${cfg.count}回（約${dur(cfg.count.toLong() * cfg.intervalSec)}）", cfg.count.toFloat(), 10f..999f, { vm.setTlCount(it.toInt()) }, enabled = !locked, accent = TlColor)
    }
    SettingSwitch("カメラで撮影する", cfg.shoot, vm::setTlShoot, "毎回シャッターを切り、SDカードにフル解像度で保存", enabled = !locked, accent = TlColor)
    SettingSwitch("動画用にコマを保存する", cfg.record, vm::setTlRecord, "ライブビュー画像を端末に保存（シャッター回数を使わない）", enabled = !locked, accent = TlColor)

    // ---- 動画化 ----
    Text("タイムラプス動画", style = MaterialTheme.typography.labelLarge, color = Cam.Text)
    SegmentedControl(listOf("24fps", "30fps", "60fps"), listOf(24, 30, 60).indexOf(cfg.fps).coerceAtLeast(0), { vm.setTlFps(listOf(24, 30, 60)[it]) }, accent = TlColor)
    AnimatedVisibility(t.encoding, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmoothProgress(t.encodeProgress, color = TlColor)
            Text("エンコード中 ${(t.encodeProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall.merge(Tnum), color = Cam.TextDim)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (t.encoding) GhostButton("中止", vm::cancelEncode, Modifier.weight(1f), color = Cam.Err, height = 46.dp)
        else PrimaryButton("動画を作成（${t.frames}コマ）", vm::encodeTimelapse, Modifier.weight(1f), enabled = !t.running && t.frames >= 2, icon = CamIcons.Film, color = TlColor, contentColor = Color.White, height = 46.dp)
        GhostButton("コマを削除", vm::discardTimelapseFrames, enabled = !locked && t.frames > 0, color = Cam.Err, height = 46.dp)
    }
    AnimatedVisibility(t.lastVideo != null && !t.encoding, enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn()) {
        GhostButton("作成した動画を開く", {
            t.lastVideo?.let { u -> openMedia(u, "video/mp4") }
        }, Modifier.fillMaxWidth(), color = TlColor, icon = CamIcons.Play)
    }
    InfoNote(
        "・撮影中はアプリを前面に表示したままにしてください（画面は自動で消灯しません。バックグラウンドではOSに止められることがあります）。\n" +
            "・動画はライブビュー画像から作るため、解像度はライブビューと同じです（D750で約640×424）。高画質の動画にしたい場合は「カメラで撮影する」でSDに保存した写真を、パソコン等で動画にしてください。\n" +
            "・コマはアプリ内に保存され、アプリを閉じても残ります。続けて撮影すると後ろに追加されます。\n" +
            "・前の撮影が次の予定時刻までに終わらなかった回は「スキップ」になり、撮影時刻はずらしません。【未検証】実機での長時間動作・カメラの電池消費。"
    )
}

@Composable
private fun TlStat(label: String, v: Int) {
    Column {
        AnimatedCounter(v, Cam.Text, 18)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Cam.TextFaint)
    }
}

/** 次の撮影までの残りを示す輪。撮影の瞬間に輪が満ちて弾む。 */
@Composable
private fun CountdownRing(frac: Float, running: Boolean, leftMs: Long) {
    val inf = rememberInfiniteTransition(label = "tlRing")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "tlSpin")
    val pulse by inf.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "tlPulse")
    val col by animateColorAsState(if (running) TlColor else Cam.TextFaint, tween(300), label = "tlCol")
    Box(Modifier.size(92.dp).graphicsLayer { val s = if (running && leftMs < 400) pulse else 1f; scaleX = s; scaleY = s }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val st = 7.dp.toPx()
            val r = size.minDimension / 2 - st
            val tl = Offset(center.x - r, center.y - r)
            drawCircle(Cam.Surface3, r, style = Stroke(st))
            if (running) {
                drawArc(Brush.sweepGradient(listOf(col.copy(alpha = 0.4f), col)), -90f, 360f * frac, false, tl, Size(r * 2, r * 2), style = Stroke(st, cap = StrokeCap.Round))
                // 外周を回る小さな点（動作中の目印）
                val a = Math.toRadians(spin.toDouble())
                drawCircle(col, 2.5.dp.toPx(), Offset(center.x + (r + st * 1.3f) * kotlin.math.cos(a).toFloat(), center.y + (r + st * 1.3f) * kotlin.math.sin(a).toFloat()))
            }
        }
        AnimatedContent(if (running) ((leftMs + 999) / 1000).toInt() else -1, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "tlLeft") { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (s < 0) "—" else "$s", style = MaterialTheme.typography.headlineSmall.merge(Tnum), color = if (s < 0) Cam.TextFaint else Cam.Text)
                Text(if (s < 0) "待機" else "秒後", style = MaterialTheme.typography.labelSmall, color = Cam.TextFaint)
            }
        }
    }
}
