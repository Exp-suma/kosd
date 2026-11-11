package app.narabuspulse.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Web 版（src/index.css）の色をそのまま使う。 */
object Brand {
    val Orange = Color(0xFFE66116)      // 奈良交通オレンジ
    val Background = Color(0xFFF2F4F5)
    val Foreground = Color(0xFF333333)
    val Muted = Color(0xFFECEFF1)
    val MutedFg = Color(0xFF616A73)
    val Border = Color(0xFFD5DADF)
    val Success = Color(0xFF27AE60)
    val Warning = Color(0xFFD99A0E)
    val Destructive = Color(0xFFC3352B)
    val Accent = Color(0xFFFEF1E8)
    val Chart = listOf(
        Color(0xFFE66116), Color(0xFF4A55A2), Color(0xFF27AE60), Color(0xFFF0B429), Color(0xFF8E5BC1),
    )
}

private val Light = lightColorScheme(
    primary = Brand.Orange,
    onPrimary = Color.White,
    primaryContainer = Brand.Accent,
    onPrimaryContainer = Color(0xFF7A2E05),
    secondary = Color(0xFF4A55A2),
    onSecondary = Color.White,
    background = Brand.Background,
    onBackground = Brand.Foreground,
    surface = Color.White,
    onSurface = Brand.Foreground,
    surfaceVariant = Brand.Muted,
    onSurfaceVariant = Brand.MutedFg,
    outline = Brand.Border,
    outlineVariant = Brand.Border,
    error = Brand.Destructive,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF07A33),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF5A2A0D),
    secondary = Color(0xFF8C95D9),
    background = Color(0xFF15181B),
    surface = Color(0xFF1E2226),
    surfaceVariant = Color(0xFF2A2F34),
    onSurfaceVariant = Color(0xFFA9B1B9),
    outline = Color(0xFF3A4046),
    error = Color(0xFFE5655B),
)

@Composable
fun NaraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(12.dp),
        ),
        content = content,
    )
}
