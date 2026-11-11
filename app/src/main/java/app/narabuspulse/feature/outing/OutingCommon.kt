package app.narabuspulse.feature.outing

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Json
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.rememberLocationRequest
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.core.toJson
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val JST: ZoneId = ZoneId.of("Asia/Tokyo")
private val STAMP = DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

internal fun stamp(ts: Long?): String = if (ts == null || ts == 0L) "—" else STAMP.format(Instant.ofEpochMilli(ts).atZone(JST))
internal fun clockOf(ts: Long?): String = if (ts == null || ts == 0L) "—" else CLOCK.format(Instant.ofEpochMilli(ts).atZone(JST))
internal fun parseJson(text: String?): JsonElement? = if (text.isNullOrBlank()) null else runCatching { Json.parse(text) }.getOrNull()?.takeIf { it !is JsonNull }
internal fun JsonObject.plus(vararg p: Pair<String, Any?>): JsonObject = JsonObject(this + p.associate { it.first to toJson(it.second) })

/** Web: lib/outing/schedule.js の時刻計算（JST・純粋関数）。 */
internal object OutingTime {
    data class Jst(val date: String, val minutes: Int, val clock: String)
    fun now(ts: Long = System.currentTimeMillis()): Jst {
        val d = Instant.ofEpochMilli(ts).atZone(JST)
        return Jst(d.toLocalDate().toString(), d.hour * 60 + d.minute, "%02d:%02d".format(d.hour, d.minute))
    }
    fun toMin(hhmm: String?): Int? = Regex("^(\\d{1,2}):(\\d{2})$").find(hhmm.orEmpty())?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
    fun durationLabel(minutes: Number?): String {
        if (minutes == null) return "—"
        val m = maxOf(0, Math.round(minutes.toDouble()).toInt())
        return if (m < 60) "${m}分" else "${m / 60}時間${if (m % 60 != 0) "${m % 60}分" else ""}"
    }
    fun durationMin(trip: JsonElement?): Int? {
        val d = toMin(trip.str("depart_at")); val r = toMin(trip.str("return_by"))
        return when { d != null && r != null -> if (r >= d) r - d else r + 1440 - d; d != null && trip.dbl("stay_min") != null -> trip.int("stay_min")!! + 60; else -> null }
    }
    fun phase(trip: JsonElement?, ts: Long = System.currentTimeMillis()): String {
        val date = trip.str("date") ?: return "before"
        val n = now(ts)
        if (date > n.date) return "before"
        if (date < n.date) return "after"
        val d = toMin(trip.str("depart_at")); val r = toMin(trip.str("return_by"))
        if (r != null && n.minutes > r) return "after"
        if (d != null && n.minutes < d - 30) return "before"
        return "during"
    }
    fun remaining(trip: JsonElement?, ts: Long = System.currentTimeMillis()): Int? {
        val date = trip.str("date") ?: return null
        val n = now(ts); val r = toMin(trip.str("return_by")) ?: return null
        if (date == n.date) return maxOf(0, r - n.minutes)
        if (date > n.date) {
            val days = runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(n.date), LocalDate.parse(date)) }.getOrDefault(0L).toInt()
            return maxOf(0, days * 1440 + r - n.minutes)
        }
        return 0
    }
    fun routeLabel(route: JsonElement?): String {
        if (route == null) return "未設定"
        val parts = mutableListOf<String>()
        if (route.str("departure") != null || route.str("arrival") != null) parts += "${route.str("departure")?.ifBlank { null } ?: "—"} → ${route.str("arrival")?.ifBlank { null } ?: "—"}"
        route.dbl("durationMin")?.let { parts += durationLabel(it) }
        route.int("transfers")?.let { parts += "乗換${it}回" }
        route.long("fare")?.let { parts += "${it}円" }
        return parts.joinToString("・").ifBlank { "未設定" }
    }
    val PHASE_LABEL = mapOf("before" to "出発前", "during" to "外出中", "after" to "帰宅後")
    val PHASE_TONE = mapOf("before" to INFO, "during" to Brand.Success, "after" to Brand.MutedFg)
}

internal val PURPOSE_LABEL = mapOf("shooting" to "撮影", "tourism" to "観光", "expedition" to "遠征", "business" to "仕事", "other" to "その他")
internal val DEVICE_KINDS = listOf("phone" to "スマートフォン", "camera" to "カメラ", "powerbank" to "モバイルバッテリー", "tablet" to "タブレット", "other" to "その他")
internal val GEAR_CATEGORIES = listOf("camera" to "撮影機材", "power" to "電源・バッテリー", "rain" to "雨具", "clothing" to "衣類・防寒", "electronics" to "電子機器", "credential" to "財布・カード", "food" to "飲料・食料", "other" to "その他")
internal val CATEGORY_LABEL = GEAR_CATEGORIES.toMap()
internal val PRIORITY_LABEL = mapOf("essential" to "必須", "high" to "重要", "normal" to "通常")
internal val PRIORITY_TONE = mapOf("essential" to Brand.Destructive, "high" to Brand.Warning, "normal" to Brand.MutedFg)
internal val GEAR_STATUS = listOf("available" to "保管中", "carried" to "持ち出し中", "lost" to "紛失", "repair" to "修理中")
internal fun levelColor(level: String?) = when (level) { "danger" -> Brand.Destructive; "warning" -> Brand.Warning; "info" -> INFO; "success" -> Brand.Success; else -> Brand.MutedFg }

/** 端末に保存する外出アシスタントの設定・表示キャッシュ（Web: lib/outing/settings.js・cache.js）。 */
internal object OutingPrefs {
    data class Thresholds(val high: Int = 30, val low: Int = 20, val critical: Int = 10)
    private fun p(c: Context) = c.getSharedPreferences("outing", Context.MODE_PRIVATE)
    fun thresholds(c: Context): Thresholds {
        val v = parseJson(p(c).getString("thresholds", null))
        return Thresholds(v.int("high")?.takeIf { it > 0 }?.coerceAtMost(95) ?: 30, v.int("low")?.takeIf { it > 0 }?.coerceAtMost(90) ?: 20, v.int("critical")?.takeIf { it > 0 }?.coerceAtMost(80) ?: 10)
    }
    fun saveThresholds(c: Context, t: Thresholds): Thresholds { p(c).edit().putString("thresholds", jsonOf("high" to t.high, "low" to t.low, "critical" to t.critical).toString()).apply(); return thresholds(c) }
    data class GearSet(val name: String, val gearIds: List<String>, val at: Long)
    fun gearSets(c: Context): List<GearSet> = (parseJson(p(c).getString("gearsets", null)) as? JsonArray)?.mapNotNull { r -> r.str("name")?.let { GearSet(it, r.list("gearIds").map { g -> g.text }, r.long("at") ?: 0) } } ?: emptyList()
    private fun writeSets(c: Context, rows: List<GearSet>) = p(c).edit().putString("gearsets", JsonArray(rows.map { jsonOf("name" to it.name, "gearIds" to it.gearIds, "at" to it.at) }).toString()).apply()
    fun saveGearSet(c: Context, name: String, ids: List<String>) = writeSets(c, gearSets(c).filter { it.name != name } + GearSet(name, ids.distinct(), System.currentTimeMillis()))
    fun deleteGearSet(c: Context, name: String) = writeSets(c, gearSets(c).filter { it.name != name })
    fun writeCache(c: Context, key: String, data: JsonElement) { runCatching { p(c).edit().putString("cache:$key", jsonOf("at" to System.currentTimeMillis(), "data" to data).toString()).apply() } }
    fun readCache(c: Context, key: String): Pair<Long, JsonElement>? = parseJson(p(c).getString("cache:$key", null))?.let { v -> v.objAt("data")?.let { (v.long("at") ?: 0) to it } }
}

/** 現在の端末の電池残量（Android の BatteryManager から取得）。 */
internal fun deviceBattery(c: Context): Pair<Int, Boolean>? {
    val i: Intent = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return Math.round(level * 100f / scale) to (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
}

private val OUTING_TABS = listOf("/outing" to "今日", "/outing/plans" to "予定", "/outing/navi" to "経路", "/outing/gear" to "持ち物", "/outing/battery" to "バッテリー")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OutingHeader(current: String, title: String, description: String, actions: (@Composable () -> Unit)? = null) {
    val nav = LocalNav.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(OUTING_TABS, current, { if (it != current) nav.go(it) })
        Heading(title, description)
        actions?.let { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { it() } }
    }
}

/** 角丸の枠付きボックス（Web の rounded-lg border）。 */
@Composable
internal fun Boxed(modifier: Modifier = Modifier, tone: Color? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.fillMaxWidth().border(1.dp, (tone ?: MaterialTheme.colorScheme.outline).copy(alpha = if (tone != null) 0.4f else 0.6f), shape)
        .background(tone?.copy(alpha = 0.08f) ?: Color.Transparent, shape).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}

internal data class OutAlert(val key: String, val level: String, val text: String, val change: Boolean = false, val action: (@Composable () -> Unit)? = null)

/** 警告（電池・忘れ物・予定変更・帰宅）をまとめて表示する（Web: AlertStack.jsx）。 */
@Composable
internal fun AlertStack(items: List<OutAlert>, title: String = "確認が必要な項目", emptyText: String = "") {
    if (items.isEmpty()) { if (emptyText.isNotBlank()) Boxed { Muted(emptyText, 11) }; return }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title.isNotBlank()) Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg)
        items.forEach { a ->
            val c = levelColor(a.level)
            Boxed(tone = c) {
                Text("${if (a.change) "✨" else when (a.level) { "danger" -> "⚠"; "warning" -> "🔋"; else -> "ℹ" }} ${a.text}", fontSize = 12.sp, color = c)
                a.action?.invoke()
            }
        }
    }
}

/** 地点の入力（駅・停留所・施設の候補検索／緯度経度／現在地）。Web: outing/PlaceInput.jsx。値は endpoint/label/lat/lon の JsonObject。 */
@Composable
internal fun PlaceInput(label: String, value: JsonObject?, onChange: (JsonObject?) -> Unit, placeholder: String, allowGeolocate: Boolean = false) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(value.str("label").orEmpty()) }
    var applied by remember { mutableStateOf(value.str("label").orEmpty()) }
    LaunchedEffect(value.str("label")) { val n = value.str("label").orEmpty(); if (n.isNotBlank() && n != applied) { applied = n; text = n } }
    var items by remember { mutableStateOf(listOf<JsonElement>()) }
    var busy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var coordOpen by remember { mutableStateOf(false) }
    var lat by remember { mutableStateOf("") }; var lon by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    fun query(k: String) {
        job?.cancel()
        if (k.isBlank()) { items = emptyList(); return }
        job = scope.launch {
            delay(300); busy = "suggest"; error = ""
            try {
                val r = Api.rpc("transit/server-fns#suggestTransitPlaces", jsonOf("q" to k.trim(), "limit" to 8))
                if (r.bool("ok") == false) error = r.str("error") ?: "候補を取得できませんでした" else items = r.items("places")
            } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "候補を取得できませんでした" } } finally { busy = "" }
        }
    }
    suspend fun nameAt(la: Double, lo: Double): String? = runCatching { Api.rpc("transit/server-fns#reverseTransitPlace", jsonOf("lat" to la, "lon" to lo)).items("places").firstOrNull()?.str("name") }.getOrNull()
    val locate = rememberLocationRequest { loc ->
        if (loc == null) { busy = ""; error = "現在地を取得できませんでした（位置情報の利用が許可されていない可能性があります）"; return@rememberLocationRequest }
        scope.launch {
            val la = "%.6f".format(loc.lat).toDouble(); val lo = "%.6f".format(loc.lon).toDouble()
            val name = nameAt(la, lo)?.let { "${it}付近（現在地）" } ?: "現在地"
            busy = ""; text = name; applied = name
            onChange(jsonOf("endpoint" to "geo:$la,$lo", "label" to name, "lat" to la, "lon" to lo))
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, color = Brand.MutedFg, modifier = Modifier.weight(1f))
            if (allowGeolocate) TextButton(onClick = { busy = "geo"; error = ""; locate() }) { Icon(Icons.Outlined.MyLocation, null, Modifier.size(14.dp)); Text(if (busy == "geo") " 取得中…" else " 現在地", fontSize = 11.sp) }
            TextButton(onClick = { coordOpen = !coordOpen }) { Text("⌖ 緯度経度", fontSize = 11.sp) }
        }
        OutlinedTextField(text, { text = it; if (value != null) onChange(null); query(it) }, placeholder = { Text(placeholder, fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Outlined.Place, null, Modifier.size(16.dp)) },
            trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = ""; onChange(null); items = emptyList() }) { Icon(Icons.Outlined.Close, "消去") } },
            supportingText = if (busy == "suggest") ({ Text("検索中…", fontSize = 10.sp) }) else null)
        if (items.isNotEmpty() && value == null) Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))) {
            items.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable {
                    val n = p.str("name").orEmpty(); text = n; applied = n; items = emptyList()
                    onChange(jsonOf("endpoint" to p.str("endpoint").orEmpty(), "label" to n, "lat" to p.dbl("lat"), "lon" to p.dbl("lon")))
                }.padding(horizontal = 8.dp, vertical = 7.dp)) {
                    Text(p.str("name").orEmpty(), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Muted(when (p.str("kind")) { "station" -> "駅"; "stop" -> "停留所"; "address" -> "住所"; else -> "施設" }, 10)
                }
            }
        }
        if (coordOpen) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            OutlinedTextField(lat, { lat = it }, label = { Text("緯度", fontSize = 10.sp) }, placeholder = { Text("34.685") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(lon, { lon = it }, label = { Text("経度", fontSize = 10.sp) }, placeholder = { Text("135.805") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            SmallButton(if (busy == "coord") "確認中…" else "この座標を使う", {
                val la = lat.toDoubleOrNull(); val lo = lon.toDoubleOrNull()
                if (la == null || lo == null || kotlin.math.abs(la) > 90 || kotlin.math.abs(lo) > 180) { error = "緯度（-90〜90）と経度（-180〜180）を入力してください"; return@SmallButton }
                busy = "coord"; error = ""
                scope.launch {
                    val name = nameAt(la, lo)?.let { "$it（${"%.4f".format(la)}, ${"%.4f".format(lo)}）" } ?: "緯度経度 ${"%.4f".format(la)}, ${"%.4f".format(lo)}"
                    busy = ""; coordOpen = false; lat = ""; lon = ""; text = name; applied = name
                    onChange(jsonOf("endpoint" to "geo:$la,$lo", "label" to name, "lat" to la, "lon" to lo))
                }
            }, enabled = busy != "coord")
        }
        val la = value.dbl("lat"); val lo = value.dbl("lon")
        if (la != null && lo != null) Muted("座標 ${"%.4f".format(la)}, ${"%.4f".format(lo)}", 10)
        if (error.isNotBlank()) Text(error, fontSize = 10.sp, color = Brand.Destructive)
    }
}

private val PURPOSES = listOf("shooting" to "撮影", "tourism" to "観光", "expedition" to "遠征", "business" to "仕事", "other" to "その他")
private val TRANSPORTS = listOf("mixed" to "指定なし（複数）", "rail" to "鉄道中心", "bus" to "バス中心", "walk" to "徒歩中心", "car" to "車")

/** 外出予定の登録・編集（Web: TripForm.jsx）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TripForm(initial: JsonElement?, onSave: (JsonObject) -> Unit, onCancel: (() -> Unit)?, busy: Boolean) {
    var title by remember { mutableStateOf(initial.str("title").orEmpty()) }
    var date by remember { mutableStateOf(initial.str("date") ?: OutingTime.now().date) }
    var purpose by remember { mutableStateOf(initial.str("purpose") ?: "shooting") }
    var depart by remember { mutableStateOf(initial.str("depart_at") ?: "08:00") }
    var ret by remember { mutableStateOf(initial.str("return_by") ?: "18:00") }
    var stay by remember { mutableStateOf((initial.int("stay_min") ?: 120).toString()) }
    var transport by remember { mutableStateOf(initial.str("transport") ?: "mixed") }
    var lodging by remember { mutableStateOf(initial.bool("lodging") == true) }
    var shooting by remember { mutableStateOf(initial.bool("shooting") ?: (initial.str("purpose") == "shooting" || initial == null)) }
    var shootingMin by remember { mutableStateOf((initial.int("shooting_min") ?: 90).toString()) }
    var notes by remember { mutableStateOf(initial.str("notes").orEmpty()) }
    var origin by remember { mutableStateOf(initial.str("from_id")?.takeIf { it.isNotBlank() }?.let { jsonOf("label" to initial.str("origin").orEmpty(), "endpoint" to it) }) }
    var dest by remember { mutableStateOf(if (!initial.str("to_id").isNullOrBlank() || initial.dbl("lat") != null) jsonOf("label" to initial.str("dest_name").orEmpty(), "endpoint" to initial.str("to_id").orEmpty(), "lat" to initial.dbl("lat"), "lon" to initial.dbl("lon")) else null) }
    var error by remember { mutableStateOf("") }
    Boxed {
        OutlinedTextField(title, { title = it }, label = { Text("予定の名前", fontSize = 11.sp) }, placeholder = { Text("飛鳥のバス撮影", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Muted("日付", 11); DateField(date, { date = it }) }
        PlaceInput("出発地（任意）", origin, { origin = it }, "自宅の最寄駅など")
        PlaceInput("目的地（必須）", dest, { dest = it }, "撮影地・駅・施設名")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Muted("出発", 11); TimeField(depart, { depart = it }); Muted("帰宅目標", 11); TimeField(ret, { ret = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(stay, { stay = it.filter(Char::isDigit) }, label = { Text("滞在（分）", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(shootingMin, { shootingMin = it.filter(Char::isDigit) }, label = { Text("撮影（分）", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectField(PURPOSES, purpose, { purpose = it }, label = "目的"); SelectField(TRANSPORTS, transport, { transport = it }, label = "移動手段")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(shooting, { shooting = it }); Text("撮影あり", fontSize = 12.sp)
            Checkbox(lodging, { lodging = it }); Text("宿泊あり", fontSize = 12.sp)
        }
        OutlinedTextField(notes, { notes = it }, label = { Text("メモ（任意）", fontSize = 11.sp) }, placeholder = { Text("集合場所・持ち帰りたい被写体など", fontSize = 12.sp) }, minLines = 2, modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            onCancel?.let { TextButton(onClick = it, enabled = !busy) { Text("キャンセル", fontSize = 12.sp) } }
            SmallButton(if (busy) "保存中…" else if (initial.str("id") != null) "予定を更新" else "予定を保存", {
                if (title.isBlank()) { error = "予定の名前を入力してください"; return@SmallButton }
                if (dest.str("label").isNullOrBlank()) { error = "目的地を候補から選ぶか、緯度経度で指定してください"; return@SmallButton }
                error = ""
                onSave(jsonOf("id" to initial.str("id"), "title" to title.trim(), "date" to date, "purpose" to purpose, "origin" to origin.str("label").orEmpty(), "from_id" to origin.str("endpoint").orEmpty(),
                    "dest_name" to dest.str("label"), "to_id" to dest.str("endpoint").orEmpty(), "lat" to dest.dbl("lat"), "lon" to dest.dbl("lon"), "depart_at" to depart.ifBlank { null }, "return_by" to ret.ifBlank { null },
                    "stay_min" to (stay.toIntOrNull() ?: 0).coerceIn(0, 1440), "transport" to transport, "lodging" to lodging, "shooting" to shooting, "shooting_min" to (shootingMin.toIntOrNull() ?: 0).coerceIn(0, 720), "notes" to notes))
            }, enabled = !busy, primary = true)
        }
    }
}

/** 予定の保存用ペイロード（既存値を保ったまま一部だけ更新する）。Web: lib/outing/payload.js */
internal fun tripSavePayload(trip: JsonElement, vararg extra: Pair<String, Any?>): JsonObject {
    val keep = listOf("id", "title", "date", "purpose", "origin", "from_id", "dest_name", "to_id", "lat", "lon", "depart_at", "return_by", "stay_min", "transport", "lodging", "shooting", "shooting_min", "notes")
    val o = trip as? JsonObject ?: JsonObject(emptyMap())
    val base = keep.mapNotNull { k -> o[k]?.takeIf { it !is JsonNull && !(it.text.isEmpty() && k !in setOf("title", "date", "purpose", "dest_name", "id")) }?.let { k to it } }.toMap()
    return JsonObject(base + extra.associate { it.first to toJson(it.second) })
}

/** 予定の一覧（Web: TripList.jsx）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TripList(trips: List<JsonElement>, onDelete: ((JsonElement) -> Unit)? = null, busy: Boolean = false) {
    val nav = LocalNav.current
    if (trips.isEmpty()) { Boxed { Muted("まだ予定がありません。「予定をつくる」から目的地と日時を登録してください。", 11) }; return }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        trips.forEach { t ->
            val ph = OutingTime.phase(t)
            Boxed {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Tag(OutingTime.PHASE_LABEL.getValue(ph), OutingTime.PHASE_TONE.getValue(ph)); Muted(t.str("date").orEmpty(), 10); Tag(PURPOSE_LABEL[t.str("purpose")] ?: t.str("purpose").orEmpty(), Brand.MutedFg)
                }
                Text(t.str("title").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Muted(listOfNotNull("📍 ${t.str("dest_name").orEmpty()}", "🕒 ${t.str("depart_at") ?: "—"} → ${t.str("return_by") ?: "—"}", if (!t.str("pack_id").isNullOrBlank()) "現地パックあり" else null, if (!t.str("packing_id").isNullOrBlank()) "持ち物あり" else null).joinToString("　"), 11)
                Row {
                    SmallButton("詳細 ›", { nav.go("/outing/plans/${t.str("id")}") })
                    onDelete?.let { d -> TextButton(onClick = { d(t) }, enabled = !busy) { Text("削除", fontSize = 11.sp, color = Brand.Destructive) } }
                }
            }
        }
    }
}

@Composable
internal fun PackingBar(items: List<JsonElement>) {
    val done = items.count { it.bool("checkedOut") == true }
    val pct = if (items.isNotEmpty()) done * 100 / items.size else 0
    Row { Muted("持ち出し確認", 10); Box(Modifier.weight(1f)); Muted("$done/${items.size}（$pct%）", 10) }
    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp))
}

private val GROUP_ORDER = listOf("credential", "electronics", "camera", "power", "rain", "clothing", "food", "other")

/** 持ち物チェックリスト（出発前の持ち出し確認・帰宅時の返却確認）。Web: PackingChecklist.jsx */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PackingChecklist(items: List<JsonObject>, onChange: (List<JsonObject>) -> Unit, onRemove: (String) -> Unit, phase: String, disabled: Boolean) {
    if (items.isEmpty()) { Boxed { Muted("持ち物がまだありません。下の提案から追加するか、持ち物タブで登録してください。", 11) }; return }
    val missingOut = items.filter { it.bool("checkedOut") != true }
    val missingBack = items.filter { it.bool("checkedOut") == true && it.bool("checkedBack") != true }
    val essentialMissing = missingOut.filter { it.str("priority") == "essential" }
    PackingBar(items)
    if (phase != "after" && missingOut.isNotEmpty()) Boxed(tone = Brand.Warning) { Text("未チェック ${missingOut.size}件${if (essentialMissing.isNotEmpty()) "（必須 ${essentialMissing.size}件）" else ""}", fontSize = 11.sp, color = Brand.Warning) }
    if (phase == "after" && missingBack.isNotEmpty()) Boxed(tone = Brand.Destructive) { Text("持ち帰り未確認 ${missingBack.size}件", fontSize = 11.sp, color = Brand.Destructive) }
    val groups = GROUP_ORDER.map { k -> k to items.filter { (it.str("category") ?: "other") == k } }.filter { it.second.isNotEmpty() } +
        listOf("extra" to items.filter { (it.str("category") ?: "other") !in GROUP_ORDER }).filter { it.second.isNotEmpty() }
    val key = if (phase == "after") "checkedBack" else "checkedOut"
    groups.forEach { (g, rows) ->
        Text(CATEGORY_LABEL[g] ?: "その他", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg, modifier = Modifier.padding(top = 4.dp))
        rows.forEach { item ->
            val checked = item.bool(key) == true
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(checked, { if (!disabled) onChange(items.map { r -> if (r.str("key") == item.str("key")) r.plus(key to !checked) else r }) }, enabled = !disabled)
                Column(Modifier.weight(1f)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(item.str("name").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, textDecoration = if (item.bool("checkedOut") == true) TextDecoration.LineThrough else null,
                            color = if (item.bool("checkedOut") == true) Brand.MutedFg else MaterialTheme.colorScheme.onSurface)
                        val pr = item.str("priority") ?: "normal"
                        Tag(PRIORITY_LABEL[pr] ?: "通常", PRIORITY_TONE[pr] ?: Brand.MutedFg)
                        if (item.bool("registered") != true) Tag("未登録の候補", Brand.Warning)
                        if (phase != "after" && item.bool("checkedOut") == true) Tag("持参", Brand.Success)
                    }
                    item.str("note")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
                }
                IconButton(onClick = { onRemove(item.str("key").orEmpty()) }, enabled = !disabled) { Icon(Icons.Outlined.Close, "リストから削除", Modifier.size(16.dp)) }
            }
        }
    }
}

internal fun packingItem(s: JsonElement): JsonObject = jsonOf(
    "key" to (s.str("key") ?: "item-${System.currentTimeMillis()}-${(Math.random() * 1e6).toLong().toString(36)}"), "name" to s.str("name"), "category" to (s.str("category") ?: "other"),
    "priority" to (s.str("priority") ?: "normal"), "qty" to 1, "checkedOut" to false, "checkedBack" to false, "gearId" to s.str("gearId"), "registered" to (s.bool("registered") == true), "note" to (s.str("reason") ?: ""))

internal fun parseItems(text: String?): List<JsonObject> = (parseJson(text) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

@Suppress("unused") private fun keepW() = Modifier.width(1.dp)
