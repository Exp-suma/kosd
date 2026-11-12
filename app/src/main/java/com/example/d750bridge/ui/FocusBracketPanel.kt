package com.example.d750bridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.d750bridge.MainViewModel
import com.example.d750bridge.ui.components.AnimatedCounter
import com.example.d750bridge.ui.components.CamCard
import com.example.d750bridge.ui.components.CamIcons
import com.example.d750bridge.ui.components.InfoNote
import com.example.d750bridge.ui.components.LabeledSlider
import com.example.d750bridge.ui.components.PrimaryButton
import com.example.d750bridge.ui.components.SegmentedControl
import com.example.d750bridge.ui.components.SettingSwitch
import com.example.d750bridge.ui.theme.Cam

val FbColor = Color(0xFF4FC3F7)

/** フォーカスブラケット（深度合成用）。 */
@Composable
fun FocusBracketPanel(vm: MainViewModel) {
    val cfg by vm.fbCfg.collectAsStateWithLifecycle()
    val fb by vm.fb.collectAsStateWithLifecycle()
    val focus by vm.focusUi.collectAsStateWithLifecycle()
    val locked = fb.running
    val total = if (fb.running) fb.total else cfg.shots
    val frac by animateFloatAsState(if (total > 0) fb.done.toFloat() / total else 0f, tween(400, easing = FastOutSlowInEasing), label = "fbFrac")

    CamCard(color = Cam.Surface2, padding = 14.dp, border = if (fb.running) FbColor.copy(alpha = 0.6f) else Cam.Outline) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // ピント位置の移動を表す「レール」: 撮影済みのコマが点で埋まっていく
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val st = 7.dp.toPx(); val r = size.minDimension / 2 - st
                    drawCircle(Cam.Surface3, r, style = androidx.compose.ui.graphics.drawscope.Stroke(st))
                    drawArc(FbColor, -90f, 360f * frac, false, Offset(center.x - r, center.y - r), androidx.compose.ui.geometry.Size(r * 2, r * 2),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(st, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedCounter(fb.done, Cam.Text, 22)
                    Text("/ $total", style = MaterialTheme.typography.labelSmall, color = Cam.TextFaint)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FocusRail(cfg.shots, if (fb.running) fb.done else 0, cfg.towardFar)
                Text("1枚ごと ${cfg.step} ステップ · 合計 ${cfg.step * (cfg.shots - 1)} ステップ${if (cfg.towardFar) "遠く" else "近く"}へ",
                    style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
            }
        }
        AnimatedContent(fb.status, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "fbStatus") {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("停止")) Cam.Err else Cam.TextDim)
        }
        AnimatedContent(fb.running, label = "fbBtn") { r ->
            if (r) PrimaryButton("中止", vm::stopFocusBracket, color = Cam.Err, contentColor = Color.White, icon = CamIcons.Stop, height = 50.dp)
            else PrimaryButton("フォーカスブラケット開始", vm::startFocusBracket, color = FbColor, contentColor = Color(0xFF001F2A), icon = CamIcons.Focus, height = 50.dp)
        }
    }
    LabeledSlider("撮影枚数", "${cfg.shots}枚", cfg.shots.toFloat(), 2f..100f, { vm.setFbShots(it.toInt()) }, enabled = !locked, accent = FbColor)
    LabeledSlider("1枚ごとのピント移動量", "${cfg.step} ステップ", cfg.step.toFloat(), 1f..300f, { vm.setFbStep(it.toInt()) }, enabled = !locked, accent = FbColor)
    Text(
        "目安: 現在の焦点距離の微調整量は ${focus.fine} ステップ（${if (focus.calibrated) "校正済み" else "未校正"}）。被写界深度が浅いマクロでは小さく、風景では大きくします。",
        style = MaterialTheme.typography.bodySmall, color = Cam.TextDim
    )
    SegmentedControl(listOf("近い → 遠い", "遠い → 近い"), if (cfg.towardFar) 0 else 1, { if (!locked) vm.setFbTowardFar(it == 0) }, accent = FbColor)
    LabeledSlider("撮影後の待ち時間（書き込み・ブレ）", "${cfg.settleMs} ms", cfg.settleMs.toFloat(), 0f..2000f, { vm.setFbSettle(it.toInt()) }, enabled = !locked, accent = FbColor)
    SettingSwitch("終わったら開始位置へピントを戻す", cfg.returnToStart, vm::setFbReturn, enabled = !locked, accent = FbColor)
    InfoNote(
        "・最初のピント位置（手前側から始めるなら手前の被写体）にAFまたは手動で合わせてから開始します。\n" +
            "・開始時にカメラをMFへ切り替え（できない場合は中止）、終了後に元のフォーカスモードへ戻します。CanonはレンズのAF/MFスイッチをMFにしてください。\n" +
            "・ライブビューを自動で開始します（MF駆動はライブビュー中のみ受け付ける機種があるため）。\n" +
            "・「方向の確認」（ピント解析・補正）でNear/Farを確認しておくと、向きが正しくなります。\n" +
            "・撮った写真の合成（深度合成）はパソコンのソフト等で行ってください。【未検証】MF駆動の1ステップの量は機種・レンズで異なります。"
    )
}

/** ピント位置のレール。コマ位置の点が、撮影済みになると色が付く。 */
@Composable
private fun FocusRail(shots: Int, done: Int, towardFar: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
        val n = shots.coerceIn(2, 40)
        val y = size.height / 2
        drawLine(Cam.Surface3, Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
        for (i in 0 until n) {
            val f = i / (n - 1f)
            val x = (if (towardFar) f else 1f - f) * (size.width - 10.dp.toPx()) + 5.dp.toPx()
            val shotIndex = (i * (shots - 1f) / (n - 1f)).toInt()
            val isDone = shotIndex < done
            drawCircle(if (isDone) FbColor else Cam.TextFaint.copy(alpha = 0.6f), if (isDone) 4.dp.toPx() else 2.5.dp.toPx(), Offset(x, y))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("近", style = MaterialTheme.typography.labelSmall, color = Cam.TextFaint)
        Box(Modifier.weight(1f))
        Text("遠", style = MaterialTheme.typography.labelSmall, color = Cam.TextFaint)
    }
}
