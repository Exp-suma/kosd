package com.example.d750bridge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.d750bridge.camera.CameraBrand

/** カメラアプリらしい、暗い背景＋アンバーのアクセント。 */
object Cam {
    val Bg = Color(0xFF0A0B0E)
    val Surface = Color(0xFF14161B)
    val Surface2 = Color(0xFF1B1E25)
    val Surface3 = Color(0xFF252932)
    val Outline = Color(0xFF2C313B)
    val Accent = Color(0xFFFFB020)
    val AccentSoft = Color(0x26FFB020)
    val Ai = Color(0xFF3DDBC6)
    val Ok = Color(0xFF34E39B)
    val Warn = Color(0xFFFFC857)
    val Err = Color(0xFFFF5A5F)
    val Rec = Color(0xFFFF3B30)
    val Pin = Color(0xFFE05CFF)
    val Text = Color(0xFFF3F4F6)
    val TextDim = Color(0xFF9CA3AF)
    val TextFaint = Color(0xFF6B7280)
}

fun brandColor(b: CameraBrand): Color = when (b) {
    CameraBrand.NIKON -> Color(0xFFFFD60A)
    CameraBrand.CANON -> Color(0xFFFF4D4F)
    CameraBrand.SONY -> Color(0xFFE6E9EF)
    CameraBrand.LUMIX -> Color(0xFF4C8DFF)
}

private val scheme = darkColorScheme(
    primary = Cam.Accent,
    onPrimary = Color(0xFF1A1200),
    primaryContainer = Color(0xFF3A2A05),
    onPrimaryContainer = Cam.Accent,
    secondary = Cam.Ai,
    onSecondary = Color(0xFF00201C),
    tertiary = Cam.Pin,
    background = Cam.Bg,
    onBackground = Cam.Text,
    surface = Cam.Surface,
    onSurface = Cam.Text,
    surfaceVariant = Cam.Surface2,
    onSurfaceVariant = Cam.TextDim,
    surfaceContainerLowest = Cam.Bg,
    surfaceContainerLow = Cam.Surface,
    surfaceContainer = Cam.Surface2,
    surfaceContainerHigh = Cam.Surface2,
    surfaceContainerHighest = Cam.Surface3,
    outline = Cam.Outline,
    outlineVariant = Cam.Outline,
    error = Cam.Err,
    onError = Color.White,
    inverseSurface = Cam.Text,
    inverseOnSurface = Cam.Bg
)

private val base = Typography()
private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    bodySmall = base.bodySmall.copy(lineHeight = 17.sp)
)

/** 数値表示用（桁幅をそろえてチラつかないようにする）。 */
val Tnum = TextStyle(fontFeatureSettings = "tnum")

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun CamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
