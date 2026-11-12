package com.example.d750bridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.d750bridge.MainViewModel
import com.example.d750bridge.platform.KeepScreenOn
import com.example.d750bridge.platform.PlatformBackHandler
import com.example.d750bridge.platform.rememberPermissionRunner
import com.example.d750bridge.ui.components.CamIcons
import com.example.d750bridge.ui.components.Pulse
import com.example.d750bridge.ui.components.tick
import com.example.d750bridge.ui.theme.Cam

enum class Tab(val label: String, val icon: ImageVector) {
    CONNECT("接続", CamIcons.Wifi),
    SHOOT("撮影", CamIcons.Camera),
    TOOLS("AIツール", CamIcons.Sparkle),
    GALLERY("ギャラリー", CamIcons.Gallery),
    SETTINGS("診断", CamIcons.Tune)
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val tl by vm.tl.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.CONNECT) }

    // ライブビュー中・インターバル撮影中・動画作成中は画面を消灯させない
    KeepScreenOn(ui.liveViewRunning || tl.running || tl.encoding)
    val snack = remember { SnackbarHostState() }

    // 接続に必要な権限（SSID表示のため）。拒否されても接続自体は試す。
    val withPerms = rememberPermissionRunner()

    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(msg)
        }
    }
    // 接続が完了したら撮影タブへ自動で移動
    var wasConnected by remember { mutableStateOf(ui.connected) }
    LaunchedEffect(ui.connected) {
        if (ui.connected && !wasConnected && tab == Tab.CONNECT) tab = Tab.SHOOT
        wasConnected = ui.connected
    }
    PlatformBackHandler(enabled = tab != Tab.CONNECT && tab != Tab.SHOOT) { tab = Tab.SHOOT }

    // 各タブのスクロール位置はタブを切り替えても保持する
    val toolsState = rememberLazyListState()
    val settingsState = rememberLazyListState()
    val galleryState = rememberLazyGridState()

    Scaffold(
        containerColor = Cam.Bg,
        snackbarHost = {
            SnackbarHost(snack) { data ->
                Snackbar(
                    data, shape = RoundedCornerShape(16.dp), containerColor = Cam.Surface3, contentColor = Cam.Text,
                    actionColor = Cam.Accent
                )
            }
        },
        bottomBar = {
            CamNavBar(
                selected = tab, onSelect = { tab = it },
                liveRunning = ui.liveViewRunning, connected = ui.connected, selectedCount = ui.selectedHandles.size
            )
        }
    ) { pad ->
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.fillMaxSize().padding(pad),
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { it * dir / 5 } + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(tween(200)) { -it * dir / 5 } + fadeOut(tween(160)))
            },
            label = "tabs"
        ) { t ->
            // 横幅が広い画面（PC・タブレット）では、文章中心の画面を読みやすい幅で中央に置く
            val readable: @Composable (@Composable () -> Unit) -> Unit = { content ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 880.dp).fillMaxSize()) { content() }
                }
            }
            when (t) {
                Tab.CONNECT -> readable { ConnectScreen(vm, withPerms, onGoShoot = { tab = Tab.SHOOT }) }
                Tab.SHOOT -> ShootScreen(vm, onGoConnect = { tab = Tab.CONNECT })
                Tab.TOOLS -> readable { ToolsScreen(vm, toolsState) }
                Tab.GALLERY -> GalleryScreen(vm, galleryState, onGoConnect = { tab = Tab.CONNECT })
                Tab.SETTINGS -> readable { SettingsScreen(vm, settingsState) }
            }
        }
    }
}

/** 下部ナビゲーション。選択中の背景がばねで滑り、アイコンが少し大きくなる。 */
@Composable
private fun CamNavBar(selected: Tab, onSelect: (Tab) -> Unit, liveRunning: Boolean, connected: Boolean, selectedCount: Int) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.fillMaxWidth().background(Cam.Bg).navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(24.dp)).background(Cam.Surface)
                .border(1.dp, Cam.Outline.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
        ) {
            val tabs = Tab.values()
            val w = maxWidth / tabs.size
            val x by animateDpAsState(w * selected.ordinal, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "navX")
            Box(
                Modifier.offset(x = x + 6.dp).width(w - 12.dp).fillMaxHeight().padding(vertical = 7.dp)
                    .background(Cam.AccentSoft, RoundedCornerShape(18.dp))
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEach { t ->
                    val sel = t == selected
                    val tint by animateColorAsState(if (sel) Cam.Accent else Cam.TextDim, tween(220), label = "navTint")
                    val scale by animateFloatAsState(if (sel) 1.12f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "navScale")
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                if (!sel) haptic.tick()
                                onSelect(t)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        Box {
                            Icon(t.icon, t.label, Modifier.size(22.dp).graphicsLayer { scaleX = scale; scaleY = scale }, tint = tint)
                            val badge: @Composable (() -> Unit)? = when {
                                t == Tab.SHOOT && liveRunning -> { { Pulse(color = Cam.Rec, size = 8.dp) } }
                                t == Tab.CONNECT && connected -> { { Box(Modifier.size(8.dp).background(Cam.Ok, CircleShape)) } }
                                t == Tab.GALLERY && selectedCount > 0 -> { {
                                    Text(
                                        "$selectedCount", Modifier.background(Cam.Accent, CircleShape).padding(horizontal = 4.dp),
                                        color = Color.Black, style = MaterialTheme.typography.labelSmall
                                    )
                                } }
                                else -> null
                            }
                            androidx.compose.animation.AnimatedVisibility(badge != null, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp), enter = scaleIn(spring(dampingRatio = 0.4f)), exit = scaleOut()) {
                                badge?.invoke()
                            }
                        }
                        Text(t.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}
