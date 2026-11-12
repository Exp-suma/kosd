package com.example.d750bridge.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import com.example.d750bridge.platform.drawLabel
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.d750bridge.AfTarget
import com.example.d750bridge.BurstMode
import com.example.d750bridge.MainViewModel
import com.example.d750bridge.camera.ExpFormat
import com.example.d750bridge.camera.ExpKind
import com.example.d750bridge.camera.ExposureInfo
import com.example.d750bridge.camera.FocusKind
import com.example.d750bridge.track.TrackState
import com.example.d750bridge.ui.components.CamChip
import com.example.d750bridge.ui.components.CamIcons
import com.example.d750bridge.ui.components.PrimaryButton
import com.example.d750bridge.ui.components.Pulse
import com.example.d750bridge.ui.components.RoundIconButton
import com.example.d750bridge.ui.components.SegmentedControl
import com.example.d750bridge.ui.components.heavy
import com.example.d750bridge.ui.components.pressScale
import com.example.d750bridge.ui.components.shimmer
import com.example.d750bridge.ui.components.tick
import com.example.d750bridge.ui.theme.Cam
import com.example.d750bridge.ui.theme.Tnum
import com.example.d750bridge.ui.theme.brandColor
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun ShootScreen(vm: MainViewModel, onGoConnect: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val frameState = vm.liveFrame.collectAsStateWithLifecycle()
    val aiCfg by vm.aiCfg.collectAsStateWithLifecycle()
    val burstCfg by vm.burstCfg.collectAsStateWithLifecycle()
    val speedOn by vm.speedOn.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var showGrid by rememberSaveable { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<ExpKind?>(null) }
    val caps = ui.camera?.caps


    // 最新のできごと（撮影・AF・設定）を1行で表示
    var ticker by remember { mutableStateOf("") }
    LaunchedEffect(ui.captureStatus) { if (ui.captureStatus != "未実行") ticker = ui.captureStatus }
    LaunchedEffect(ui.afStatus) { if (ui.afStatus != "未実行") ticker = ui.afStatus }
    LaunchedEffect(ui.settingStatus) { if (ui.settingStatus != "未実行") ticker = ui.settingStatus }

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        // 横幅が広い画面（PC・タブレット）は「左: ライブビュー / 右: 操作パネル」の2列にする
        val wide = maxWidth >= 840.dp
        val quick: @Composable (Boolean) -> Unit = { wrap ->
            QuickRow(
                vm, ui.exposure.focus, caps?.focusModes ?: emptySet(), aiCfg.detectEnabled, aiCfg.autoAf, aiCfg.pinMode, speedOn, showGrid,
                onGrid = { showGrid = !showGrid }, wrap = wrap
            )
        }
        if (wide) Column(Modifier.fillMaxSize()) {
            TopHud(vm)
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val boxW = maxWidth; val boxH = maxHeight
                    LiveViewport(vm, frameState, showGrid, ui.connected, ui.liveViewRunning, onGoConnect, fitBox = boxW to boxH)
                }
                Column(
                    Modifier.width(420.dp).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Cam.Surface.copy(alpha = 0.5f))
                        .verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatusTicker(ticker)
                    ExposureStrip(ui.exposure, ui.connected && caps?.exposure == true, ui.settingBusy) { haptic.tick(); sheet = it }
                    quick(true)
                    ControlBar(vm, ui.connected, ui.liveViewRunning, caps?.capture == true, ui.captureBusy, burstCfg.mode, caps?.movie == true)
                }
            }
        } else Column(Modifier.fillMaxSize()) {
            TopHud(vm)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LiveViewport(vm, frameState, showGrid, ui.connected, ui.liveViewRunning, onGoConnect)
                StatusTicker(ticker)
                ExposureStrip(ui.exposure, ui.connected && caps?.exposure == true, ui.settingBusy) { haptic.tick(); sheet = it }
                quick(false)
            }
            ControlBar(vm, ui.connected, ui.liveViewRunning, caps?.capture == true, ui.captureBusy, burstCfg.mode, caps?.movie == true)
        }
    }

    sheet?.let { k -> ExposureSheet(k, ui.exposure, onDismiss = { sheet = null }, onPick = { v ->
        when (k) {
            ExpKind.ISO -> vm.setIso(v)
            ExpKind.APERTURE -> vm.setAperture(v)
            ExpKind.SHUTTER -> vm.setShutterRaw(v)
            ExpKind.BIAS -> vm.setBias(v)
        }
    }) }
}

@Composable
private fun TopHud(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cam = ui.camera
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val bc = cam?.let { brandColor(it.brand) } ?: Cam.TextFaint
        Box(Modifier.size(10.dp).background(if (ui.connected) Cam.Ok else Cam.TextFaint, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(cam?.title ?: "未接続", style = MaterialTheme.typography.titleSmall, color = Cam.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cam?.let { "${it.brand.label} · ${it.protocol}" } ?: "接続タブからカメラに接続してください", style = MaterialTheme.typography.labelSmall, color = bc, maxLines = 1)
        }
        ui.exposure.modeLabel?.let { HudChip(it, Cam.Text) }
        ui.exposure.battery?.let { b ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(CamIcons.Battery, null, Modifier.size(18.dp), tint = if (b < 20) Cam.Err else Cam.TextDim)
                Text("$b%", style = MaterialTheme.typography.labelMedium.merge(Tnum), color = Cam.TextDim)
            }
        }
    }
}

@Composable
private fun HudChip(text: String, color: Color) {
    Text(text, Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
}

// ----------------------------------------------------------------------
// ライブビュー
// ----------------------------------------------------------------------
@Composable
private fun LiveViewport(
    vm: MainViewModel, frameState: State<Bitmap?>, showGrid: Boolean, connected: Boolean, running: Boolean, onGoConnect: () -> Unit,
    /** 2列表示のとき、ライブビューを収める枠（幅, 高さ）。縦横比を保ったまま枠いっぱいに表示する。 */
    fitBox: Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp>? = null
) {
    // フレームそのものではなく「縦横比」と「有無」だけを親で使う（毎フレームの再構成を避ける）
    val aspect by remember { derivedStateOf { frameState.value?.let { it.width.toFloat() / it.height } ?: 1.5f } }
    val hasFrame by remember { derivedStateOf { frameState.value != null } }
    val shotTick by vm.shotTick.collectAsStateWithLifecycle()
    val flash = remember { Animatable(0f) }
    LaunchedEffect(shotTick) { if (shotTick > 0) { flash.snapTo(0.6f); flash.animateTo(0f, tween(280)) } }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var ripplePos by remember { mutableStateOf<Offset?>(null) }
    val ripple = remember { Animatable(1f) }
    val dim by animateFloatAsState(if (running) 1f else 0.35f, tween(400), label = "lvDim")

    Box(
        (if (fitBox == null) Modifier.fillMaxWidth().aspectRatio(aspect)
        else Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = fitBox.first / fitBox.second > aspect))
            .clip(RoundedCornerShape(20.dp)).background(Color.Black)
            .border(1.dp, Cam.Outline, RoundedCornerShape(20.dp))
            .pointerInput(hasFrame, running) {
                if (hasFrame && running) detectTapGestures { pos ->
                    haptic.tick()
                    ripplePos = pos
                    scope.launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(520)) }
                    vm.focusAt(pos.x / size.width, pos.y / size.height)
                }
            }
    ) {
        if (hasFrame) {
            LiveFrameLayer(frameState, Modifier.fillMaxSize().graphicsLayer { alpha = dim })
            if (showGrid) GridLayer()
            if (running) {
                TrackingOverlay(vm)
                AfBracketLayer(vm)
            }
            Canvas(Modifier.fillMaxSize()) {
                val p = ripplePos ?: return@Canvas
                val v = ripple.value
                if (v < 1f) {
                    drawCircle(Color.White.copy(alpha = (1f - v) * 0.8f), 14.dp.toPx() + 46.dp.toPx() * v, p, style = Stroke(2.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = (1f - v) * 0.25f), 14.dp.toPx() + 30.dp.toPx() * v, p)
                }
            }
        } else {
            Box(Modifier.fillMaxSize().shimmer(Color(0xFF0F1115), Color(0xFF191C22)))
        }
        // 撮影時のフラッシュ
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.White))
        LvBadges(vm, running)
        CountdownOverlay(vm)
        // 中央の案内（未接続 / 停止中 / 開始中）
        AnimatedContent(
            targetState = when { !connected -> 0; !running -> 1; !hasFrame -> 2; else -> 3 },
            modifier = Modifier.align(Alignment.Center),
            transitionSpec = { (fadeIn(tween(250)) + scaleIn(initialScale = 0.85f)) togetherWith (fadeOut(tween(150)) + scaleOut(targetScale = 1.1f)) },
            label = "lvCenter"
        ) { s ->
            when (s) {
                0 -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(CamIcons.Camera, null, Modifier.size(40.dp), tint = Cam.TextDim)
                    Text("カメラ未接続", style = MaterialTheme.typography.titleSmall, color = Cam.TextDim)
                    Text("接続する", Modifier.clip(CircleShape).background(Cam.Accent).clickable(onClick = onGoConnect).padding(horizontal = 18.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = Color.Black)
                }
                1 -> PlayBubble { haptic.tick(); vm.startLiveView() }
                2 -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(34.dp), color = Cam.Accent, strokeWidth = 3.dp)
                    Text("ライブビューを開始しています…", style = MaterialTheme.typography.labelMedium, color = Cam.TextDim)
                }
                else -> Spacer(Modifier.size(1.dp))
            }
        }
    }
}

@Composable
private fun PlayBubble(onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "play")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600)), label = "playP")
    val src = remember { MutableInteractionSource() }
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(78.dp).graphicsLayer { val s = 1f + p * 0.5f; scaleX = s; scaleY = s; alpha = 1f - p }.background(Cam.Accent.copy(alpha = 0.35f), CircleShape))
        Box(
            Modifier.size(72.dp).pressScale(src, 0.88f).clip(CircleShape).background(Cam.Accent)
                .clickable(interactionSource = src, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(CamIcons.Play, "ライブビュー開始", Modifier.size(32.dp), tint = Color.Black) }
    }
}

/** フレームだけを描く層。フレーム更新時はここだけが再描画される。 */
@Composable
private fun LiveFrameLayer(frameState: State<Bitmap?>, modifier: Modifier) {
    val bmp = frameState.value ?: return
    val img = remember(bmp) { bmp.asImageBitmap() }
    Image(img, "ライブビュー（タップでAF）", modifier, contentScale = ContentScale.FillBounds)
}

@Composable
private fun GridLayer() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.22f)
        val w = 1.dp.toPx()
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), w)
            drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), w)
        }
    }
}

/** セルフタイマーの大きなカウントダウン。数字が切り替わるたびに拡大→縮小する。 */
@Composable
private fun CountdownOverlay(vm: MainViewModel) {
    val n by vm.countdown.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedContent(n, transitionSpec = {
            (scaleIn(initialScale = 1.8f, animationSpec = spring(dampingRatio = 0.5f)) + fadeIn(tween(150))) togetherWith (scaleOut(targetScale = 0.5f) + fadeOut(tween(200)))
        }, label = "countdown") { v ->
            if (v > 0) Box(Modifier.size(120.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
                Text("$v", style = MaterialTheme.typography.displaySmall.merge(Tnum), color = Cam.Warn, fontSize = 64.sp)
            }
        }
    }
}

/** 録画中の経過時間。赤い点が点滅する。 */
@Composable
private fun RecBadge(startMs: Long) {
    val now by produceState(System.currentTimeMillis(), startMs) { while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(250) } }
    val sec = ((now - startMs) / 1000).coerceAtLeast(0)
    Row(
        Modifier.background(Cam.Rec.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Pulse(color = Color.White, size = 7.dp)
        Text("REC %02d:%02d".format(sec / 60, sec % 60), style = MaterialTheme.typography.labelSmall.merge(Tnum), color = Color.White)
    }
}

@Composable
private fun LvBadges(vm: MainViewModel, running: Boolean) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val aiCfg by vm.aiCfg.collectAsStateWithLifecycle()
    val ai by vm.aiUi.collectAsStateWithLifecycle()
    val pin by vm.pin.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().padding(10.dp)) {
        val rec by vm.recStartMs.collectAsStateWithLifecycle()
        AnimatedVisibility(rec != null, Modifier.align(Alignment.BottomStart), enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
            rec?.let { RecBadge(it) }
        }
        AnimatedVisibility(running, Modifier.align(Alignment.TopStart), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            Row(
                Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Pulse(color = Cam.Rec, size = 7.dp)
                Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White)
                if (ui.liveFps > 0f) Text("%.0ffps".format(ui.liveFps), style = MaterialTheme.typography.labelSmall.merge(Tnum), color = Cam.TextDim)
            }
        }
        Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AnimatedVisibility(running && aiCfg.detectEnabled, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                HudChip("AI ${aiCfg.subject.label}${if (ai.detCount > 0) " ·${ai.detCount}" else ""}", Cam.Ai)
            }
            AnimatedVisibility(running && ai.night, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) { HudChip("夜間", Cam.Warn) }
            AnimatedVisibility(pin != null, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) { HudChip("置きピン", Cam.Pin) }
            val tl by vm.tl.collectAsStateWithLifecycle()
            AnimatedVisibility(tl.running, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) { HudChip("TL ${tl.shots + if (tl.shots == 0) tl.frames else 0}", TlColor) }
        }
    }
}

/** AF枠（四隅のブラケット）。新しい位置に置かれると大きい枠から縮みながら合わせ、結果で色が変わる。 */
@Composable
private fun AfBracketLayer(vm: MainViewModel) {
    val target by vm.afTarget.collectAsStateWithLifecycle()
    val pin by vm.pin.collectAsStateWithLifecycle()
    val zoom = remember { Animatable(1f) }
    val t: AfTarget? = target
    LaunchedEffect(t?.nx, t?.ny) {
        if (t != null) { zoom.snapTo(1.8f); zoom.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)) }
    }
    val color by animateColorAsState(when (t?.ok) { true -> Cam.Ok; false -> Cam.Err; null -> Cam.Warn }, tween(250), label = "afColor")
    val inf = rememberInfiniteTransition(label = "afBlink")
    val blink by inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(380), RepeatMode.Reverse), label = "afBlinkA")
    Canvas(Modifier.fillMaxSize()) {
        pin?.let { pn ->
            val side = size.width * 0.1f
            val cx = pn.nx * size.width; val cy = pn.ny * size.height
            brackets(Cam.Pin, cx, cy, side, 3.dp.toPx(), 1f)
            drawLine(Cam.Pin, Offset(cx - side * 0.35f, cy), Offset(cx + side * 0.35f, cy), 1.5.dp.toPx())
            drawLine(Cam.Pin, Offset(cx, cy - side * 0.35f), Offset(cx, cy + side * 0.35f), 1.5.dp.toPx())
        }
        if (t != null) {
            val side = size.width * 0.13f * zoom.value
            brackets(color, t.nx * size.width, t.ny * size.height, side, 2.5.dp.toPx(), if (t.ok == null) blink else 1f)
        }
    }
}

private fun DrawScope.brackets(c: Color, cx: Float, cy: Float, side: Float, w: Float, alpha: Float) {
    val h = side / 2; val l = side * 0.28f
    val col = c.copy(alpha = alpha)
    val pts = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
    for ((sx, sy) in pts) {
        val x = cx + sx * h; val y = cy + sy * h
        drawLine(col, Offset(x, y), Offset(x - sx * l, y), w, StrokeCap.Round)
        drawLine(col, Offset(x, y), Offset(x, y - sy * l), w, StrokeCap.Round)
    }
}

/** 追跡枠（状態で色分け）・速度表示・オービス検知枠・速度校正の基準線・AI目標。 */
@Composable
private fun TrackingOverlay(vm: MainViewModel) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val speeds by vm.speeds.collectAsStateWithLifecycle()
    val speedOn by vm.speedOn.collectAsStateWithLifecycle()
    val prof by vm.speedProfile.collectAsStateWithLifecycle()
    val calib by vm.calibPick.collectAsStateWithLifecycle()
    val hits by vm.orbisHits.collectAsStateWithLifecycle()
    val plan by vm.aiPlan.collectAsStateWithLifecycle()
    Canvas(Modifier.fillMaxSize()) {
        val ts = 11.sp.toPx()
        val now = System.currentTimeMillis()
        val hitIds = hits.filter { h -> now - h.timeMs < 8000L }.map { h -> h.id }.toSet()
        for (tv in tracks) {
            if (tv.state == TrackState.LOST) continue
            val hit = tv.id in hitIds
            val col = if (hit) Cam.Rec else when (tv.state) {
                TrackState.TRACKING -> Cam.Ok
                TrackState.OCCLUDED -> Color(0xFFFFAB40)
                TrackState.PREDICTING -> Cam.Warn
                TrackState.REACQUIRING -> Color(0xFF40C4FF)
                TrackState.LOST -> Color.Gray
            }
            val b = tv.box
            drawRoundRect(
                color = col, topLeft = Offset(b.x0 * size.width, b.y0 * size.height),
                size = Size((b.x1 - b.x0) * size.width, (b.y1 - b.y0) * size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                style = Stroke(width = (if (tv.isTarget || hit) 2.5f else 1.2f).dp.toPx())
            )
            val si = speeds[tv.id]
            val label = buildString {
                append("ID${tv.id} ${tv.state.label}")
                if (speedOn && si != null) {
                    val k = si.kmh
                    if (k == null) append(" 測定中")
                    else if (si.conf < 40) append(" 参考≈%.0fkm/h?".format(k))
                    else append(" 推定%.0fkm/h(%d%%)".format(k, si.conf))
                }
            }
            drawLabel(label, (b.x0 * size.width).coerceAtLeast(2f), (b.y0 * size.height - 4.dp.toPx()).coerceAtLeast(ts), ts)
        }
        if (speedOn || calib > 0) {
            val y = Color(0xFFFFD600)
            val a = Offset(prof.p1x * size.width, prof.p1y * size.height)
            val c = Offset(prof.p2x * size.width, prof.p2y * size.height)
            drawLine(y, a, c, 2.dp.toPx())
            drawCircle(y, 6.dp.toPx(), a, style = Stroke(2.dp.toPx()))
            drawCircle(y, 6.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            drawLabel("基準1", a.x + 8f, a.y - 8f, ts)
            drawLabel("基準2", c.x + 8f, c.y - 8f, ts)
        }
        plan?.let { p ->
            val d = p.det
            drawRoundRect(
                Cam.Ai, Offset(d.x0 * size.width, d.y0 * size.height), Size((d.x1 - d.x0) * size.width, (d.y1 - d.y0) * size.height),
                androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx())
            )
            val px = p.nx * size.width; val py = p.ny * size.height; val r = 10.dp.toPx()
            drawLine(Cam.Ai, Offset(px - r, py), Offset(px + r, py), 2.dp.toPx())
            drawLine(Cam.Ai, Offset(px, py - r), Offset(px, py + r), 2.dp.toPx())
        }
    }
}

@Composable
private fun StatusTicker(text: String) {
    AnimatedContent(
        text, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
        label = "ticker"
    ) { t ->
        val col = when {
            t.startsWith("OK") || t.startsWith("撮影OK") || t.contains("受理") -> Cam.Ok
            t.contains("失敗") || t.contains("不可") || t.contains("不一致") -> Cam.Err
            else -> Cam.TextDim
        }
        Text(
            t.ifBlank { "映像をタップするとその位置にピントを合わせます" }, Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = col, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

// ----------------------------------------------------------------------
// 露出
// ----------------------------------------------------------------------
@Composable
private fun ExposureStrip(e: ExposureInfo, enabled: Boolean, busy: Boolean, onOpen: (ExpKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExpTile("ISO", ExpFormat.isoLabel(e.iso), enabled && e.isoOptions.isNotEmpty(), Modifier.weight(1f)) { onOpen(ExpKind.ISO) }
            ExpTile("絞り", ExpFormat.apertureLabel(e.apertureX100), enabled && e.apertureOptions.isNotEmpty(), Modifier.weight(1f)) { onOpen(ExpKind.APERTURE) }
            ExpTile("SS", e.shutterLabel ?: "—", enabled && e.shutterOptions.isNotEmpty(), Modifier.weight(1f)) { onOpen(ExpKind.SHUTTER) }
            ExpTile("EV", ExpFormat.biasLabel(e.biasMilli), enabled && e.biasOptions.isNotEmpty(), Modifier.weight(1f)) { onOpen(ExpKind.BIAS) }
        }
        AnimatedVisibility(busy, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape), color = Cam.Accent, trackColor = Cam.Surface3)
        }
    }
}

@Composable
private fun ExpTile(label: String, value: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Column(
        modifier.pressScale(src, 0.93f).clip(RoundedCornerShape(16.dp)).background(Cam.Surface)
            .border(1.dp, Cam.Outline.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Cam.TextFaint)
        AnimatedContent(value, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "expVal") { v ->
            Text(v, style = MaterialTheme.typography.titleMedium.merge(Tnum), color = Cam.Text, maxLines = 1)
        }
    }
}

/** 値を左右にスクロールして選ぶホイール。タップした値をすぐ書き込み、中央の値は「設定」ボタンで書き込む。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ExposureSheet(kind: ExpKind, e: ExposureInfo, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val opts: List<Pair<String, Long>> = when (kind) {
        ExpKind.ISO -> e.isoOptions.sorted().map { ExpFormat.isoLabel(it) to it }
        ExpKind.APERTURE -> e.apertureOptions.sorted().map { ExpFormat.apertureLabel(it) to it }
        ExpKind.SHUTTER -> e.shutterOptions.sortedBy { it.seconds }.map { it.label to it.raw }
        ExpKind.BIAS -> e.biasOptions.sorted().map { ExpFormat.biasLabel(it) to it }
    }
    val cur: Long? = when (kind) { ExpKind.ISO -> e.iso; ExpKind.APERTURE -> e.apertureX100; ExpKind.SHUTTER -> e.shutterRaw; ExpKind.BIAS -> e.biasMilli }
    val curLabel = when (kind) {
        ExpKind.ISO -> ExpFormat.isoLabel(e.iso); ExpKind.APERTURE -> ExpFormat.apertureLabel(e.apertureX100)
        ExpKind.SHUTTER -> e.shutterLabel ?: "—"; ExpKind.BIAS -> ExpFormat.biasLabel(e.biasMilli) + " EV"
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Cam.Surface, contentColor = Cam.Text) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(kind.label, style = MaterialTheme.typography.labelLarge, color = Cam.TextDim)
                    AnimatedContent(curLabel, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "sheetCur") {
                        Text(it, style = MaterialTheme.typography.displaySmall.merge(Tnum), color = Cam.Accent)
                    }
                }
                e.modeLabel?.let { Text("モード: $it", style = MaterialTheme.typography.labelMedium, color = Cam.TextDim) }
            }
            if (opts.isEmpty()) {
                Text("カメラから選択肢が届いていません（露出モードによっては変更できません）", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = Cam.Warn)
                return@Column
            }
            val haptic = LocalHapticFeedback.current
            val scope = rememberCoroutineScope()
            val startIndex = opts.indexOfFirst { it.second == cur }.coerceAtLeast(0)
            val list = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
            val fling = rememberSnapFlingBehavior(lazyListState = list)
            val centered by remember {
                derivedStateOf {
                    val info = list.layoutInfo
                    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                    info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) }?.index ?: startIndex
                }
            }
            var first by remember { mutableStateOf(true) }
            LaunchedEffect(centered) { if (first) first = false else haptic.tick() }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val itemW = 84.dp
                val side = (maxWidth - itemW) / 2
                Box(Modifier.align(Alignment.Center).width(itemW).height(64.dp).border(1.5.dp, Cam.Accent.copy(alpha = 0.7f), RoundedCornerShape(18.dp)).background(Cam.AccentSoft, RoundedCornerShape(18.dp)))
                LazyRow(state = list, flingBehavior = fling, contentPadding = PaddingValues(horizontal = side), modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    itemsIndexed(opts, key = { _, o -> o.second }) { i, (label, v) ->
                        Box(
                            Modifier.width(itemW).height(64.dp).graphicsLayer {
                                val info = list.layoutInfo
                                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                                val item = info.visibleItemsInfo.firstOrNull { x -> x.index == i }
                                val d = item?.let { x -> abs(x.offset + x.size / 2f - center) / x.size } ?: 3f
                                val s = (1f - 0.16f * d).coerceIn(0.62f, 1f)
                                scaleX = s; scaleY = s; alpha = (1f - 0.3f * d).coerceIn(0.2f, 1f)
                            }.clip(RoundedCornerShape(16.dp)).clickable {
                                haptic.tick()
                                scope.launch { list.animateScrollToItem(i) }
                                if (v != cur) onPick(v)
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, style = MaterialTheme.typography.titleLarge.merge(Tnum), color = if (v == cur) Cam.Accent else Cam.Text, textAlign = TextAlign.Center, maxLines = 1)
                        }
                    }
                }
            }
            val pick = opts.getOrNull(centered)
            Box(Modifier.padding(horizontal = 20.dp)) {
                PrimaryButton(
                    if (pick == null || pick.second == cur) "現在の値です" else "${pick.first} に設定",
                    { pick?.let { onPick(it.second) } }, enabled = pick != null && pick.second != cur, height = 52.dp
                )
            }
            Text(
                if (e.synthesizedOptions) "※ カメラから選択肢を取得できないため標準の一覧を表示しています（機種によって使えない値があります）"
                else "選択肢はカメラが返した値のみ。書き込み後にカメラから読み直して表示します。",
                Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = Cam.TextFaint
            )
        }
    }
}

// ----------------------------------------------------------------------
// クイック操作・コントロールバー
// ----------------------------------------------------------------------
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuickRow(
    vm: MainViewModel, focus: FocusKind?, modes: Set<FocusKind>, ai: Boolean, follow: Boolean, pin: Boolean, speed: Boolean, grid: Boolean,
    onGrid: () -> Unit, wrap: Boolean = false
) {
    // 2列表示（PC）では折り返して並べる。スマホは横スクロール
    val body: @Composable () -> Unit = {
        FocusKind.values().filter { it in modes }.forEach { k -> CamChip(k.label, focus == k, { vm.setFocusKind(k) }) }
        if (modes.isNotEmpty()) Box(Modifier.width(1.dp).height(24.dp).background(Cam.Outline))
        CamChip("AI検出", ai, { vm.setAiDetect(!ai) }, CamIcons.Sparkle, Cam.Ai)
        CamChip("追従AF", follow, { vm.setAiAuto(!follow) }, CamIcons.Track, Cam.Ai, enabled = ai && !pin)
        CamChip("置きピン", pin, { vm.setPinMode(!pin) }, CamIcons.Pin, Cam.Pin)
        CamChip("速度", speed, { vm.setSpeedOn(!speed) }, CamIcons.Speed, Cam.Warn)
        CamChip("グリッド", grid, onGrid, CamIcons.Grid, Cam.TextDim)
    }
    if (wrap) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() }
    else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { body() }
}

@Composable
private fun ControlBar(vm: MainViewModel, connected: Boolean, running: Boolean, canCapture: Boolean, captureBusy: Boolean, mode: BurstMode, canMovie: Boolean) {
    val burst by vm.burstUi.collectAsStateWithLifecycle()
    val plan by vm.aiPlan.collectAsStateWithLifecycle()
    val aiCfg by vm.aiCfg.collectAsStateWithLifecycle()
    val selfTimer by vm.selfTimer.collectAsStateWithLifecycle()
    val recStart by vm.recStartMs.collectAsStateWithLifecycle()
    val recBusy by vm.recBusy.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var movieMode by rememberSaveable { mutableStateOf(false) }
    val movie = movieMode && canMovie
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Cam.Surface.copy(alpha = 0.6f))))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AnimatedVisibility(canMovie, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SegmentedControl(listOf("写真", "動画"), if (movie) 1 else 0, {
                haptic.tick()
                if (recStart == null) movieMode = it == 1
            }, Modifier.padding(horizontal = 60.dp), accent = if (movie) Cam.Rec else Cam.Accent, height = 34.dp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(running, transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "lvBtn") { r ->
                RoundIconButton(
                    if (r) CamIcons.Stop else CamIcons.Play,
                    { haptic.tick(); if (r) vm.stopLiveView() else vm.startLiveView() },
                    tint = if (r) Cam.Rec else Cam.Text, bg = if (r) Cam.Rec.copy(alpha = 0.18f) else Cam.Surface2,
                    enabled = connected && recStart == null, label = if (r) "LV停止" else "ライブビュー"
                )
            }
            AnimatedContent(movie, transitionSpec = { (scaleIn(initialScale = 0.7f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.7f) + fadeOut()) }, label = "shutterKind") { m ->
                if (m) RecordButton(connected, recStart != null, recBusy) { haptic.heavy(); vm.toggleMovie() }
                else {
                    val bursting = captureBusy && (burst.state == "連写中" || burst.state == "開始")
                    ShutterButton(connected && canCapture, captureBusy, bursting, burst.shots, vm::shutterPress, vm::shutterRelease)
                }
            }
            RoundIconButton(
                CamIcons.Focus,
                { haptic.tick(); if (plan != null && !aiCfg.pinMode) vm.aiFocusNow() else vm.autofocus() },
                tint = if (plan != null) Cam.Ai else Cam.Text, enabled = connected, label = if (plan != null) "AI AF" else "AF"
            )
        }
        AnimatedContent(movie, label = "ctlRow") { m ->
            if (m) Text(
                if (recStart != null) "録画中は ● をもう一度押すと停止します" else "● で録画開始。カメラ側を動画モード（Nikonはライブビューセレクターを動画）にしてください",
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, color = Cam.TextFaint, textAlign = TextAlign.Center
            ) else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // セルフタイマー: タップで OFF → 2秒 → 5秒 → 10秒
                val next = when (selfTimer) { 0 -> 2; 2 -> 5; 5 -> 10; else -> 0 }
                CamChip(if (selfTimer == 0) "タイマー" else "${selfTimer}秒", selfTimer > 0, { haptic.tick(); vm.setSelfTimer(next) }, CamIcons.Timer, Cam.Warn)
                Text("長押し連写", style = MaterialTheme.typography.labelSmall, color = Cam.TextFaint)
                SegmentedControl(BurstMode.values().map { it.label }, mode.ordinal, { vm.setBurstMode(BurstMode.values()[it]) }, Modifier.weight(1f), height = 34.dp)
            }
        }
    }
}

/** 録画ボタン。待機中は赤い丸、録画中は角丸の四角に変形して外周が脈動する。 */
@Composable
private fun RecordButton(enabled: Boolean, recording: Boolean, busy: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val inner by androidx.compose.animation.core.animateDpAsState(if (recording) 34.dp else 66.dp, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), label = "recSize")
    val corner by androidx.compose.animation.core.animateDpAsState(if (recording) 8.dp else 33.dp, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "recCorner")
    val t = rememberInfiniteTransition(label = "recPulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200)), label = "recP")
    Box(
        Modifier.size(90.dp).pressScale(src, 0.88f).clickable(interactionSource = src, indication = null, enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 - 3.dp.toPx()
            drawCircle(Color.White.copy(alpha = if (enabled) 0.85f else 0.25f), r, style = Stroke(3.dp.toPx()))
            if (recording) drawCircle(Cam.Rec.copy(alpha = (1f - p) * 0.6f), r + 6.dp.toPx() * p, style = Stroke(3.dp.toPx()))
        }
        Box(Modifier.size(inner).background(if (enabled) Cam.Rec else Cam.Surface3, RoundedCornerShape(corner)))
        if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.5.dp)
    }
}

/** シャッター。タップ=1枚、長押し=離すまで連写。押すと縮んで赤くなり、連写中は外周が回る。 */
@Composable
private fun ShutterButton(enabled: Boolean, busy: Boolean, bursting: Boolean, shots: Int, onPress: () -> Unit, onRelease: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.82f else 1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium), label = "shScale")
    val inner by animateColorAsState(
        when { !enabled -> Cam.Surface3; pressed || bursting -> Cam.Rec; busy -> Cam.TextDim; else -> Color.White },
        tween(160), label = "shColor"
    )
    val t = rememberInfiniteTransition(label = "shRing")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(850, easing = LinearEasing)), label = "shRot")
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(90.dp).pointerInput(enabled) {
                if (enabled) detectTapGestures(onPress = {
                    pressed = true
                    haptic.heavy()
                    onPress()
                    tryAwaitRelease()
                    pressed = false
                    onRelease()
                })
            },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2 - 3.dp.toPx()
                drawCircle(Color.White.copy(alpha = if (enabled) 0.85f else 0.25f), r, style = Stroke(3.dp.toPx()))
                if (bursting) drawArc(
                    Cam.Rec, rot, 100f, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2),
                    style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            Box(Modifier.size(70.dp).graphicsLayer { scaleX = scale; scaleY = scale }.background(inner, CircleShape))
        }
        AnimatedVisibility(
            bursting && shots > 0, Modifier.align(Alignment.TopCenter).graphicsLayer { translationY = -26.dp.toPx() },
            enter = scaleIn(spring(dampingRatio = 0.45f)) + fadeIn(), exit = scaleOut() + fadeOut()
        ) {
            Row(Modifier.background(Cam.Rec, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                com.example.d750bridge.ui.components.AnimatedCounter(shots, Color.White, 13)
                Text(" 枚", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
