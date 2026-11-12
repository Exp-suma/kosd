package com.example.d750bridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.d750bridge.ConnStage
import com.example.d750bridge.MainViewModel
import com.example.d750bridge.camera.CameraBrand
import com.example.d750bridge.camera.CameraIdentity
import com.example.d750bridge.net.WifiSnapshot
import com.example.d750bridge.ui.components.CamCard
import com.example.d750bridge.ui.components.CamIcons
import com.example.d750bridge.ui.components.ExpandableCard
import com.example.d750bridge.ui.components.GhostButton
import com.example.d750bridge.ui.components.KeyValue
import com.example.d750bridge.ui.components.PrimaryButton
import com.example.d750bridge.ui.components.SectionLabel
import com.example.d750bridge.ui.components.SettingSwitch
import com.example.d750bridge.ui.components.StaggeredAppear
import com.example.d750bridge.ui.components.StatusPill
import com.example.d750bridge.ui.components.pressScale
import com.example.d750bridge.ui.components.tick
import com.example.d750bridge.ui.theme.Cam
import com.example.d750bridge.ui.theme.brandColor
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun stageColor(stage: ConnStage): Color = when (stage) {
    ConnStage.READY -> Cam.Ok
    ConnStage.FAILED -> Cam.Err
    ConnStage.IDLE -> Cam.TextDim
    else -> Cam.Accent
}

@Composable
fun ConnectScreen(vm: MainViewModel, withPerms: (() -> Unit) -> Unit, onGoShoot: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val wifi by vm.wifi.state.collectAsStateWithLifecycle()
    val busy = ui.stage == ConnStage.WIFI || ui.stage == ConnStage.LOCATING || ui.stage == ConnStage.SESSION || ui.probeBusy
    val locked = busy || ui.connected

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CamBridge", style = MaterialTheme.typography.headlineMedium, color = Cam.Text)
                Text("ワイヤレス・カメラリモート", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
            }
            StatusPill(ui.stage.label, stageColor(ui.stage), pulsing = busy)
        }

        HeroCard(ui.brand, ui.stage, ui.probeStatus, ui.camera, busy,
            onConnect = { withPerms { vm.connectAll() } },
            onExisting = { withPerms { vm.useExistingWifi() } },
            onCancel = vm::disconnectAll,
            onDisconnect = vm::disconnectAll,
            onGoShoot = onGoShoot,
            onRetryCamera = vm::connectCamera,
            wifiConnected = wifi.phase == com.example.d750bridge.net.WifiPhase.CONNECTED
        )

        SectionLabel("カメラのメーカー")
        BrandGrid(ui.brand, enabled = !locked) { vm.setBrand(it) }

        AnimatedContent(
            ui.brand,
            transitionSpec = { (fadeIn(tween(260)) + slideInHorizontally { it / 8 }) togetherWith (fadeOut(tween(160)) + slideOutHorizontally { -it / 8 }) },
            label = "guide"
        ) { b -> SetupGuide(b) }

        ExpandableCard(CamIcons.Tune, "接続の詳細設定", "SSID・パスワード・カメラのIPアドレス", initiallyExpanded = ui.brand == CameraBrand.LUMIX && ui.ssidPrefix.isBlank()) {
            AdvancedSettings(vm, ui.brand, ui.ssidPrefix, ui.passphrase, ui.autoIp, ui.cameraIp, ui.resolvedHost, wifi, locked)
        }

        AnimatedVisibility(ui.camera != null, enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            ui.camera?.let { CameraInfoCard(it) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 接続状態のヒーロー表示。中央のオーブが状態に合わせて回転・脈動する。 */
@Composable
private fun HeroCard(
    brand: CameraBrand, stage: ConnStage, status: String, camera: CameraIdentity?, busy: Boolean,
    onConnect: () -> Unit, onExisting: () -> Unit, onCancel: () -> Unit, onDisconnect: () -> Unit, onGoShoot: () -> Unit,
    onRetryCamera: () -> Unit, wifiConnected: Boolean
) {
    val bc = brandColor(brand)
    val glow by animateColorAsState(if (stage == ConnStage.READY) Cam.Ok else if (stage == ConnStage.FAILED) Cam.Err else bc, tween(600), label = "glow")
    CamCard(padding = 20.dp, color = Cam.Surface) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ConnectionOrb(stage, glow, busy)
        }
        AnimatedContent(
            if (stage == ConnStage.READY && camera != null) camera.title else stage.label,
            transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { -it / 2 }) },
            modifier = Modifier.fillMaxWidth(), label = "heroTitle"
        ) { t -> Text(t, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge, color = Cam.Text, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        val shown = if (stage == ConnStage.IDLE && status == "未接続") "カメラのWi-Fiを有効にして、下のボタンで接続します" else status
        AnimatedContent(shown, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, modifier = Modifier.fillMaxWidth(), label = "heroStatus") { s ->
            Text(s, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = if (stage == ConnStage.FAILED) Cam.Err else Cam.TextDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        StepIndicator(stage)
        Spacer(Modifier.height(4.dp))
        AnimatedContent(
            when { stage == ConnStage.READY -> 2; busy -> 1; else -> 0 },
            transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.92f)) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.92f)) },
            label = "heroActions"
        ) { mode ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (mode) {
                    2 -> {
                        PrimaryButton("撮影をはじめる", onGoShoot, icon = CamIcons.Camera, color = Cam.Ok, contentColor = Color(0xFF00210F))
                        GhostButton("切断", onDisconnect, Modifier.fillMaxWidth(), color = Cam.Err, icon = Icons.Filled.Close)
                    }
                    1 -> {
                        PrimaryButton("接続中…", {}, loading = true)
                        GhostButton("キャンセル", onCancel, Modifier.fillMaxWidth())
                    }
                    else -> {
                        if (wifiConnected) {
                            PrimaryButton("カメラに接続", onRetryCamera, icon = CamIcons.Link)
                            GhostButton("Wi-Fiを切断", onDisconnect, Modifier.fillMaxWidth())
                        } else {
                            PrimaryButton("${brand.label} に接続する", onConnect, icon = CamIcons.Wifi)
                            GhostButton("接続済みのWi-Fiを使う", onExisting, Modifier.fillMaxWidth(), icon = CamIcons.Link)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionOrb(stage: ConnStage, color: Color, busy: Boolean) {
    val t = rememberInfiniteTransition(label = "orb")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "orbRot")
    val breathe by t.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "orbBreathe")
    val sweep by animateFloatAsState(if (busy) 100f else 360f, spring(stiffness = Spring.StiffnessLow), label = "orbSweep")
    val ready = stage == ConnStage.READY
    val iconScale by animateFloatAsState(if (ready) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "orbCheck")
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { val s = if (busy) 1f else breathe; scaleX = s; scaleY = s }) {
            val c = center
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent), c, r), r, c)
            drawCircle(color.copy(alpha = 0.15f), r * 0.62f, c, style = Stroke(2.dp.toPx()))
            // 周回する光点
            if (busy) for (i in 0 until 3) {
                val a = Math.toRadians((rot * (1 + i * 0.35f) + i * 120).toDouble())
                drawCircle(color.copy(alpha = 0.85f - i * 0.2f), 3.5.dp.toPx(), Offset(c.x + cos(a).toFloat() * r * 0.8f, c.y + sin(a).toFloat() * r * 0.8f))
            }
            drawArc(
                color, startAngle = if (busy) rot else -90f, sweepAngle = sweep, useCenter = false,
                topLeft = Offset(c.x - r * 0.62f, c.y - r * 0.62f), size = androidx.compose.ui.geometry.Size(r * 1.24f, r * 1.24f),
                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Box(Modifier.size(64.dp).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(CamIcons.Camera, null, Modifier.size(30.dp).graphicsLayer { alpha = 1f - iconScale; scaleX = 1f - iconScale * 0.5f; scaleY = 1f - iconScale * 0.5f }, tint = color)
            Icon(Icons.Filled.Check, null, Modifier.size(34.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale; alpha = iconScale }, tint = color)
        }
    }
}

/** Wi-Fi → 検出 → セッション の3段階。現在の段階が脈動し、間の線が伸びる。 */
@Composable
private fun StepIndicator(stage: ConnStage) {
    val idx = when (stage) {
        ConnStage.IDLE, ConnStage.FAILED -> -1
        ConnStage.WIFI -> 0
        ConnStage.LOCATING -> 1
        ConnStage.SESSION -> 2
        ConnStage.READY -> 3
    }
    val labels = listOf("Wi-Fi", "カメラ検出", "セッション")
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, l ->
            val done = idx > i
            val active = idx == i
            val col by animateColorAsState(when { done -> Cam.Ok; active -> Cam.Accent; stage == ConnStage.FAILED -> Cam.Err.copy(alpha = 0.5f); else -> Cam.Surface3 }, tween(350), label = "stepCol")
            val t = rememberInfiniteTransition(label = "step$i")
            val p by t.animateFloat(1f, 1.25f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "stepP")
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                Box(
                    Modifier.size(26.dp).graphicsLayer { val s = if (active) p else 1f; scaleX = s; scaleY = s }.background(col, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(done, transitionSpec = { scaleIn(spring(dampingRatio = 0.4f)) togetherWith scaleOut() }, label = "stepIcon") { d ->
                        if (d) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Color.Black)
                        else Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = if (active) Color.Black else Cam.TextDim)
                    }
                }
                Text(l, style = MaterialTheme.typography.labelSmall, color = if (active || done) Cam.Text else Cam.TextFaint)
            }
            if (i < labels.lastIndex) {
                val fill by animateFloatAsState(if (idx > i) 1f else if (idx == i) 0.4f else 0f, tween(500, easing = FastOutSlowInEasing), label = "line")
                Box(Modifier.weight(1f).padding(bottom = 16.dp).height(3.dp).clip(CircleShape).background(Cam.Surface3)) {
                    Box(Modifier.fillMaxWidth(fill).height(3.dp).background(Brush.horizontalGradient(listOf(Cam.Ok, Cam.Accent)), CircleShape))
                }
            }
        }
    }
}

@Composable
private fun BrandGrid(selected: CameraBrand, enabled: Boolean, onSelect: (CameraBrand) -> Unit) {
    val rows = CameraBrand.values().toList().chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { b -> BrandCard(b, b == selected, enabled, Modifier.weight(1f)) { onSelect(b) } }
            }
        }
    }
}

@Composable
private fun BrandCard(brand: CameraBrand, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bc = brandColor(brand)
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) bc.copy(alpha = 0.13f) else Cam.Surface, tween(300), label = "brandBg")
    val border by animateColorAsState(if (selected) bc else Cam.Outline, tween(300), label = "brandBorder")
    val bar by animateDpAsState(if (selected) 40.dp else 14.dp, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "brandBar")
    val lift by animateFloatAsState(if (selected) 1f else 0.97f, spring(dampingRatio = 0.55f), label = "brandLift")
    Box(
        modifier.height(96.dp).graphicsLayer { scaleX = lift; scaleY = lift }.pressScale(src, 0.94f)
            .clip(RoundedCornerShape(20.dp)).background(bg).border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(20.dp))
            .clickable(interactionSource = src, indication = null, enabled = enabled) { haptic.tick(); onClick() }
            .alpha(if (enabled || selected) 1f else 0.45f)
            .padding(14.dp)
    ) {
        Column(Modifier.align(Alignment.BottomStart)) {
            Box(Modifier.width(bar).height(4.dp).background(bc, CircleShape))
            Spacer(Modifier.height(8.dp))
            Text(brand.label, style = MaterialTheme.typography.titleLarge, color = Cam.Text)
            Text(brand.protocol, style = MaterialTheme.typography.labelSmall, color = Cam.TextDim)
        }
        AnimatedVisibility(selected, Modifier.align(Alignment.TopEnd), enter = scaleIn(spring(dampingRatio = 0.4f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
            Box(Modifier.size(24.dp).background(bc, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, Modifier.size(15.dp), tint = Color.Black)
            }
        }
    }
}

@Composable
private fun SetupGuide(brand: CameraBrand) {
    val bc = brandColor(brand)
    CamCard(color = Cam.Surface) {
        Text("${brand.label} の準備", style = MaterialTheme.typography.titleSmall, color = Cam.Text)
        brand.setupSteps.forEachIndexed { i, s ->
            StaggeredAppear(i) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(24.dp).background(bc.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = bc)
                    }
                    Text(s, Modifier.weight(1f).padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = Cam.Text)
                }
            }
        }
        Text(brand.note, style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cam.Accent, unfocusedBorderColor = Cam.Outline, focusedLabelColor = Cam.Accent,
    cursorColor = Cam.Accent, focusedContainerColor = Cam.Bg, unfocusedContainerColor = Cam.Bg
)

@Composable
private fun AdvancedSettings(
    vm: MainViewModel, brand: CameraBrand, ssid: String, pass: String, autoIp: Boolean, ip: String, resolved: String?,
    wifi: WifiSnapshot, locked: Boolean
) {
    var showPass by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = ssid, onValueChange = vm::setSsidPrefix, enabled = !locked, singleLine = true,
        label = { Text("カメラSSIDの先頭（${brand.ssidHint}）") }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp), colors = fieldColors()
    )
    OutlinedTextField(
        value = pass, onValueChange = vm::setPassphrase, enabled = !locked, singleLine = true,
        label = { Text("Wi-Fiパスワード（カメラに表示されている場合）") }, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton(onClick = { showPass = !showPass }) { Icon(if (showPass) CamIcons.EyeOff else CamIcons.Eye, "表示切替", tint = Cam.TextDim) } },
        shape = RoundedCornerShape(14.dp), colors = fieldColors()
    )
    SettingSwitch("カメラのIPアドレスを自動検出", autoIp, vm::setAutoIp, "DHCPサーバー・ゲートウェイ・${brand.label}の既定値（${brand.defaultHosts.joinToString()}）を順に試します", enabled = !locked)
    AnimatedVisibility(!autoIp) {
        OutlinedTextField(
            value = ip, onValueChange = vm::setCameraIp, enabled = !locked, singleLine = true,
            label = { Text("カメラIP（手動）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors()
        )
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Cam.Bg.copy(alpha = 0.6f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        KeyValue("Wi-Fi", wifi.phase.label + if (wifi.existing) "（接続済みを使用）" else "")
        KeyValue("SSID", wifi.ssid ?: "取得不可（位置情報の権限が必要）")
        KeyValue("${com.example.d750bridge.platform.PlatformInfo.deviceWord}のIP", wifi.phoneIp ?: "—")
        KeyValue("DHCPサーバー", wifi.dhcpServer ?: "—")
        KeyValue("ゲートウェイ", wifi.gateway ?: "—")
        KeyValue("カメラ", resolved ?: if (autoIp) "未検出" else ip, if (resolved != null) Cam.Ok else Cam.Text)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraInfoCard(cam: CameraIdentity) {
    val bc = brandColor(cam.brand)
    CamCard(border = bc.copy(alpha = 0.5f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(bc.copy(alpha = 0.16f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(CamIcons.Camera, null, tint = bc)
            }
            Column(Modifier.weight(1f)) {
                Text(cam.title, style = MaterialTheme.typography.titleMedium, color = Cam.Text)
                Text("${cam.protocol} · ${cam.host}", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
            }
        }
        if (cam.firmware.isNotBlank()) KeyValue("ファームウェア", cam.firmware)
        Text("使える機能", style = MaterialTheme.typography.labelMedium, color = Cam.TextDim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cam.caps.badges().forEachIndexed { i, (name, ok) ->
                StaggeredAppear(i) {
                    Row(
                        Modifier.background(if (ok) Cam.Ok.copy(alpha = 0.12f) else Cam.Surface2, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(if (ok) Icons.Filled.Check else Icons.Filled.Close, null, Modifier.size(13.dp), tint = if (ok) Cam.Ok else Cam.TextFaint)
                        Text(name, style = MaterialTheme.typography.labelSmall, color = if (ok) Cam.Ok else Cam.TextFaint)
                    }
                }
            }
        }
    }
}
