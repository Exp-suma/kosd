package com.example.d750bridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.d750bridge.BurstMode
import com.example.d750bridge.MainViewModel
import com.example.d750bridge.ai.NightMode
import com.example.d750bridge.ai.Subject
import com.example.d750bridge.camera.CameraBrand
import com.example.d750bridge.camera.FocusKind
import com.example.d750bridge.focus.FocusProfileStore
import com.example.d750bridge.track.TrackState
import com.example.d750bridge.ui.components.CamCard
import com.example.d750bridge.ui.components.CamChip
import com.example.d750bridge.ui.components.CamIcons
import com.example.d750bridge.ui.components.ExpandableCard
import com.example.d750bridge.ui.components.GhostButton
import com.example.d750bridge.ui.components.InfoNote
import com.example.d750bridge.ui.components.LabeledSlider
import com.example.d750bridge.ui.components.PrimaryButton
import com.example.d750bridge.ui.components.SegmentedControl
import com.example.d750bridge.ui.components.SettingSwitch
import com.example.d750bridge.ui.components.SmoothProgress
import com.example.d750bridge.ui.components.StatusPill
import com.example.d750bridge.ui.theme.Cam
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ToolsScreen(vm: MainViewModel, listState: LazyListState) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val aiCfg by vm.aiCfg.collectAsStateWithLifecycle()
    val focusUi by vm.focusUi.collectAsStateWithLifecycle()
    val pred by vm.predCfg.collectAsStateWithLifecycle()
    val burst by vm.burstUi.collectAsStateWithLifecycle()
    val speedOn by vm.speedOn.collectAsStateWithLifecycle()
    val orbis by vm.orbisCfg.collectAsStateWithLifecycle()
    val caps = ui.camera?.caps
    val mfOk = caps?.mfDrive != false
    val mfReason = "${ui.camera?.title ?: ""} は通信でのMF駆動に未対応"

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("AIツール", style = MaterialTheme.typography.headlineMedium, color = Cam.Text)
                Text("被写体検出・ピント解析・追跡・速度測定（ライブビュー中に動作）", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
            }
        }
        item {
            ExpandableCard(
                CamIcons.Sparkle, "被写体AF支援", "${aiCfg.subject.label} · ${if (aiCfg.detectEnabled) "検出ON" else "検出OFF"}${if (aiCfg.pinMode) " · 置きピン" else ""}",
                accent = Cam.Ai, initiallyExpanded = true, badge = if (aiCfg.detectEnabled) "ON" else null
            ) { AiAfPanel(vm) }
        }
        item {
            val tl by vm.tl.collectAsStateWithLifecycle()
            ExpandableCard(
                CamIcons.Timer, "インターバル・タイムラプス",
                if (tl.running) "撮影中 · ${tl.shots}枚 / コマ${tl.frames}" else if (tl.frames > 0) "保存済みのコマ ${tl.frames}枚" else "一定間隔で撮影して動画に",
                accent = TlColor, badge = if (tl.running) "撮影中" else if (tl.encoding) "作成中" else null
            ) { TimelapsePanel(vm) }
        }
        item {
            val fb by vm.fb.collectAsStateWithLifecycle()
            ExpandableCard(
                CamIcons.Burst, "フォーカスブラケット", if (fb.running) "撮影中 ${fb.done} / ${fb.total}" else "ピントをずらしながら連続撮影（深度合成用）",
                accent = FbColor, enabled = mfOk, disabledReason = mfReason, badge = if (fb.running) "撮影中" else null
            ) { FocusBracketPanel(vm) }
        }
        item {
            ExpandableCard(
                CamIcons.Focus, "ピント解析・補正", "判定: ${focusUi.verdict}${if (focusUi.running) "（実行中）" else ""}",
                enabled = mfOk, disabledReason = mfReason, badge = if (focusUi.running) "実行中" else null
            ) { FocusPanel(vm) }
        }
        item {
            ExpandableCard(CamIcons.Track, "遮蔽物に強い追跡", "柱・木・他の車両に隠れても位置を予測", accent = Cam.Ai) { TrackPanel(vm) }
        }
        item {
            ExpandableCard(
                CamIcons.Radar, "AI予測フォーカス", if (pred.enabled) "ON · 先読み ${pred.leadMs}ms" else "距離推定 → 先にレンズを動かす",
                accent = Cam.Ai, enabled = mfOk, disabledReason = mfReason, badge = if (pred.enabled) "ON" else null
            ) { PredPanel(vm) }
        }
        item {
            ExpandableCard(CamIcons.Burst, "連写", "${burst.state} · 指示${burst.shots}回", badge = if (ui.captureBusy) "撮影中" else null) { BurstPanel(vm) }
        }
        item {
            ExpandableCard(CamIcons.Speed, "車両速度測定", if (speedOn) "測定ON（推定値）" else "推定値・法的に有効な測定ではありません", accent = Cam.Warn, badge = if (speedOn) "ON" else null) { SpeedPanel(vm) }
        }
        item {
            ExpandableCard(CamIcons.Bolt, "オービスモード", "娯楽・撮影・技術検証用", accent = Cam.Rec, badge = if (orbis.enabled) "ON" else null) { OrbisPanel(vm) }
        }
        item {
            val nikon = ui.brand == CameraBrand.NIKON
            ExpandableCard(CamIcons.Chip, "AF微調整値の調査", "PTPベンダー拡張プロパティの一覧（Nikon）", enabled = nikon, disabledReason = "Nikon（PTP）接続時のみ") { FineTunePanel(vm) }
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(
        text, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Cam.Bg.copy(alpha = 0.6f)).padding(10.dp),
        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Cam.TextDim, lineHeight = 15.sp
    )
}

@Composable
private fun SubTitle(text: String) { Text(text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge, color = Cam.Text) }

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cam.Accent, unfocusedBorderColor = Cam.Outline, focusedLabelColor = Cam.Accent, cursorColor = Cam.Accent,
    focusedContainerColor = Cam.Bg, unfocusedContainerColor = Cam.Bg
)

// ----------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiAfPanel(vm: MainViewModel) {
    val cfg by vm.aiCfg.collectAsStateWithLifecycle()
    val ai by vm.aiUi.collectAsStateWithLifecycle()
    val plan by vm.aiPlan.collectAsStateWithLifecycle()
    val pin by vm.pin.collectAsStateWithLifecycle()
    val landscape = cfg.subject == Subject.LANDSCAPE
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Subject.values().forEach { s -> CamChip(s.label, cfg.subject == s, { vm.setSubject(s) }, color = Cam.Ai) }
    }
    AnimatedContent(cfg.subject, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "subjDesc") { s ->
        Text(
            when (s) {
                Subject.PERSON -> "人: 顔を検出してAF（水色の枠=顔/人物、十字=AF点）。顔が見つからないときは人物枠の頭部位置で代用します。"
                Subject.TRAIN -> "電車: 横に動いているときは進行方向の先頭側、正面から来るときは中央にAF点を置きます。"
                Subject.BUS -> "バス: 枠の中央（縦位置は下のスライダー）にAF点を置きます。"
                Subject.LANDSCAPE -> "風景: 検出は使わず、画面中央寄り（空を避けた範囲）でコントラストが一番高い点にAFします。自動追従はしません。"
            }, style = MaterialTheme.typography.bodySmall, color = Cam.TextDim
        )
    }
    SettingSwitch("被写体検出・AF位置の自動決定", cfg.detectEnabled, vm::setAiDetect, "ライブビュー中に端末内のAIで検出", accent = Cam.Ai)
    SettingSwitch("追従AF", cfg.autoAf, vm::setAiAuto, "AF点が動く／被写体が近づく・遠ざかると自動でAF（既定OFF）", enabled = cfg.detectEnabled && !cfg.pinMode && !landscape, accent = Cam.Ai)
    AnimatedVisibility(cfg.subject == Subject.BUS) {
        SettingSwitch("トラック・乗用車も対象", cfg.includeTruckCar, vm::setAiTruckCar, "OFF=バスのみ", accent = Cam.Ai)
    }

    SubTitle("夜間・暗所AF")
    SegmentedControl(NightMode.values().map { it.label }, cfg.nightMode.ordinal, { vm.setNightMode(NightMode.values()[it]) }, accent = Cam.Warn)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusPill(if (ai.night) "夜間補正 作動中" else "待機", if (ai.night) Cam.Warn else Cam.TextDim, pulsing = ai.night)
        Text("平均輝度 ${ai.luma}/255（自動は65未満）", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
    }
    InfoNote("作動中は、検出用の映像を明るくして検出し、AF点を「枠内でコントラストが高い点」に寄せ、AFが決まらなければ次の候補点で最大3回やり直します。")

    SubTitle("置きピン")
    SettingSwitch("置きピンモード", cfg.pinMode, vm::setPinMode, "映像をタップした位置でAF→固定。被写体が来てもAFは追従しません", accent = Cam.Pin)
    SettingSwitch("ピン設置後にカメラをMFへ切替", cfg.pinLockMf, vm::setPinLockMf, "撮影時にカメラがAFし直すのを防ぐ", accent = Cam.Pin)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("ピンを外す", vm::releasePin, enabled = pin != null, color = Cam.Pin, icon = CamIcons.Pin)
        Text(
            pin?.let { "設置済み: ${it.lockNote}" } ?: if (cfg.pinMode) "ライブビューをタップして置いてください" else "OFF",
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = if (pin != null) Cam.Pin else Cam.TextDim
        )
    }

    SubTitle("検出・AF点")
    Text("モデル: ${ai.modelStatus}", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
    Text(
        "検出: ${ai.detCount}件 / 1フレーム ${ai.detectMs}ms" + (plan?.let { "（目標 ${it.det.label} %.0f%% / AF点: ${it.note}）".format(it.det.score * 100) } ?: ""),
        style = MaterialTheme.typography.bodySmall, color = if (plan != null) Cam.Ai else Cam.TextDim
    )
    if (!landscape) {
        LabeledSlider(
            if (cfg.subject == Subject.PERSON) "顔が無いときのAF位置（人物枠の上から）" else "AF点の縦位置（検出枠の上から）",
            "${(cfg.pointYRatio * 100).toInt()}%", cfg.pointYRatio, 0.05f..0.9f, vm::setAiPointY, accent = Cam.Ai
        )
        LabeledSlider("先読み（映像遅延ぶん進行方向へ）", "${cfg.leadMs} ms", cfg.leadMs.toFloat(), 0f..500f, { vm.setAiLead(it.toInt()) }, accent = Cam.Ai)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton(if (landscape) "風景AF（今すぐ）" else "AI AF（今すぐ）", vm::aiFocusNow, Modifier.weight(1f), enabled = plan != null && !cfg.pinMode, color = Cam.Ai, contentColor = Color(0xFF00201C), height = 48.dp)
        GhostButton("計測リセット", vm::clearAfStats)
    }
    Mono(
        if (ai.stats.isEmpty()) "AF所要時間: まだ計測なし"
        else "AF所要時間（AF指示〜完了確認）\n" + ai.stats.entries.joinToString("\n") { (k, v) ->
            "$k: 平均 ${v.avgMs}ms / 直近 ${v.lastMs}ms / 応答平均 ${v.cmdAvgMs}ms / ${v.count}回"
        }
    )
    Text(ai.lastAction, style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
    InfoNote("このアプリが決めるのはAF位置とAFを送るタイミングです。ライブビュー中のAFは多くの機種でコントラスト検出方式で、暗所では電車・バスのヘッドライトのような明暗の境目がある所を狙うほど合いやすくなりますが、駆動速度や合焦の限界はカメラとレンズで決まります。")
}

// ----------------------------------------------------------------------
@Composable
private fun FocusPanel(vm: MainViewModel) {
    val cfg by vm.focusCfg.collectAsStateWithLifecycle()
    val f by vm.focusUi.collectAsStateWithLifecycle()
    val pct = if (f.peak > 0f) (f.score / f.peak).coerceIn(0f, 1f) else 0f
    val verdictColor by animateColorAsState(
        when {
            f.verdict.startsWith("合焦") -> Cam.Ok
            f.verdict.contains("不能") -> Cam.Err
            f.verdict == "—" -> Cam.TextDim
            else -> Cam.Warn
        }, tween(300), label = "verdict"
    )
    CamCard(color = Cam.Surface2, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("シャープネス", style = MaterialTheme.typography.labelSmall, color = Cam.TextFaint)
                Text("%.1f".format(f.score), style = MaterialTheme.typography.headlineSmall, color = Cam.Text)
            }
            StatusPill(f.verdict, verdictColor, pulsing = f.running)
        }
        SmoothProgress(pct, color = verdictColor)
        Text("直近ピークの ${(pct * 100).toInt()}% · 信頼度 ${f.confidence}%${if (f.blurHint.isNotEmpty()) " · ${f.blurHint}" else ""}", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
    }
    Text(
        "焦点距離: ${f.focalMm?.let { "%.0f mm".format(it) } ?: "不明"} · 校正点: ${f.bucket?.let { "${it}mm" } ?: "該当なし"} · 微調整量: ${f.fine}ステップ（${if (f.calibrated) "校正済み" else "未校正"}）",
        style = MaterialTheme.typography.bodySmall, color = Cam.TextDim
    )
    Text("状態: ${f.status} · 累計移動 ${f.net}" + (if (f.lastResult.isNotEmpty()) "\n前回: ${f.lastResult}" else ""), style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)

    SubTitle("手動フォーカス")
    for (far in listOf(false, true)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (far) "Far" else "Near", Modifier.size(width = 40.dp, height = 20.dp), style = MaterialTheme.typography.labelLarge, color = Cam.TextDim)
            listOf("微", "標準", "大").forEachIndexed { i, l -> GhostButton(l, { vm.manualFocus(far, i) }, Modifier.weight(1f), height = 40.dp) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedContent(f.running, Modifier.weight(1f), label = "corrBtn") { r ->
            if (r) PrimaryButton("補正停止", vm::stopFocusCorrection, color = Cam.Err, contentColor = Color.White, height = 46.dp)
            else PrimaryButton("自動合焦補正", vm::startFocusCorrection, height = 46.dp)
        }
        GhostButton("初期位置へ", vm::focusReset, height = 46.dp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("ステップ校正", vm::calibrateStep, Modifier.weight(1f), enabled = !f.running, height = 42.dp)
        GhostButton("焦点距離", vm::refreshFocalLength, Modifier.weight(1f), height = 42.dp)
        GhostButton("MFに切替", { vm.setFocusKind(FocusKind.MF) }, Modifier.weight(1f), height = 42.dp)
    }

    SubTitle("方向の確認")
    Text(
        "Near = 方向フラグ${f.nearFlag}（${if (f.dirConfirmed) "確認済み" else "未確認・仮定値"}）。Nearを押して手前がシャープになれば「方向OK」、奥がシャープになれば「入れ替え」。確認前は前ピン/後ピンを断定しません。",
        style = MaterialTheme.typography.bodySmall, color = if (f.dirConfirmed) Cam.TextDim else Cam.Warn
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("方向OK", vm::confirmFocusDirection, Modifier.weight(1f), color = Cam.Ok, height = 42.dp)
        GhostButton("Near/Far入れ替え", vm::swapFocusDirection, Modifier.weight(1f), height = 42.dp)
    }

    SubTitle("設定")
    SettingSwitch("AIピント解析（リアルタイム表示）", cfg.analysisOn, vm::setFocusAnalysis)
    SettingSwitch("前ピン・後ピンの表記", cfg.verdictOn, vm::setFocusVerdict, "方向確認済みのときのみ")
    SettingSwitch("AF後に自動でピント補正", cfg.autoCorrectOn, vm::setFocusAutoCorrect, "置きピン中は動作しない")
    LabeledSlider("補正感度（改善/悪化とみなす幅）", "${(cfg.sensitivity * 100).toInt()}%", cfg.sensitivity, 0.03f..0.25f, vm::setFocusSensitivity)
    LabeledSlider("補正速度（駆動後の安定待ち）", "${cfg.settleMs} ms", cfg.settleMs.toFloat(), 100f..800f, { vm.setFocusSettle(it.toInt()) })
    LabeledSlider("補正最大回数", "${cfg.maxIter}", cfg.maxIter.toFloat(), 2f..16f, { vm.setFocusMaxIter(it.toInt()) })
    LabeledSlider("移動上限（開始位置から±）", "${cfg.maxTotal}", cfg.maxTotal.toFloat(), 50f..900f, { vm.setFocusMaxTotal(it.toInt()) })
    LabeledSlider("微調整量（現在の焦点距離）", "${f.fine} ステップ", f.fine.toFloat(), 2f..100f, { vm.setFocusFine(it.toInt()) })
    InfoNote(
        "MF駆動（Nikon MfDrive 0x9204 / Canon DriveLens 0x9155 / LUMIX focus）の方向・ステップ量の単位・受理条件は実機で未確認です（応答コードをそのまま表示します）。" +
            "前ピン/後ピンは1枚の画像では判定できないため、前後に少し動かしたシャープネスの変化からピーク方向を求めます。" +
            "被写界深度が深い・低コントラスト・LEDや強い光源・被写体ブレがあると誤判定します。校正点は${FocusProfileStore.BUCKETS.joinToString("/")}mmで、他の焦点距離へは流用しません。",
        "未検証・制約", warn = true
    )
}

// ----------------------------------------------------------------------
@Composable
private fun TrackPanel(vm: MainViewModel) {
    val cfg by vm.trackCfg.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val shown = tracks.filter { it.state != TrackState.LOST }.sortedByDescending { it.isTarget }.take(8)
    AnimatedContent(shown.isEmpty(), label = "trackEmpty") { empty ->
        if (empty) Text("追跡中の物体なし（AI検出をONにして、被写体を映してください）", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
        else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach {
                val c = when (it.state) {
                    TrackState.TRACKING -> Cam.Ok; TrackState.OCCLUDED -> Color(0xFFFFAB40); TrackState.PREDICTING -> Cam.Warn
                    TrackState.REACQUIRING -> Color(0xFF40C4FF); TrackState.LOST -> Cam.TextFaint
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("ID${it.id} ${it.label}", c, pulsing = it.isTarget)
                    Text("${it.state.label}${if (it.isTarget) " ★AF対象" else ""} · %.2f,%.2f/秒".format(it.vx, it.vy), style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
                }
            }
        }
    }
    LabeledSlider("遮蔽への耐性（重なっても予測し続ける）", "%.1f 秒".format(cfg.occlusionTolerance), cfg.occlusionTolerance, 0.5f..8f, vm::setOcclusionTolerance, accent = Cam.Ai)
    LabeledSlider("予測追跡の最大時間（画面外・柱の裏など）", "%.1f 秒".format(cfg.predictMax), cfg.predictMax, 1f..12f, vm::setPredictMax, accent = Cam.Ai)
    LabeledSlider("再捕捉の感度（右ほど再捕捉しやすいが誤認も増える）", "%.2f".format(cfg.reacqThreshold), cfg.reacqThreshold, 0.2f..0.8f, vm::setReacqThreshold, accent = Cam.Ai)
    InfoNote(
        "状態: 追跡中(TRACKING) / 遮蔽中(OCCLUDED: 重なる別物体あり) / 予測追跡中(PREDICTING) / 再認識中(REACQUIRING) / 見失い(LOST)。" +
            "遮蔽・予測・再認識中は、AF対象を別の物体へ切り替えず、予測位置も使ってAFを急に動かしません。柱や木は物体クラスに無いため「予測追跡中」になります。\n" +
            "【未実装】追従強度・AF位置の補正速度・車体前面/側面優先・LED行先表示や反射領域の回避。"
    )
}

// ----------------------------------------------------------------------
@Composable
private fun PredPanel(vm: MainViewModel) {
    val cfg by vm.predCfg.collectAsStateWithLifecycle()
    val p by vm.predUi.collectAsStateWithLifecycle()
    SettingSwitch("予測フォーカス", cfg.enabled, vm::setPredEnabled, "追跡中の対象の距離変化に合わせて先にレンズを動かす", accent = Cam.Ai)
    SettingSwitch("合間にシャープネスで自動微調整", cfg.fineTune, vm::setPredFineTune, "被写体が動いていないとき", accent = Cam.Ai)
    LabeledSlider("先読み時間", "${cfg.leadMs} ms", cfg.leadMs.toFloat(), 0f..1000f, { vm.setPredLead(it.toInt()) }, accent = Cam.Ai)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton("距離校正点を記録", { vm.recordDistancePoint(false) }, Modifier.weight(1f), color = Cam.Ai, contentColor = Color(0xFF00201C), height = 46.dp)
        GhostButton("校正を消去", vm::clearDistanceMap, height = 46.dp)
    }
    Mono(
        "状態: ${p.status}\n校正点: ${p.points}点 / 傾き: ${p.slope?.let { "%.1f ステップ/(1/m)".format(it) } ?: "未確定（2点以上・距離に差が必要）"}" +
            (p.distM?.let { "\n現在の推定距離: %.1f m".format(it) } ?: "")
    )
    InfoNote(
        "使い方: ①MF運用（置きピンのMF切替か、本体のMF）にする ②対象にピントを合わせた状態で「距離校正点を記録」 " +
            "③対象が別の距離に来たらまた合わせて記録（2点以上・距離に差が必要）。点が揃うまで、また距離推定が不確かなときは、レンズを動かしません。" +
            "1回の移動量には上限があります。距離は枠の高さと公称寸法（バス3.1m等）から求めるため絶対値は±20%以上ずれますが、校正と予測を同じ仮定で行うので比は保たれます。" +
            "【未検証】AFでレンズが動くと校正は無効になります。"
    )
}

// ----------------------------------------------------------------------
@Composable
private fun BurstPanel(vm: MainViewModel) {
    val cfg by vm.burstCfg.collectAsStateWithLifecycle()
    val b by vm.burstUi.collectAsStateWithLifecycle()
    SegmentedControl(BurstMode.values().map { it.label }, cfg.mode.ordinal, { vm.setBurstMode(BurstMode.values()[it]) })
    AnimatedContent(cfg.mode, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "burstDesc") { m ->
        Text(
            when (m) {
                BurstMode.NORMAL -> "通常: 撮影指示を1回ずつ送り、応答とカメラの撮影可否を確認してから次を送ります。"
                BurstMode.HIGH -> "高速: カメラのレリーズモードを連続撮影にして、1回の指示で複数枚を要求します（Nikonのみ。それ以外・設定できない場合は通常方式）。"
                BurstMode.AI_FOLLOW -> "AI追従: 各撮影の直前に予測フォーカスを実行してから撮影します。予測フォーカスの校正が必要です。"
            }, style = MaterialTheme.typography.bodySmall, color = Cam.TextDim
        )
    }
    SettingSwitch("指定枚数で自動停止", cfg.limited, vm::setBurstLimited, if (cfg.limited) "${cfg.count} 回で停止" else "停止ボタンまで（最大999回）")
    AnimatedVisibility(cfg.limited, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        LabeledSlider("枚数", "${cfg.count} 回", cfg.count.toFloat(), 1f..200f, { vm.setBurstCount(it.toInt()) })
    }
    AnimatedContent(cfg.mode == BurstMode.HIGH, label = "burstSlider") { high ->
        if (high) LabeledSlider("1回の指示で要求する枚数", "${cfg.nativeN}", cfg.nativeN.toFloat(), 2f..20f, { vm.setBurstNative(it.toInt()) })
        else LabeledSlider("撮影間隔", if (cfg.intervalMs == 0) "最速" else "${cfg.intervalMs} ms", cfg.intervalMs.toFloat(), 0f..2000f, { vm.setBurstInterval(it.toInt()) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton("連写開始", vm::startBurstPanel, Modifier.weight(1f), icon = CamIcons.Burst, height = 48.dp)
        GhostButton("停止", vm::stopBurstPanel, color = Cam.Err, height = 48.dp)
    }
    CamCard(color = Cam.Surface2, padding = 12.dp) {
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            Stat("指示", "${b.shots}"); Stat("通知", "${b.events}"); Stat("件/秒", "%.2f".format(b.fps)); Stat("エラー", "${b.errors}")
        }
        Text("状態: ${b.state} · カメラ: ${b.cameraReady} · busy待ち ${b.busyWaits}回" + (if (b.note.isNotEmpty()) "\n${b.note}" else ""), style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
    }
    InfoNote("撮影画面のシャッター長押しでも連写できます（離すと停止）。通知件数はRAW+JPEG記録だと1枚で2件になります。【未検証】カメラ連続撮影モードの受理・実際の連写速度。")
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(value, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "stat") {
            Text(it, style = MaterialTheme.typography.titleLarge, color = Cam.Text)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Cam.TextFaint)
    }
}

// ----------------------------------------------------------------------
private fun parseF(s: String): Float? = s.trim().replace(',', '.').toFloatOrNull()

@Composable
private fun SpeedPanel(vm: MainViewModel) {
    val on by vm.speedOn.collectAsStateWithLifecycle()
    val prof by vm.speedProfile.collectAsStateWithLifecycle()
    val speeds by vm.speeds.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val calib by vm.calibPick.collectAsStateWithLifecycle()
    val names by vm.profileNames.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshProfileNames() }
    var distText by remember(prof.name) { mutableStateOf(if (prof.realDistM > 0f) prof.realDistM.toString() else "") }
    var heightText by remember(prof.name) { mutableStateOf(if (prof.camHeightM > 0f) prof.camHeightM.toString() else "") }
    var roadText by remember(prof.name) { mutableStateOf(if (prof.roadDistM > 0f) prof.roadDistM.toString() else "") }
    var angleText by remember(prof.name) { mutableStateOf(if (prof.roadAngleDeg != 0f) prof.roadAngleDeg.toString() else "") }
    var menu by remember { mutableStateOf(false) }

    SettingSwitch("速度測定", on, vm::setSpeedOn, "AI検出ON・追跡中の車両ごと", accent = Cam.Warn)
    val rows = tracks.filter { speeds[it.id] != null }.take(6)
    if (on) {
        if (rows.isEmpty()) Text("測定対象なし（車両が映り、追跡が数フレーム続くと表示されます）", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
        rows.forEach { tv ->
            val s = speeds[tv.id]!!
            val k = s.kmh
            CamCard(color = Cam.Surface2, padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ID${tv.id} ${tv.label} · ${s.dir}", style = MaterialTheme.typography.labelMedium, color = Cam.TextDim)
                        Text(
                            when { k == null -> "測定中"; s.conf < 40 -> "参考 ≈%.0f km/h".format(k); else -> "推定 %.0f km/h".format(k) },
                            style = MaterialTheme.typography.titleLarge, color = if (k != null && s.conf >= 40) Cam.Text else Cam.TextDim
                        )
                    }
                    StatusPill("信頼度${s.conf}%", if (s.conf >= 60) Cam.Ok else if (s.conf >= 40) Cam.Warn else Cam.TextDim)
                }
                Text(
                    "[${s.method}] ①通過時間 ${s.gateKmh?.let { "%.0f".format(it) } ?: "—"} / ②基準スケール ${s.scaleKmh?.let { "%.0f".format(it) } ?: "—"} / ③公称寸法 ${s.nominalKmh?.let { "%.0f".format(it) } ?: "—"} km/h" +
                        if (s.note.isNotEmpty()) "\n${s.note}" else "",
                    style = MaterialTheme.typography.bodySmall, color = Cam.TextDim
                )
            }
        }
    }
    SubTitle("校正（撮影地点ごとに保存）")
    AnimatedContent(calib, label = "calib") { c ->
        Text(
            when (c) {
                1 -> "▶ ライブビュー映像の道路上の「基準点1」をタップしてください"
                2 -> "▶ 続いて、道路上の「基準点2」をタップしてください"
                else -> "基準2点: (%.2f, %.2f) → (%.2f, %.2f) ／ 実距離 ${if (prof.realDistM > 0f) "${prof.realDistM} m" else "未入力"}".format(prof.p1x, prof.p1y, prof.p2x, prof.p2y)
            }, style = MaterialTheme.typography.bodySmall, color = if (c > 0) Cam.Warn else Cam.TextDim
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton("基準2点を指定", vm::startCalibPick, Modifier.weight(1f), enabled = calib == 0, color = Cam.Warn, height = 46.dp)
        GhostButton("やめる", vm::cancelCalibPick, enabled = calib != 0, height = 46.dp)
    }
    OutlinedTextField(prof.name, { vm.updateSpeedProfile(prof.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text("撮影地点の名前") }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors())
    OutlinedTextField(distText, { distText = it; vm.updateSpeedProfile(prof.copy(realDistM = parseF(it) ?: 0f)) }, Modifier.fillMaxWidth(), label = { Text("基準2点間の実距離 [m]") }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors())
    OutlinedTextField(heightText, { heightText = it; vm.updateSpeedProfile(prof.copy(camHeightM = parseF(it) ?: 0f)) }, Modifier.fillMaxWidth(), label = { Text("カメラの高さ [m]（記録のみ）") }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors())
    OutlinedTextField(roadText, { roadText = it; vm.updateSpeedProfile(prof.copy(roadDistM = parseF(it) ?: 0f)) }, Modifier.fillMaxWidth(), label = { Text("道路まで [m]（記録のみ）") }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors())
    OutlinedTextField(angleText, { angleText = it; vm.updateSpeedProfile(prof.copy(roadAngleDeg = parseF(it) ?: 0f)) }, Modifier.fillMaxWidth(), label = { Text("道路の向き [度]（記録のみ）") }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton("保存", vm::saveSpeedProfile, Modifier.weight(1f), height = 44.dp)
        Box {
            GhostButton(if (names.isEmpty()) "保存済みなし" else "読込 ▾", { menu = true }, enabled = names.isNotEmpty(), height = 44.dp)
            DropdownMenu(menu, { menu = false }) { names.forEach { n -> DropdownMenuItem({ Text(n) }, { menu = false; vm.loadSpeedProfile(n) }) } }
        }
        GhostButton("削除", { if (prof.name.isNotBlank()) vm.deleteSpeedProfile(prof.name) }, enabled = prof.name in names, color = Cam.Err, height = 44.dp)
    }
    InfoNote(
        "方式: ①基準2地点を車両の接地点（枠の下辺中央）が通る時間で 実距離÷時間 ②基準2点から求めた縮尺×画面上の移動 ③校正不要の公称寸法（バス3.1m等）×画面上の移動。" +
            "3つを並べて表示するので、一致していれば信頼度が上がります。時刻はライブビューフレームの取り込み時刻で、予測位置は測定に使いません。" +
            "カメラの向き・傾斜・揺れの補正や、カメラ高さ/道路距離/道路角度からの幾何計算は未実装です（基準2点が道路の奥行きの変化を含むと②は不正確）。" +
            "校正なしの場合は③のみで、精度は低い推定値です。"
    )
}

// ----------------------------------------------------------------------
@Composable
private fun OrbisPanel(vm: MainViewModel) {
    val cfg by vm.orbisCfg.collectAsStateWithLifecycle()
    val hits by vm.orbisHits.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.JAPAN) }
    InfoNote(
        "法的な速度取締り・違反判定には使えません。速度は校正条件にもとづく推定値で、証拠能力はありません。" +
            "ナンバー・画像は画面に一時表示するだけで、外部へ送信しません。履歴の保存は下のスイッチをONにしたときだけです。" +
            "公道では、運転者や通行人に危険が及ばない安全な場所で使ってください。", "ご利用にあたって", warn = true
    )
    SettingSwitch("オービスモード", cfg.enabled, vm::setOrbisEnabled, "速度測定が自動でONになります", accent = Cam.Rec)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("しきい値 ${cfg.limitKmh} km/h", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Cam.Text)
        Box {
            GhostButton("プリセット ▾", { menu = true }, height = 38.dp)
            DropdownMenu(menu, { menu = false }) {
                listOf(30, 40, 50, 60, 70, 80, 90, 100, 120).forEach { v -> DropdownMenuItem({ Text("$v km/h") }, { menu = false; vm.setOrbisLimit(v) }) }
            }
        }
    }
    LabeledSlider("速度しきい値", "${cfg.limitKmh} km/h", cfg.limitKmh.toFloat(), 5f..200f, { vm.setOrbisLimit(it.toInt()) }, accent = Cam.Rec)
    LabeledSlider("検知する最低の信頼度", "${cfg.minConf}%", cfg.minConf.toFloat(), 0f..95f, { vm.setOrbisMinConf(it.toInt()) }, accent = Cam.Rec)
    SettingSwitch("検知音", cfg.sound, vm::setOrbisSound, accent = Cam.Rec)
    SettingSwitch("検知時に1枚撮影し高解像度でナンバーを読む", cfg.shootForOcr, vm::setOrbisShoot, "カメラのJPEG記録が必要・PTP接続のみ・未検証", accent = Cam.Rec)
    SettingSwitch("検知履歴をテキストで端末内に保存", cfg.saveHistory, vm::setOrbisSave, "既定OFF。画像は保存しません", accent = Cam.Rec)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("履歴を全削除", vm::clearOrbisHistory, color = Cam.Err, height = 40.dp)
        Text("保存済み: ${vm.orbisSavedCount()}件 / 画面上: ${hits.size}件", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
    }
    if (hits.isEmpty()) Text("検知なし", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
    hits.take(5).forEach { h ->
        CamCard(color = Cam.Rec.copy(alpha = 0.08f), border = Cam.Rec.copy(alpha = 0.5f), padding = 12.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (h.thumb != null) Image(h.thumb.asImageBitmap(), "検知車両", Modifier.size(width = 96.dp, height = 72.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f)) {
                    Text("%.0f km/h".format(h.kmh), style = MaterialTheme.typography.headlineSmall, color = Cam.Rec)
                    Text("設定 ${h.limit} / 超過推定 %.0f / 信頼度 ${h.conf}%%".format(h.kmh - h.limit), style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
                    Text("ナンバー: ${h.plate}${if (h.plateConf > 0) "（目安 ${h.plateConf}%）" else ""}", style = MaterialTheme.typography.bodySmall, color = Cam.Text)
                    Text(fmt.format(Date(h.timeMs)) + if (h.plateNote.isNotEmpty()) " · ${h.plateNote}" else "", style = MaterialTheme.typography.labelSmall, color = Cam.TextFaint)
                }
            }
        }
    }
    InfoNote(
        "ナンバー認識: ML Kit日本語OCR（端末内）。専用のナンバー検出モデルは使わず、車両の下側を切り出して文字認識し、ナンバーの形式に合う部分だけを採用します。" +
            "形式に合わない・読み取りが不安定な場合は「認識不能」と表示し、推測で文字を補いません。ライブビュー解像度では小さすぎて読めないことが多いです。"
    )
}

// ----------------------------------------------------------------------
@Composable
private fun FineTunePanel(vm: MainViewModel) {
    val survey by vm.propSurvey.collectAsStateWithLifecycle()
    Text(
        "このアプリからAF微調整値を取得・変更することはできません（PTPの対応プロパティを特定できていないため、取得・変更できるふりはしません）。" +
            "設定は本体で: セットアップメニュー →「AF微調整」→ ON →「保存値」（-20〜+20）。",
        style = MaterialTheme.typography.bodySmall, color = Cam.TextDim
    )
    InfoNote(
        "ファインダーの位相差AFのピント位置は、このアプリのライブビュー（コントラスト方式）やMF駆動の解析では測れないため、値は算出できません。" +
            "目安の手順は、定規などの斜め配置ターゲットをファインダーAFで撮影し、保存値を数段階ずつ変えて撮り比べ、最もピントが合うコマの値を採用する方法です。"
    )
    GhostButton("ベンダー拡張プロパティを調査", vm::surveyProperties, Modifier.fillMaxWidth(), icon = CamIcons.Chip)
    Mono(survey)
}
