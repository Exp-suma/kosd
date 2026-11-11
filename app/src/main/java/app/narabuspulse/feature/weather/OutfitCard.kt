package app.narabuspulse.feature.weather

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Logic
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.rememberLocationRequest
import app.narabuspulse.core.str
import app.narabuspulse.core.at
import app.narabuspulse.core.text
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlin.math.roundToInt

/** 選んだ地点（Web の localStorage "outfit-place-v1" と同じ内容を端末に保存）。 */
private data class OutfitPlace(val code: String? = "nara", val lat: Double? = null, val lon: Double? = null, val label: String? = null)

private fun loadPlace(ctx: Context): OutfitPlace {
    val p = ctx.getSharedPreferences("outfit", Context.MODE_PRIVATE)
    val code = p.getString("code", null)
    if (code != null) return OutfitPlace(code)
    if (p.contains("lat")) return OutfitPlace(null, p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble(), p.getString("label", "現在地"))
    return OutfitPlace()
}

private fun savePlace(ctx: Context, place: OutfitPlace) {
    ctx.getSharedPreferences("outfit", Context.MODE_PRIVATE).edit().clear().apply {
        if (place.code != null) putString("code", place.code)
        else { putFloat("lat", place.lat!!.toFloat()); putFloat("lon", place.lon!!.toFloat()); putString("label", place.label) }
    }.apply()
}

private val CELL_TONE = listOf(Brand.Destructive, Brand.Destructive, Brand.Warning, Brand.Warning, Color(0xFF2F6FB0), Color(0xFF2F6FB0), Brand.Orange, Brand.Orange)

/** 今日のおすすめの服装（Web: components/weather/OutfitCard.jsx）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutfitCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var place by remember { mutableStateOf(loadPlace(ctx)) }
    var data by remember { mutableStateOf<JsonElement?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var geoNote by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val municipalities by produceState<List<JsonElement>>(emptyList()) { value = Logic.constant("weather/municipalities", "MUNICIPALITIES").items() }

    suspend fun load(force: Boolean) {
        busy = true; error = null
        try {
            val payload = if (place.code != null) jsonOf("code" to place.code, "force" to force)
            else jsonOf("lat" to place.lat, "lon" to place.lon, "label" to (place.label ?: "現在地"), "force" to force)
            val res = Api.rpc("weather/outfit-fns#getOutfitAdvice", payload)
            if (res.bool("ok") == true) data = res else error = res.str("error") ?: "気象データを取得できませんでした"
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.userMessage()
        } finally { busy = false }
    }

    LaunchedEffect(place) {
        while (true) { load(false); delay(15 * 60_000L) }
    }

    val requestLocation = rememberLocationRequest { loc ->
        if (loc == null) { geoNote = "現在地を取得できませんでした（位置情報の許可を確認してください）。"; return@rememberLocationRequest }
        scope.launch {
            val nearest = Logic.call("weather/outfit", "nearestPlace", loc.lat, loc.lon, municipalities)
            val label = nearest.str("name")?.let { "現在地（${it}付近）" } ?: "現在地"
            val next = OutfitPlace(null, loc.lat, loc.lon, label)
            savePlace(ctx, next); place = next; geoNote = ""
        }
    }

    val advice by produceState<JsonElement?>(null, data) {
        val d = data
        value = if (d != null && d.bool("ok") == true) Logic.call("weather/outfit", "buildOutfitAdvice", jsonOf("current" to d.at("current"), "day" to d.at("day"))) else null
    }
    val month by produceState<JsonElement?>(null, data) {
        val d = data
        value = if (d != null) Logic.call("weather/outfit", "buildMonthlyOutfit", d.at("month") ?: kotlinx.serialization.json.JsonArray(emptyList())) else null
    }

    val placeName = data?.str("location.name") ?: (if (place.code == null) place.label else null) ?: "奈良市"
    val available = advice?.bool("available") == true

    Panel(
        title = "今日のおすすめの服装",
        subtitle = if (available) "$placeName・${advice.str("bandName")}" else placeName,
        modifier = modifier,
        action = {
            Row {
                IconButton(onClick = requestLocation) { Icon(Icons.Outlined.MyLocation, "現在地の気温を使う", Modifier.size(18.dp)) }
                IconButton(onClick = { scope.launch { load(true) } }, enabled = !busy) { Icon(Icons.Outlined.Refresh, "服装のおすすめを更新", Modifier.size(18.dp)) }
                Box {
                    TextButton(onClick = { menu = true }) { Text(if (place.code == null) "現在地" else municipalities.firstOrNull { it.str("code") == place.code }?.str("name") ?: "地点", fontSize = 12.sp) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        municipalities.forEach { m ->
                            DropdownMenuItem(text = { Text(m.str("name").orEmpty()) }, onClick = {
                                menu = false
                                val next = OutfitPlace(m.str("code"))
                                savePlace(ctx, next); place = next; geoNote = ""
                            })
                        }
                    }
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (data == null && busy) Text("気温と天候を取得しています…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { msg ->
                Row(
                    Modifier.fillMaxWidth().background(Brand.Warning.copy(alpha = 0.1f), RoundedCornerShape(4.dp)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { load(true) } }) { Text("再取得", fontSize = 12.sp) }
                }
            }
            val a = advice
            if (a != null && available) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                        .border(1.dp, Brand.Orange.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).padding(10.dp),
                ) {
                    Icon(Icons.Outlined.Checkroom, null, tint = Brand.Orange, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("きょうの支度：${a.str("bandShort")}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(a.str("lead").orEmpty(), fontSize = 12.sp)
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${a.dbl("temp")?.roundToInt() ?: "—"}", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text("℃", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("体感 ${a.dbl("feels")?.let { "${it.roundToInt()}℃" } ?: "取得不可"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WeatherSymbol(a.int("code"), 14.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(a.str("weather") ?: "天気取得不可", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("風 ${a.dbl("wind")?.let { "${fmt1(it)}m/s" } ?: "—"}", fontSize = 11.sp)
                        Text("湿度 ${a.int("humidity")?.let { "$it%" } ?: "—"}", fontSize = 11.sp)
                        Text("降水 ${a.int("pop")?.let { "$it%" } ?: "—"}", fontSize = 11.sp)
                    }
                }
                Text("おすすめ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    a.list("wear").forEach { Chip(it.text, false) }
                }
                val gear = a.list("gear")
                if (gear.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Umbrella, null, tint = Brand.Orange, modifier = Modifier.size(14.dp))
                    gear.forEach { Chip(it.text, true) }
                }
                // 朝・昼・夜
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.list("hourly").forEach { slot ->
                        Surface(Modifier.weight(1f), shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                            Column(Modifier.padding(6.dp)) {
                                Text("${slot.str("label")} ${slot.str("hour")}時", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (slot.bool("available") == true) {
                                    Text("${slot.dbl("temp")?.roundToInt()}℃", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text(slot.str("bandShort").orEmpty() + if (slot.bool("estimated") == true) "（目安）" else "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else Text("取得不可", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                MonthlyOutfit(month)
                Column {
                    a.list("reasons").forEach { r -> Text("・${r.text}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } else if (data?.bool("ok") == true && a != null && error == null) {
                Text("${a.str("reason") ?: "気温を取得できませんでした"}。時間をおいて更新してください。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (geoNote.isNotEmpty()) Text(geoNote, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                Text("更新 ${BusFormat.time(data?.long("fetchedAt"))}${if (data?.bool("stale") == true) "（前回の取得値）" else ""}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(data?.str("source")?.ifBlank { null } ?: "Open-Meteo", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthlyOutfit(month: JsonElement?) {
    if (month?.bool("available") != true) return
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text("月間の服装（予想気温・30日）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("${month.str("coldShort")}〜${month.str("warmShort")}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(month.str("lead").orEmpty(), fontSize = 12.sp)
        Text(month.str("summary").orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(6.dp))
        val days = month.list("days")
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    val ok = day.bool("available") == true
                    val tone = if (ok) CELL_TONE.getOrElse(day.int("index") ?: 7) { Brand.Orange } else Brand.MutedFg
                    val date = day.str("date").orEmpty().split("-").let { if (it.size == 3) "${it[1].toInt()}/${it[2].toInt()}" else "—" }
                    val temp = if (ok) "${day.dbl("tmax")?.roundToInt() ?: "—"}/${day.dbl("tmin")?.roundToInt() ?: "—"}" else "—"
                    Column(
                        Modifier.weight(1f).background(tone.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .border(1.dp, tone.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(date, fontSize = 9.sp, color = tone)
                        Text(temp, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = tone, maxLines = 1)
                        Text(if (ok) day.str("cellLabel").orEmpty() else "—", fontSize = 9.sp, color = tone, maxLines = 1)
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text("${month.str("note").orEmpty()}日中の最高気温で判定しています。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Chip(text: String, accent: Boolean) {
    val color = if (accent) Brand.Orange else MaterialTheme.colorScheme.onSurface
    Text(
        text,
        fontSize = 11.sp,
        color = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else color,
        modifier = Modifier
            .background(if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .border(1.dp, if (accent) Brand.Orange.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun fmt1(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else String.format("%.1f", v)
