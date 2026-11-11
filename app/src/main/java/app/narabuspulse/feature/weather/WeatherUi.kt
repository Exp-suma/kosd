package app.narabuspulse.feature.weather

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Water
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Logic
import app.narabuspulse.ui.theme.Brand
import kotlinx.serialization.json.JsonPrimitive

/** Web の lib/weather/format.js weatherKind と同じ分類。 */
fun weatherKind(code: Int?): String = when {
    code == null -> "unknown"
    code == 0 -> "sun"
    code <= 2 -> "sunCloud"
    code == 3 -> "cloud"
    code <= 48 -> "fog"
    code <= 57 -> "drizzle"
    code <= 67 -> "rain"
    code <= 77 -> "snow"
    code <= 82 -> "shower"
    code <= 86 -> "snow"
    else -> "thunder"
}

private val INFO = Color(0xFF2F6FB0)

fun weatherIcon(code: Int?): Pair<ImageVector, Color> = when (weatherKind(code)) {
    "sun" -> Icons.Outlined.WbSunny to Brand.Warning
    "sunCloud" -> Icons.Outlined.WbCloudy to Brand.Warning
    "cloud" -> Icons.Outlined.Cloud to Brand.MutedFg
    "fog" -> Icons.Outlined.Dehaze to Brand.MutedFg
    "drizzle" -> Icons.Outlined.Grain to INFO
    "rain", "shower" -> Icons.Outlined.Water to INFO
    "snow" -> Icons.Outlined.AcUnit to INFO
    "thunder" -> Icons.Outlined.Thunderstorm to Brand.Destructive
    else -> Icons.Outlined.AcUnit to Brand.MutedFg
}

/** 天気のアイコン（と名前）。名前は Web と同じ lib/shoot/weather.js の weatherLabel を使う。 */
@Composable
fun WeatherSymbol(code: Int?, size: Dp = 18.dp, showLabel: Boolean = false, unavailable: String = "—") {
    if (code == null) {
        Text(unavailable, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val (icon, tint) = weatherIcon(code)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size))
        if (showLabel) {
            val label = produceState("", code) { value = (Logic.call("shoot/weather", "weatherLabel", code) as? JsonPrimitive)?.content.orEmpty() }
            Spacer(Modifier.width(4.dp))
            Text(label.value, fontSize = 12.sp)
        }
    }
}
