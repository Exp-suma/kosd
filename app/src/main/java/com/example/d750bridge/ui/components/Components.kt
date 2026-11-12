package com.example.d750bridge.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import com.example.d750bridge.ui.theme.Cam
import com.example.d750bridge.ui.theme.Tnum

fun HapticFeedback.tick() = performHapticFeedback(HapticFeedbackType.TextHandleMove)
fun HapticFeedback.heavy() = performHapticFeedback(HapticFeedbackType.LongPress)

/** 押している間だけ少し縮む（ばね）。 */
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.95f): Modifier = composed {
    val isPressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    this.graphicsLayer { scaleX = s; scaleY = s }
}

/** 読み込み中のきらめき。 */
fun Modifier.shimmer(base: Color = Cam.Surface2, highlight: Color = Cam.Surface3): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "shimmerX")
    this.background(
        Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(x * 600f - 300f, 0f),
            end = Offset(x * 600f + 300f, 300f)
        )
    )
}

@Composable
fun CamCard(
    modifier: Modifier = Modifier,
    color: Color = Cam.Surface,
    border: Color = Cam.Outline,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = BorderStroke(1.dp, border.copy(alpha = 0.7f))) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.labelSmall, color = Cam.TextFaint)
}

/** 点滅する状態ドット。 */
@Composable
fun StatusDot(color: Color, pulsing: Boolean, size: Dp = 10.dp) {
    val t = rememberInfiniteTransition(label = "dot")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)), label = "pulse")
    val c by animateColorAsState(color, tween(400), label = "dotColor")
    Box(Modifier.size(size * 2.2f), contentAlignment = Alignment.Center) {
        if (pulsing) {
            Box(Modifier.size(size * (1f + 1.2f * pulse)).alpha(1f - pulse).background(c.copy(alpha = 0.5f), CircleShape))
        }
        Box(Modifier.size(size).background(c, CircleShape))
    }
}

/** 状態表示のピル。文字が変わるとスライドして切り替わる。 */
@Composable
fun StatusPill(text: String, color: Color, pulsing: Boolean = false, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(color.copy(alpha = 0.14f), tween(400), label = "pillBg")
    Row(
        modifier.background(bg, CircleShape).padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(color, pulsing, 7.dp)
        AnimatedContent(text, transitionSpec = {
            (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
        }, label = "pillText") { t ->
            Text(t, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 見出し・説明・スイッチの1行。行全体をタップしても切り替わる。 */
@Composable
fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit, desc: String? = null, enabled: Boolean = true, accent: Color = Cam.Accent) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .alpha(if (enabled) 1f else 0.45f)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Cam.Text)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black, checkedTrackColor = accent,
                uncheckedThumbColor = Cam.TextDim, uncheckedTrackColor = Cam.Surface3, uncheckedBorderColor = Cam.Outline
            )
        )
    }
}

@Composable
fun LabeledSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, enabled: Boolean = true, accent: Color = Cam.Accent) {
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
            AnimatedContent(valueText, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) }, label = "sliderVal") {
                Text(it, style = MaterialTheme.typography.labelLarge.merge(Tnum), color = accent)
            }
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Cam.Surface3)
        )
    }
}

/** 長い説明は畳んでおく（「詳しく」で展開）。 */
@Composable
fun InfoNote(text: String, title: String = "詳しい説明", warn: Boolean = false) {
    var open by rememberSaveable(text.hashCode()) { mutableStateOf(false) }
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "infoRot")
    val col = if (warn) Cam.Warn else Cam.TextDim
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Cam.Bg.copy(alpha = 0.5f))
            .clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp)
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (warn) "⚠ $title" else "ⓘ $title", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = col)
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp).rotate(rot), tint = col)
        }
        if (open) Text(text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
    }
}

/** 開閉できるカード。開閉時は高さをアニメーションする。 */
@Composable
fun ExpandableCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color = Cam.Accent,
    initiallyExpanded: Boolean = false,
    enabled: Boolean = true,
    disabledReason: String? = null,
    badge: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val rot by animateFloatAsState(if (open) 180f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "expRot")
    val border by animateColorAsState(if (open) accent.copy(alpha = 0.55f) else Cam.Outline, tween(300), label = "expBorder")
    val iconBg by animateColorAsState(if (open) accent.copy(alpha = 0.22f) else Cam.Surface3, tween(300), label = "expIconBg")
    Surface(
        Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)),
        shape = RoundedCornerShape(22.dp), color = Cam.Surface, border = BorderStroke(1.dp, border)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(42.dp).background(iconBg, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) accent else Cam.TextFaint)
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) Cam.Text else Cam.TextDim)
                        if (badge != null) Text(
                            badge, Modifier.background(accent.copy(alpha = 0.18f), CircleShape).padding(horizontal = 7.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall, color = accent
                        )
                    }
                    Text(
                        if (!enabled && disabledReason != null) disabledReason else subtitle,
                        style = MaterialTheme.typography.bodySmall, color = if (!enabled) Cam.Warn.copy(alpha = 0.8f) else Cam.TextDim,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.rotate(rot), tint = Cam.TextDim)
            }
            AnimatedVisibility(open, enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(
                    Modifier.padding(start = 14.dp, end = 14.dp, bottom = 16.dp).alpha(if (enabled) 1f else 0.5f),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = content
                )
            }
        }
    }
}

/** 大きな主ボタン。押すと縮み、処理中はスピナーに切り替わる。 */
@Composable
fun PrimaryButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
    color: Color = Cam.Accent, contentColor: Color = Color(0xFF1A1200), icon: ImageVector? = null, height: Dp = 56.dp
) {
    val src = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (enabled) color else Cam.Surface3, tween(250), label = "btnBg")
    val fg by animateColorAsState(if (enabled) contentColor else Cam.TextFaint, tween(250), label = "btnFg")
    Box(
        modifier.fillMaxWidth().height(height).pressScale(src)
            .clip(RoundedCornerShape(height / 2))
            .background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0.86f))))
            .clickable(interactionSource = src, indication = null, enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(loading, transitionSpec = { (fadeIn() + slideInVertically { it / 3 }) togetherWith (fadeOut() + slideOutVertically { -it / 3 }) }, label = "btnLoad") { l ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (l) CircularProgressIndicator(Modifier.size(20.dp), color = fg, strokeWidth = 2.5.dp)
                else if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = fg)
                Text(text, style = MaterialTheme.typography.titleMedium, color = fg)
            }
        }
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Cam.Text, icon: ImageVector? = null, height: Dp = 48.dp) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier.height(height).pressScale(src).clip(RoundedCornerShape(height / 2))
            .border(1.dp, if (enabled) color.copy(alpha = 0.35f) else Cam.Outline, RoundedCornerShape(height / 2))
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = color)
            Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
        }
    }
}

/** 選択式のチップ（色・背景がアニメーションする）。 */
@Composable
fun CamChip(label: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null, color: Color = Cam.Accent, enabled: Boolean = true) {
    val src = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) color.copy(alpha = 0.2f) else Cam.Surface2, tween(220), label = "chipBg")
    val bd by animateColorAsState(if (selected) color else Cam.Outline, tween(220), label = "chipBd")
    val fg by animateColorAsState(if (selected) color else Cam.TextDim, tween(220), label = "chipFg")
    Row(
        Modifier.pressScale(src, 0.92f).clip(CircleShape).background(bg).border(1.dp, bd, CircleShape)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** セグメント切替。選択インジケーターがばねで滑る。 */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, accent: Color = Cam.Accent, height: Dp = 40.dp) {
    BoxWithConstraints(modifier.fillMaxWidth().height(height).clip(CircleShape).background(Cam.Surface2).padding(3.dp)) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "segX")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().background(accent.copy(alpha = 0.22f), CircleShape).border(1.dp, accent.copy(alpha = 0.6f), CircleShape))
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, o ->
                val fg by animateColorAsState(if (i == selected) accent else Cam.TextDim, tween(200), label = "segFg")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) { Text(o, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1) }
            }
        }
    }
}

/** 数字が変わると上下にスライドするカウンター。 */
@Composable
fun AnimatedCounter(value: Int, color: Color = Cam.Text, fontSize: Int = 16) {
    AnimatedContent(value, transitionSpec = {
        if (targetState > initialState) (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
        else (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
    }, label = "counter") { v ->
        Text("$v", color = color, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, style = Tnum)
    }
}

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = Cam.Text) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(112.dp), style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.merge(Tnum), color = valueColor)
    }
}

/** 円形の操作ボタン（ライブビュー開始・AFなど）。 */
@Composable
fun RoundIconButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 56.dp, tint: Color = Cam.Text, bg: Color = Cam.Surface2, enabled: Boolean = true, label: String? = null) {
    val src = remember { MutableInteractionSource() }
    val b by animateColorAsState(bg, tween(250), label = "rbBg")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier.size(size).pressScale(src, 0.88f).clip(CircleShape).background(b)
                .border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape)
                .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
                .alpha(if (enabled) 1f else 0.4f),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, Modifier.size(size * 0.42f), tint = tint) }
        if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = Cam.TextDim)
    }
}

/** 空の状態（未接続・非対応など）。アイコンがゆっくり浮遊する。 */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, action: (@Composable () -> Unit)? = null) {
    val t = rememberInfiniteTransition(label = "float")
    val dy by t.animateFloat(-4f, 4f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "floatY")
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(84.dp).graphicsLayer { translationY = dy }.background(Cam.Surface2, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(38.dp), tint = Cam.TextDim)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = Cam.Text)
        Text(message, style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
        if (action != null) { Spacer(Modifier.height(6.dp)); action() }
    }
}

/** 進捗バー（値の変化をなめらかに補間）。 */
@Composable
fun SmoothProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = Cam.Accent, height: Dp = 6.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(450, easing = FastOutSlowInEasing), label = "prog")
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = size.height / 2
        drawRoundRect(Cam.Surface3, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        if (f > 0f) drawRoundRect(
            Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color)),
            size = androidx.compose.ui.geometry.Size(size.width * f, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
        )
    }
}

/** 1度だけ表示時にふわっと出す（リストの段階表示用）。 */
@Composable
fun StaggeredAppear(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(320, delayMillis = index * 55), label = "appearA")
    val s by animateFloatAsState(if (shown) 1f else 0.85f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow), label = "appearS")
    Box(Modifier.graphicsLayer { alpha = a; scaleX = s; scaleY = s }) { content() }
}

@Composable
fun Pulse(modifier: Modifier = Modifier, color: Color, size: Dp) {
    val t = rememberInfiniteTransition(label = "pulse2")
    val p by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p2")
    Box(modifier.size(size).scale(0.8f + 0.2f * p).alpha(p).background(color, CircleShape))
}
