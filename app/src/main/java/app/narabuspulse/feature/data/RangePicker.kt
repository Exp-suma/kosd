package app.narabuspulse.feature.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.SmallButton
import java.time.LocalDate

data class DateRange(val from: String, val to: String)

fun jstTodayKey(): String = BusFormat.dayKey(System.currentTimeMillis())
fun addDays(key: String, days: Int): String = LocalDate.parse(key).plusDays(days.toLong()).toString()

private val PRESETS = listOf("7日" to 7, "30日" to 30, "90日" to 90, "180日" to 180, "1年" to 365)

/** 期間の選択（Web: components/analytics/RangePicker.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RangePicker(range: DateRange, onChange: (DateRange) -> Unit, extra: (@Composable () -> Unit)? = null) {
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            PRESETS.forEach { (label, days) -> SmallButton(label, { val t = jstTodayKey(); onChange(DateRange(addDays(t, -(days - 1)), t)) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("期間", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DateField(range.from, { onChange(range.copy(from = it)) }, max = range.to)
            Text("〜", fontSize = 12.sp)
            DateField(range.to, { onChange(range.copy(to = it)) }, min = range.from)
            extra?.invoke()
        }
    }
}
