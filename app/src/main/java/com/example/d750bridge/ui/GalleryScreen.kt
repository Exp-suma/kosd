package com.example.d750bridge.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.d750bridge.MainViewModel
import com.example.d750bridge.MediaUiItem
import com.example.d750bridge.PreviewState
import com.example.d750bridge.ui.components.CamCard
import com.example.d750bridge.ui.components.CamChip
import com.example.d750bridge.ui.components.CamIcons
import com.example.d750bridge.ui.components.EmptyState
import com.example.d750bridge.ui.components.GhostButton
import com.example.d750bridge.ui.components.PrimaryButton
import com.example.d750bridge.ui.components.SmoothProgress
import com.example.d750bridge.ui.components.pressScale
import com.example.d750bridge.ui.components.shimmer
import com.example.d750bridge.ui.components.tick
import com.example.d750bridge.ui.theme.Cam
import com.example.d750bridge.ui.theme.brandColor

@Composable
fun GalleryScreen(vm: MainViewModel, gridState: LazyGridState, onGoConnect: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val thumbs by vm.thumbs.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val media = ui.camera?.caps?.media
    val bc = brandColor(ui.brand)
    val inf = rememberInfiniteTransition(label = "refresh")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ギャラリー", style = MaterialTheme.typography.headlineMedium, color = Cam.Text)
                    Text(ui.mediaStatus, style = MaterialTheme.typography.bodySmall, color = Cam.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = vm::refreshMedia, enabled = media == true && !ui.mediaLoading) {
                    Icon(Icons.Filled.Refresh, "読み込む", Modifier.rotate(if (ui.mediaLoading) spin else 0f), tint = if (media == true) Cam.Accent else Cam.TextFaint)
                }
            }
            when {
                ui.camera == null -> EmptyState(CamIcons.Gallery, "カメラ未接続", "接続すると、カメラのSDカード内の画像を表示できます") {
                    PrimaryButton("接続する", onGoConnect, Modifier.padding(horizontal = 40.dp), height = 48.dp)
                }
                media == false -> EmptyState(
                    CamIcons.Gallery, "このカメラでは未対応",
                    "SDカード一覧・RAW取り込みはPTP接続（Nikon / Canon）のみ対応です。${ui.brand.label} はカメラ本体またはメーカー純正アプリで転送してください。"
                )
                ui.mediaItems.isEmpty() && !ui.mediaLoading -> EmptyState(CamIcons.Gallery, "まだ読み込んでいません", "SDカードの画像を新しい順に30枚ずつ読み込みます。CR3・CR2・NEF・ARW・RAF・ORF・RW2・DNGなどのRAWを選んで取り込めます。") {
                    PrimaryButton("SDカードを読み込む", vm::refreshMedia, Modifier.padding(horizontal = 40.dp), icon = CamIcons.Download, height = 48.dp)
                }
                else -> {
                    if (ui.storageIds.size > 1) Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.storageIds.forEachIndexed { i, id -> CamChip("カード ${i + 1}", ui.selectedStorageId == id, { vm.selectStorage(id) }, enabled = !ui.mediaLoading) }
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CamChip("RAWを全選択", false, { vm.selectVisibleMedia(true) })
                        CamChip("選択解除", false, { vm.selectVisibleMedia(false) }, enabled = ui.selectedHandles.isNotEmpty())
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp), state = gridState, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(ui.mediaItems, key = { it.handle }) { item ->
                            ThumbTile(item, thumbs[item.handle], item.handle in ui.selectedHandles, vm.isRaw(item), bc,
                                onOpen = { vm.showPreview(item) }, onToggle = { vm.toggleMediaSelection(item.handle, it) })
                        }
                        if (ui.moreMediaAvailable || ui.mediaLoading) item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                if (ui.mediaLoading) CircularProgressIndicator(Modifier.size(28.dp), color = Cam.Accent, strokeWidth = 3.dp)
                                else GhostButton("さらに古い30件を読み込む", vm::loadMoreMedia)
                            }
                        }
                    }
                }
            }
        }
        ImportBar(
            visible = ui.selectedHandles.isNotEmpty() || ui.transferBusy, count = ui.selectedHandles.size, busy = ui.transferBusy,
            percent = ui.transferPercent, progress = ui.transferProgress, onImport = vm::importSelectedRaw, onCancel = vm::cancelTransfer,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
    preview?.let { PreviewDialog(it, vm::closePreview) }
}

@Composable
private fun ThumbTile(item: MediaUiItem, thumb: Bitmap?, selected: Boolean, isRaw: Boolean, accent: Color, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(if (selected) 0.92f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "tileScale")
    val border by animateColorAsState(if (selected) Cam.Accent else Color.Transparent, tween(200), label = "tileBorder")
    Box(
        Modifier.aspectRatio(4f / 3f).graphicsLayer { scaleX = scale; scaleY = scale }.pressScale(src, 0.95f)
            .clip(RoundedCornerShape(14.dp)).border(2.5.dp, border, RoundedCornerShape(14.dp))
            .clickable(interactionSource = src, indication = null, onClick = onOpen)
    ) {
        Crossfade(thumb, animationSpec = tween(350), label = "thumb") { b ->
            if (b != null) Image(remember(b) { b.asImageBitmap() }, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().shimmer())
        }
        Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))).padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(item.name, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isRaw) Text(
            com.example.d750bridge.camera.RawFormats.ext(item.name), Modifier.align(Alignment.TopStart).padding(5.dp).background(accent.copy(alpha = 0.9f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall, color = Color.Black
        )
        if (isRaw) {
            val fill by animateColorAsState(if (selected) Cam.Accent else Color.Black.copy(alpha = 0.35f), tween(180), label = "checkFill")
            Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(26.dp).clip(CircleShape).background(fill)
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .pointerInput(selected) { detectTapGestures { haptic.tick(); onToggle(!selected) } },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.AnimatedVisibility(selected, enter = scaleIn(spring(dampingRatio = 0.4f)), exit = scaleOut()) {
                    Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun ImportBar(visible: Boolean, count: Int, busy: Boolean, percent: Int, progress: String, onImport: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(visible, modifier.padding(12.dp), enter = slideInVertically(spring(dampingRatio = 0.75f)) { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        CamCard(color = Cam.Surface2, border = Cam.Accent.copy(alpha = 0.6f), padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (busy) "取り込み中 $percent%" else "$count 件を選択中", style = MaterialTheme.typography.titleSmall, color = Cam.Text)
                    Text(if (busy) progress else "保存先: Download/D750Bridge（カードの原本は削除しません）", style = MaterialTheme.typography.labelSmall, color = Cam.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (busy) GhostButton("中止", onCancel, color = Cam.Err, height = 42.dp)
                else PrimaryButton("取り込む", onImport, Modifier.size(width = 130.dp, height = 46.dp), icon = CamIcons.Download, height = 46.dp)
            }
            AnimatedVisibility(busy) { SmoothProgress(percent / 100f) }
        }
    }
}

/** 拡大プレビュー。ピンチで拡大・ダブルタップで元に戻す。 */
@Composable
private fun PreviewDialog(p: PreviewState, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var shown by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
        val appear by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "previewIn")
        var zoom by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f * appear))) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { val s = 0.85f + 0.15f * appear; scaleX = s; scaleY = s; alpha = appear }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            zoom = (zoom * zoomChange).coerceIn(1f, 5f)
                            pan = if (zoom == 1f) Offset.Zero else pan + panChange
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero }) },
                contentAlignment = Alignment.Center
            ) {
                val bmp = p.bitmap
                if (bmp != null) Image(
                    remember(bmp) { bmp.asImageBitmap() }, p.name,
                    Modifier.fillMaxWidth().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y },
                    contentScale = ContentScale.Fit
                ) else Text(if (p.loading) "読み込み中…" else "プレビューを取得できませんでした", color = Cam.TextDim)
                if (p.loading && bmp != null) CircularProgressIndicator(Modifier.align(Alignment.BottomCenter).padding(40.dp).size(26.dp), color = Cam.Accent, strokeWidth = 3.dp)
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "閉じる", tint = Color.White) }
            }
        }
    }
}
