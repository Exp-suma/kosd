package app.narabuspulse.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneOffset

private val smallPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)

/** 日付の選択（yyyy-MM-dd。Web の <input type="date">）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, min: String? = null, max: String? = null, label: String? = null) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.heightIn(min = 36.dp), contentPadding = smallPadding) {
        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(16.dp))
        Text(" ${label?.let { "$it " } ?: ""}${value.ifBlank { "日付" }}", fontSize = 12.sp)
    }
    if (open) {
        val toMs = { s: String? -> s?.let { runCatching { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() } }
        val minMs = toMs(min); val maxMs = toMs(max)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = toMs(value),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = (minMs == null || utcTimeMillis >= minMs) && (maxMs == null || utcTimeMillis <= maxMs)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("決定") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("キャンセル") } },
        ) { DatePicker(state) }
    }
}

/** 時刻の選択（HH:mm。Web の <input type="time">）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.heightIn(min = 36.dp), contentPadding = smallPadding) {
        Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp))
        Text(" ${label?.let { "$it " } ?: ""}${value.ifBlank { "--:--" }}", fontSize = 12.sp)
    }
    if (open) {
        val parts = value.split(":")
        val state = rememberTimePickerState(parts.getOrNull(0)?.toIntOrNull() ?: 12, parts.getOrNull(1)?.toIntOrNull() ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = { onChange("%02d:%02d".format(state.hour, state.minute)); open = false }) { Text("決定") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("キャンセル") } },
            text = { TimePicker(state) },
        )
    }
}

/** 選択肢（Web の <select>）。 */
@Composable
fun <T> SelectField(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "選択",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, contentPadding = smallPadding, modifier = Modifier.heightIn(min = 36.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (label != null) Text("$label ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(options.firstOrNull { it.first == selected }?.second ?: placeholder, fontSize = 12.sp, maxLines = 1)
                Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp))
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text, fontSize = 13.sp) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}

/** 小さめのボタン（Web の Button size="sm" variant="outline"）。 */
@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, primary: Boolean = false) {
    if (primary) {
        androidx.compose.material3.Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 34.dp), contentPadding = smallPadding) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp).padding(end = 4.dp))
            Text(text, fontSize = 12.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 34.dp), contentPadding = smallPadding) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp).padding(end = 4.dp))
            Text(text, fontSize = 12.sp)
        }
    }
}
