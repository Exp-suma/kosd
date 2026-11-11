package app.narabuspulse.feature.outing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ElectricalServices
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import app.narabuspulse.core.objAt
import app.narabuspulse.core.offline.OfflineStore
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.field.FieldNoteList
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** ダッシュボード上部：現在の活動状態・目的地・電池・通信（Web: TodayStatus.jsx）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodayStatus(trip: JsonElement?, phase: String, level: Int?, endLevel: Int?, remaining: Int?, online: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 2) {
        val m = Modifier.weight(1f)
        Column(m) { Boxed {
            Muted("現在の活動状態", 10)
            if (trip != null) Tag(OutingTime.PHASE_LABEL.getValue(phase), OutingTime.PHASE_TONE.getValue(phase)) else Tag("予定なし", Brand.MutedFg)
            Muted(remaining?.let { "帰宅目標まで ${OutingTime.durationLabel(it)}" } ?: "帰宅目標 未設定", 10)
        } }
        Column(m) { Boxed {
            Muted("📍 現在の目的地", 10); Text(trip.str("dest_name") ?: "未設定", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Muted(trip.str("date")?.let { "$it ${trip.str("depart_at").orEmpty()}出発" } ?: "予定を登録してください", 10)
        } }
        Column(m) { Boxed {
            Muted("🔋 端末の電池残量", 10); Text(level?.let { "$it%" } ?: "未登録", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (level != null && level <= 20) Brand.Destructive else MaterialTheme.colorScheme.onSurface)
            Muted(endLevel?.let { "帰宅時 約$it%（推定）" } ?: "残量を登録すると推定します", 10)
        } }
        Column(m) { Boxed {
            Muted(if (online) "📶 通信状態" else "📵 通信状態", 10); Text(if (online) "オンライン" else "オフライン", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (online) Brand.Success else Brand.Warning)
            Muted(if (online) "保存済み情報も併用できます" else "保存済みの情報を表示しています", 10)
        } }
    }
}

@Composable
internal fun OfflineNotice(cachedAt: Long?) {
    Boxed(tone = Brand.Warning) { Text("☁ 通信できないため、端末に保存した内容を表示しています。${cachedAt?.takeIf { it > 0 }?.let { "（保存 ${stamp(it)}）" } ?: ""}", fontSize = 11.sp, color = Brand.Warning) }
}

/** 今日の予定の要約カード（Web: TodayTripCard.jsx）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodayTripCard(trip: JsonElement?, packing: JsonElement?, pack: JsonElement?, devices: List<JsonElement>) {
    val nav = LocalNav.current
    if (trip == null) {
        Panel {
            Text("今日の予定は登録されていません", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Muted("目的地と日時を登録すると、経路・現地情報・持ち物・電池の見通しをまとめて用意します。", 11)
            TextButton(onClick = { nav.go("/outing/plans") }) { Text("予定をつくる ›", fontSize = 12.sp) }
        }
        return
    }
    val phase = OutingTime.phase(trip); val remaining = OutingTime.remaining(trip)
    val weather = parseJson(pack.str("sections")).objAt("weather")
    val items = parseItems(packing.str("items"))
    val route = parseJson(trip.str("route"))
    val bank = devices.firstOrNull { it.str("kind") == "powerbank" }
    Panel(action = { SmallButton("詳細 ›", { nav.go("/outing/plans/${trip.str("id")}") }) }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Tag(OutingTime.PHASE_LABEL.getValue(phase), OutingTime.PHASE_TONE.getValue(phase)); Tag(PURPOSE_LABEL[trip.str("purpose")] ?: trip.str("purpose").orEmpty(), Brand.MutedFg) }
        Text(trip.str("title").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Muted("📍 ${trip.str("dest_name").orEmpty()}　🕒 ${trip.str("depart_at") ?: "—"} → ${trip.str("return_by") ?: "—"}${remaining?.let { "　残り ${OutingTime.durationLabel(it)}" } ?: ""}", 11)
        Boxed { Muted("🛣 経路", 10); Text(OutingTime.routeLabel(route), fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted(if (route != null) "保存した経路（取得日時は詳細で確認）" else "未取得（経路タブで検索）", 10) }
        Boxed {
            Muted("☀ 天気", 10)
            if (weather?.bool("available") == true) {
                Text("${weather.str("tmax") ?: "—"}℃ / ${weather.str("tmin") ?: "—"}℃ 降水${weather.str("pop") ?: "—"}%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Muted("${weather.str("source").orEmpty()}・${weather.long("fetchedAt")?.let { stamp(it) } ?: "取得日時不明"}", 10)
            } else Muted(weather.str("reason") ?: "現地情報パック未作成", 11)
        }
        Boxed {
            Muted("持ち物・モバイルバッテリー", 10)
            Text(if (items.isNotEmpty()) "${items.count { it.bool("checkedOut") == true }}/${items.size} 確認" else "リスト未作成", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Muted("バッテリー ${bank.int("level")?.let { "$it%" } ?: "未登録"}", 10)
        }
        if (items.isNotEmpty()) PackingBar(items)
    }
}

/** 予定に紐づく現地メモ（Web: TripMemos.jsx。端末に保存→復旧後に自動同期）。 */
@Composable
internal fun TripMemos(trip: JsonElement) {
    val scope = rememberCoroutineScope()
    val online by OfflineStore.online.collectAsState()
    val records by OfflineStore.records.collectAsState()
    val rows = records.filter { it.bool("deleted") != true && it.str("planId") == trip.str("id") }.sortedByDescending { it.long("createdAt") ?: 0 }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Panel(title = "✏ 現地メモ", subtitle = if (online) "端末に保存し、自動で同期します" else "オフライン：端末に保存します") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, placeholder = { Text("例：○○橋から望遠で狙う。午後は逆光。", fontSize = 12.sp) }, minLines = 2, modifier = Modifier.weight(1f))
            SmallButton(if (busy) "…" else "記録", {
                busy = true
                scope.launch {
                    try {
                        OfflineStore.createRecord(mapOf("kind" to "memo", "content" to text.trim(), "planId" to trip.str("id"), "planName" to trip.str("title"), "occurredAt" to System.currentTimeMillis(), "recordedOffline" to !online))
                        text = ""; if (online) OfflineStore.autoSync("after-record")
                    } finally { busy = false }
                }
            }, enabled = !busy && text.isNotBlank(), primary = true, modifier = Modifier.padding(start = 6.dp))
        }
        FieldNoteList(rows, onDelete = { r -> scope.launch { OfflineStore.deleteRecord(r.str("id").orEmpty()) } }, emptyText = "この予定のメモはまだありません。")
    }
}

/** 天候・目的・滞在時間から持ち物を提案する（Web: SuggestPanel.jsx）。 */
@Composable
internal fun SuggestPanel(context: JsonElement?, gear: List<JsonElement>, items: List<JsonObject>, onChange: (List<JsonObject>) -> Unit, disabled: Boolean) {
    val data = produceState<Pair<List<JsonElement>, String>?>(null, context, gear) {
        value = runCatching { Logic.call("outing/gear", "buildSuggestions", jsonOf("context" to context, "gear" to JsonArray(gear))).items() to Logic.call("outing/gear", "suggestNote", context).text }.getOrNull()
    }.value
    val existing = items.map { if (!it.str("gearId").isNullOrBlank()) "g:${it.str("gearId")}" else "n:${it.str("name")}" }.toSet()
    val available = data?.first.orEmpty().filter { r -> (if (!r.str("gearId").isNullOrBlank()) "g:${r.str("gearId")}" else "n:${r.str("name")}") !in existing }
    Panel(title = "✨ 持ち物の自動提案", subtitle = data?.second, action = { SmallButton("候補をすべて追加", { onChange(items + available.map(::packingItem)) }, enabled = !disabled && available.isNotEmpty()) }) {
        if (data == null) Muted("提案を計算しています…", 11)
        else if (available.isEmpty()) Boxed { Muted("提案できる持ち物はすべてリストにあります。", 11) }
        else available.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.str("name").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (r.bool("registered") != true) Tag("未登録（所持は未確認）", Brand.Warning)
                        r.int("forgotten")?.takeIf { it > 0 }?.let { Tag("過去に${it}回忘れています", Brand.Destructive) }
                    }
                    Muted(r.str("reason").orEmpty(), 10)
                }
                TextButton(onClick = { onChange(items + packingItem(r)) }, enabled = !disabled) { Text("＋ 追加", fontSize = 11.sp) }
            }
        }
        Muted("登録していない機材を所持しているとは判断しません。「未登録」は候補として表示しています。", 10)
    }
}

/** 電池残量の管理・帰宅までの消費推定・警告（Web: BatteryPanel.jsx）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BatteryPanel(devices: List<JsonElement>, trip: JsonElement?, prediction: JsonElement?, thresholds: OutingPrefs.Thresholds, busy: Boolean,
                          onLog: (String, Int, Boolean) -> Unit, onSensor: ((Int, Boolean) -> Unit)?, onSaveThresholds: ((OutingPrefs.Thresholds) -> Unit)?) {
    val context = LocalContext.current
    val levels = remember { mutableStateOf(mapOf<String, String>()) }
    val charging = remember { mutableStateOf(mapOf<String, Boolean>()) }
    var limits by remember(thresholds) { mutableStateOf(thresholds) }
    var tipsOpen by remember { mutableStateOf(false) }
    var assumOpen by remember { mutableStateOf(false) }
    val sensor = remember { deviceBattery(context) }
    Panel(title = "🔌 充電・バッテリー", action = { Tag("推定値（実測ではありません）", Brand.Warning) }) {
        if (onSensor != null) Boxed {
            if (sensor != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📱 端末から取得：${sensor.first}%${if (sensor.second) "（充電中）" else ""}", fontSize = 11.sp, modifier = Modifier.weight(1f))
                SmallButton("この値を登録", { val s = deviceBattery(context) ?: sensor; onSensor(s.first, s.second) }, enabled = !busy)
            } else Muted("この端末では電池残量を取得できません。下の欄に手入力してください。", 11)
        }
        if (devices.isEmpty()) Boxed { Muted("機器が登録されていません。バッテリータブでスマートフォン・カメラ・モバイルバッテリーを登録してください。", 11) }
        devices.forEach { d ->
            val id = d.str("id").orEmpty()
            val v = levels.value[id] ?: d.int("level")?.toString().orEmpty()
            val ch = charging.value[id] ?: (d.bool("charging") == true)
            Boxed {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.str("name").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Tag(DEVICE_KINDS.toMap()[d.str("kind")] ?: d.str("kind").orEmpty(), Brand.MutedFg)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(v, { s -> levels.value = levels.value + (id to s.filter(Char::isDigit).take(3)) }, label = { Text("残量 %", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Checkbox(ch, { charging.value = charging.value + (id to it) }); Text("充電中", fontSize = 11.sp)
                    SmallButton("記録", { v.toIntOrNull()?.let { onLog(id, it.coerceIn(0, 100), ch) } }, enabled = !busy && v.toIntOrNull() != null, modifier = Modifier.padding(start = 4.dp))
                }
                Muted("更新 ${clockOf(d.long("measured_at"))}・${if (d.str("source") == "sensor") "端末" else "手入力"}", 10)
            }
        }
        if (prediction != null) Boxed {
            Muted("帰宅時点の残量（推定）", 10)
            Text(prediction.int("endLevel")?.let { "約 $it%" } ?: "残量を登録してください", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Muted("予定 ${trip.str("return_by")?.let { "帰宅 $it" } ?: "帰宅時刻未設定"}・消費 約${prediction.str("drainPercent") ?: "—"}%", 10)
            prediction.items("breakdown").forEach { r -> Row { Muted("${r.str("label")}：${OutingTime.durationLabel(r.dbl("minutes"))}（${r.str("perHour")}%/時）", 10); Column(Modifier.weight(1f)) {}; Muted("-${"%.1f".format(r.dbl("percent") ?: 0.0)}%", 10) } }
            AlertStack(prediction.items("warnings").map { OutAlert(it.str("key").orEmpty(), it.str("level").orEmpty(), it.str("text").orEmpty()) }, title = "")
            TextButton(onClick = { tipsOpen = !tipsOpen }) { Text("${if (tipsOpen) "▾" else "▸"} 省電力のヒント", fontSize = 11.sp) }
            if (tipsOpen) prediction.list("tips").forEach { Muted("・${it.text}", 10) }
            TextButton(onClick = { assumOpen = !assumOpen }) { Text("${if (assumOpen) "▾" else "▸"} 計算の前提", fontSize = 11.sp) }
            if (assumOpen) prediction.list("assumptions").forEach { Muted("・${it.text}", 10) }
        }
        if (onSaveThresholds != null) Boxed {
            Text("⚡ 警告のしきい値（%）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                @Composable fun F(l: String, v: Int, set: (Int) -> Unit) = OutlinedTextField(v.toString(), { s -> s.filter(Char::isDigit).take(2).toIntOrNull()?.let(set) }, label = { Text(l, fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                F("注意", limits.high) { limits = limits.copy(high = it) }; F("低", limits.low) { limits = limits.copy(low = it) }; F("危険", limits.critical) { limits = limits.copy(critical = it) }
                SmallButton("保存", { onSaveThresholds(limits) })
            }
        }
        if (busy) Muted("更新中…", 10)
    }
}

/** 充電できる可能性がある場所（Web: ChargingSpots.jsx。OpenStreetMap のタグがある施設のみ）。 */
@Composable
internal fun ChargingSpots(trip: JsonElement?) {
    val scope = rememberCoroutineScope()
    var place by remember(trip.str("id")) { mutableStateOf(if (trip.dbl("lat") != null && trip.dbl("lon") != null) jsonOf("label" to (trip.str("dest_name") ?: "目的地"), "endpoint" to trip.str("to_id").orEmpty(), "lat" to trip.dbl("lat"), "lon" to trip.dbl("lon")) else null) }
    var result by remember { mutableStateOf<JsonElement?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var othersOpen by remember { mutableStateOf(false) }
    Panel(title = "🔌 充電できる場所を探す") {
        PlaceInput("調べる地点", place, { place = it }, "目的地・駅など", allowGeolocate = true)
        SmallButton(if (busy) "調べています…" else "周辺を調べる", {
            val la = place.dbl("lat"); val lo = place.dbl("lon")
            if (la == null || lo == null) { error = "地点を候補から選ぶか、緯度経度で指定してください"; return@SmallButton }
            busy = true; error = ""; result = null
            scope.launch {
                try { val r = Api.rpc("outing/server-fns#searchChargingSpots", jsonOf("lat" to la, "lon" to lo, "radius" to 900)); result = r; if (r.bool("ok") == false) error = r.str("error") ?: "周辺の情報を取得できませんでした" }
                catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "周辺の情報を取得できませんでした" } } finally { busy = false }
            }
        }, enabled = !busy, primary = true)
        if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
        result?.let { r ->
            Muted("取得 ${stamp(r.long("fetchedAt"))}・半径約${r.int("radius") ?: 900}m・${r.str("source").orEmpty()}", 10)
            val charging = r.items("charging"); val others = r.items("others")
            Boxed {
                Text("充電設備のタグがある施設（${charging.size}件）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg)
                if (charging.isEmpty()) Muted("地図データ上、充電設備の登録は見つかりませんでした（設備が無いとは断定できません）。", 11)
                charging.forEach { Row { Text(it.str("name") ?: "名称なし", fontSize = 12.sp, modifier = Modifier.weight(1f)); Muted("約${it.int("distance")}m", 11) } }
            }
            if (others.isNotEmpty()) Boxed {
                Text("${if (othersOpen) "▾" else "▸"} 電源を借りられそうな立ち寄り先（駅・カフェ・コンビニ・宿泊／${others.size}件）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg, modifier = Modifier.clickable { othersOpen = !othersOpen })
                if (othersOpen) others.take(12).forEach { Row { Text("${it.str("name") ?: "名称なし"}（${it.str("kindLabel").orEmpty()}）", fontSize = 12.sp, modifier = Modifier.weight(1f)); Muted("約${it.int("distance")}m", 11) } }
            }
            Muted(r.str("note").orEmpty(), 10)
        }
    }
}

/** 管理する機器の登録（Web: DeviceForm.jsx）。 */
@Composable
internal fun DeviceForm(initial: JsonElement?, onSave: (JsonObject) -> Unit, onCancel: () -> Unit, busy: Boolean) {
    var name by remember { mutableStateOf(initial.str("name").orEmpty()) }
    var kind by remember { mutableStateOf(initial.str("kind") ?: "phone") }
    var level by remember { mutableStateOf(initial.int("level")?.toString().orEmpty()) }
    var cap by remember { mutableStateOf(initial.int("capacity_mah")?.toString().orEmpty()) }
    var note by remember { mutableStateOf(initial.str("note").orEmpty()) }
    var active by remember { mutableStateOf(initial.bool("active") != false) }
    var error by remember { mutableStateOf("") }
    Boxed {
        OutlinedTextField(name, { name = it }, label = { Text("機器の名前", fontSize = 11.sp) }, placeholder = { Text("例 スマートフォン / カメラ / モバイルバッテリー", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SelectField(DEVICE_KINDS, kind, { kind = it }, label = "種類")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(level, { level = it.filter(Char::isDigit).take(3) }, label = { Text("現在の残量（％）", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(cap, { cap = it.filter(Char::isDigit).take(6) }, label = { Text("容量（mAh・任意）", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        OutlinedTextField(note, { note = it }, label = { Text("メモ", fontSize = 11.sp) }, placeholder = { Text("予備電池2本など", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(active, { active = it }); Text("この外出で持ち出す機器として扱う", fontSize = 12.sp) }
        if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("キャンセル", fontSize = 12.sp) }
            SmallButton(if (busy) "保存中…" else if (initial.str("id") != null) "更新" else "登録", {
                if (name.isBlank()) { error = "機器の名前を入力してください"; return@SmallButton }
                error = ""
                onSave(jsonOf("id" to initial.str("id"), "name" to name.trim(), "kind" to kind, "level" to level.toIntOrNull()?.coerceAtMost(100), "capacity_mah" to cap.toIntOrNull()?.takeIf { it > 0 }, "note" to note, "active" to active, "source" to "manual"))
            }, enabled = !busy, primary = true)
        }
    }
}

/** 持ち物の登録・編集（Web: GearForm.jsx。写真は非公開ストレージに保存）。 */
@Composable
internal fun GearForm(initial: JsonElement?, onSave: (JsonObject) -> Unit, onCancel: () -> Unit, busy: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.str("name").orEmpty()) }
    var category by remember { mutableStateOf(initial.str("category") ?: "camera") }
    var qty by remember { mutableStateOf((initial.int("qty") ?: 1).toString()) }
    var weight by remember { mutableStateOf(initial.int("weight_g")?.toString().orEmpty()) }
    var storage by remember { mutableStateOf(initial.str("storage").orEmpty()) }
    var essential by remember { mutableStateOf(initial.bool("essential") == true) }
    var note by remember { mutableStateOf(initial.str("note").orEmpty()) }
    var photoUri by remember { mutableStateOf(initial.str("photo_uri").orEmpty()) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val preview = produceState("", photoUri) { value = if (photoUri.isBlank()) "" else runCatching { Api.signedUrl(photoUri) }.getOrDefault("") }.value
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true; error = ""
        scope.launch {
            try {
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: throw IllegalStateException("写真を読み込めませんでした")
                photoUri = Api.uploadPrivateFile("gear.${mime.substringAfter('/')}", mime, bytes)
            } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "写真をアップロードできませんでした" } } finally { uploading = false }
        }
    }
    Boxed {
        OutlinedTextField(name, { name = it }, label = { Text("名前", fontSize = 11.sp) }, placeholder = { Text("例 望遠レンズ", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SelectField(GEAR_CATEGORIES, category, { category = it }, label = "カテゴリー")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(qty, { qty = it.filter(Char::isDigit).take(3) }, label = { Text("個数", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(weight, { weight = it.filter(Char::isDigit).take(5) }, label = { Text("重量（g）", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        OutlinedTextField(storage, { storage = it }, label = { Text("保管場所", fontSize = 11.sp) }, placeholder = { Text("カメラバッグ前室", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("メモ", fontSize = 11.sp) }, placeholder = { Text("型番・予備電池の本数など", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(essential, { essential = it }); Text("重要（常に確認したい）", fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (photoUri.isNotBlank()) {
                if (preview.isNotBlank()) AsyncImage(preview, "持ち物の写真", Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                Tag("写真あり", Brand.Success); TextButton(onClick = { photoUri = "" }) { Text("外す", fontSize = 11.sp) }
            } else SmallButton(if (uploading) "送信中…" else "写真を追加", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !uploading, icon = Icons.Outlined.CameraAlt)
        }
        if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("キャンセル", fontSize = 12.sp) }
            SmallButton(if (busy) "保存中…" else if (initial.str("id") != null) "更新" else "登録", {
                if (name.isBlank()) { error = "持ち物の名前を入力してください"; return@SmallButton }
                error = ""
                onSave(jsonOf("id" to initial.str("id"), "name" to name.trim(), "category" to category, "qty" to (qty.toIntOrNull() ?: 1), "weight_g" to weight.toIntOrNull()?.takeIf { it > 0 }, "storage" to storage,
                    "essential" to essential, "note" to note, "photo_uri" to photoUri.ifBlank { null }, "status" to initial.str("status")))
            }, enabled = !busy && !uploading, primary = true)
        }
    }
}

/** 登録した持ち物の一覧（Web: GearList.jsx）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GearList(gear: List<JsonElement>, onEdit: (JsonElement) -> Unit, onDelete: (JsonElement) -> Unit, onStatus: (JsonElement, String) -> Unit, busy: Boolean) {
    if (gear.isEmpty()) { Boxed { Muted("まだ持ち物が登録されていません。カメラ・予備電池・雨具など、よく持ち出すものを登録してください。", 11) }; return }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        gear.forEach { g ->
            Boxed {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(g.str("name").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (g.bool("essential") == true) Text("★", color = Brand.Warning, fontSize = 12.sp)
                    Tag(CATEGORY_LABEL[g.str("category")] ?: "その他", Brand.MutedFg)
                    g.int("forgotten_count")?.takeIf { it > 0 }?.let { Tag("忘れ ${it}回", Brand.Destructive) }
                }
                Muted(listOfNotNull(g.int("qty")?.takeIf { it > 0 }?.let { "${it}個" }, g.int("weight_g")?.takeIf { it > 0 }?.let { "${it}g" }, g.str("storage")?.takeIf { it.isNotBlank() },
                    if (!g.str("photo_uri").isNullOrBlank()) "写真あり" else null, g.str("note")?.takeIf { it.isNotBlank() }).joinToString(" ・ ").ifBlank { "詳細なし" }, 10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectField(GEAR_STATUS, g.str("status") ?: "available", { onStatus(g, it) })
                    TextButton(onClick = { onEdit(g) }, enabled = !busy) { Text("編集", fontSize = 11.sp) }
                    TextButton(onClick = { onDelete(g) }, enabled = !busy) { Text("削除", fontSize = 11.sp, color = Brand.Destructive) }
                }
            }
        }
    }
}

private val FACILITY_GROUPS = listOf("toilet" to "トイレ", "convenience" to "コンビニ", "cafe" to "カフェ", "restaurant" to "飲食店", "lodging" to "宿泊", "charging" to "充電設備（OSMタグ）", "shelter" to "雨宿り")

/** 現地情報パック（Web: FieldPackPanel.jsx。取得した事実と取得日時・情報源だけを表示）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FieldPackPanel(trip: JsonElement, pack: JsonElement?, onBuild: (Boolean) -> Unit, onSaveOffline: (JsonElement) -> Unit, busy: Boolean) {
    val parts by OfflineStore.parts.collectAsState()
    val local = parts.firstOrNull { it.str("key") == "pack:${trip.str("id")}" }
    val body = pack ?: local.list("rows").firstOrNull()
    var openGroup by remember { mutableStateOf("") }
    if (body == null) {
        Panel(title = "現地情報パック") {
            Muted("目的地周辺の駅・バス停・施設・天気・日の出/日の入りをまとめて取得します（OpenStreetMap と予報APIを使用）。", 11)
            SmallButton(if (busy) "取得中…" else "現地情報パックを作成", { onBuild(false) }, enabled = !busy, primary = true)
        }
        return
    }
    val sections = parseJson(body.str("sections"))
    val sources = parseJson(body.str("sources")).items()
    val weather = sections.objAt("weather"); val sun = sections.objAt("sun")
    val stops = sections.list("stops")
    val fetchedAt = body.long("fetched_at") ?: body.long("fetchedAt") ?: 0
    val stale = fetchedAt > 0 && System.currentTimeMillis() - fetchedAt > 12 * 3_600_000
    val offlineSavedAt = body.long("offline_saved_at") ?: local.long("savedAt") ?: 0
    Panel(title = "現地情報パック", subtitle = "取得 ${if (fetchedAt > 0) stamp(fetchedAt) else "取得日時不明"}${if (stale) "（古い情報）" else ""}", action = {
        Column(horizontalAlignment = Alignment.End) {
            SmallButton(if (busy) "更新中…" else "更新", { onBuild(true) }, enabled = !busy, icon = Icons.Outlined.Refresh)
            SmallButton("端末に保存", { onSaveOffline(body) }, enabled = !busy, icon = Icons.Outlined.CloudDownload, modifier = Modifier.padding(top = 4.dp))
        }
    }) {
        Boxed { Muted(if (offlineSavedAt > 0) "端末に保存済み（${stamp(offlineSavedAt)}）。この内容は通信なしで閲覧できます。地図タイルは「設定 > オフライン」で別途保存が必要です。" else "端末に保存すると通信なしで閲覧できます。地図タイルは「設定 > オフライン」で別途保存してください。", 10) }
        Boxed {
            Muted("☀ 天気", 10)
            if (weather?.bool("available") == true) {
                Text("${weather.str("tmax") ?: "—"}℃ / ${weather.str("tmin") ?: "—"}℃・降水 ${weather.str("pop") ?: "—"}%${weather.dbl("snow")?.let { "・降雪 ${it}cm" } ?: ""}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Muted("${weather.str("date").orEmpty()}・${weather.str("source").orEmpty()}・${weather.long("fetchedAt")?.let { stamp(it) } ?: "取得日時不明"}", 10)
                Muted("予報モデルの値です（実測ではありません）。", 10)
            } else Muted(weather.str("reason") ?: "天気を取得できていません", 11)
        }
        Boxed {
            Muted("日の出・日の入り（計算値）", 10)
            Text("日の出 ${clockOf(sun.long("sunrise"))} / 日の入り ${clockOf(sun.long("sunset"))}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Muted(sun.str("note") ?: "目的地の座標が未設定のため計算できません", 10)
        }
        Boxed {
            Muted("📍 周辺の駅・バス停（${stops.size}件）", 10)
            if (stops.isEmpty()) Muted("地図データから取得できませんでした（推測で補完していません）。", 11)
            stops.take(6).forEach { s -> Row { Text("${s.str("name") ?: s.str("kindLabel")}（${s.str("kindLabel").orEmpty()}）", fontSize = 12.sp, modifier = Modifier.weight(1f)); Muted("約${s.int("distance")}m", 11) } }
            val official = sections.list("officialStops")
            if (official.isNotEmpty()) { Muted("奈良交通の停留所データ（保存済み）", 10); official.take(5).forEach { s -> Row { Text(s.str("name").orEmpty(), fontSize = 12.sp, modifier = Modifier.weight(1f)); Muted("約${s.int("distance")}m", 11) } } }
        }
        Boxed {
            Muted("周辺施設（OpenStreetMapのタグ）", 10)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FACILITY_GROUPS.forEach { (k, l) -> val n = sections.list("facilities.$k").size; androidx.compose.material3.FilterChip(openGroup == k, { openGroup = if (openGroup == k) "" else k }, label = { Text("$l $n", fontSize = 10.sp) }) }
            }
            if (openGroup.isNotBlank()) {
                val rows = sections.list("facilities.$openGroup")
                if (rows.isEmpty()) Muted("該当する登録はありません。", 11)
                rows.forEach { s -> Row { Text(s.str("name") ?: "名称なし", fontSize = 12.sp, modifier = Modifier.weight(1f)); Muted("約${s.int("distance")}m", 11) } }
            }
            Muted("登録があるものだけを表示します。トイレ・充電設備などの有無は現地で確認してください。", 10)
        }
        sections.objAt("homeRoute")?.let { h -> Boxed { Muted("保存した帰宅経路", 10); Text(OutingTime.routeLabel(h), fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted("取得 ${stamp(h.long("fetchedAt"))}", 10) } }
        val warnings = sections.list("warnings")
        if (warnings.isNotEmpty()) Boxed(tone = Brand.Warning) { Text("⚠ 注意事項・未取得", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.Warning); warnings.forEach { Text("・${it.text}", fontSize = 11.sp, color = Brand.Warning) } }
        Boxed {
            Muted("情報源", 10)
            sources.forEach { s -> Row { Text(s.str("name").orEmpty(), fontSize = 11.sp, modifier = Modifier.weight(1f)); Text("${if (s.str("status") == "ok") "取得済み" else "未取得"}・${stamp(s.long("fetchedAt"))}", fontSize = 10.sp, color = if (s.str("status") == "ok") Brand.Success else Brand.Warning) } }
        }
    }
}

private val TRIGGERS = listOf("delay" to "バス・電車の遅延", "suspension" to "運休", "missed" to "乗り遅れ", "weather" to "天候の悪化", "shooting" to "撮影予定の変更", "destination" to "目的地の変更",
    "stay" to "滞在時間の変更", "health" to "体調・疲労", "battery" to "電池残量の低下", "closing" to "施設の営業時間変更", "lastbus" to "最終便が近い", "other" to "その他")

/** AI予定変更アシスタント（Web: ChangeAssistant.jsx）。提案は保存のみで、採用するまで予定は変えない。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChangeAssistant(trip: JsonElement, changes: List<JsonElement>, onPropose: (String, String) -> Unit, onDecide: (String, String, String?) -> Unit, busy: Boolean) {
    var trigger by remember { mutableStateOf(if (trip.str("purpose") == "shooting") "suspension" else "delay") }
    var detail by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf<String?>(null) }
    val choice = remember { mutableStateOf(mapOf<String, String>()) }
    val proposed = changes.filter { it.str("status") == "proposed" }
    val decided = changes.filter { it.str("status") != "proposed" }
    @Composable fun Change(c: JsonElement) {
        val id = c.str("id").orEmpty()
        val before = parseJson(c.str("before")); val after = parseJson(c.str("after"))
        val alts = after.list("alternatives")
        val chosen = choice.value[id] ?: after.str("routeOptionId") ?: alts.firstOrNull()?.str("id").orEmpty()
        Boxed {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                when (c.str("status")) { "adopted" -> Tag("採用済み", Brand.Success); "rejected" -> Tag("却下", Brand.MutedFg); else -> Tag("提案中", INFO) }
                Muted(stamp(c.long("at")), 10); Tag(TRIGGERS.toMap()[c.str("trigger")] ?: c.str("trigger").orEmpty(), Brand.MutedFg)
            }
            Text(c.str("summary").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            c.str("detail")?.takeIf { it.isNotBlank() }?.let { Muted("状況：$it", 10) }
            TextButton(onClick = { openId = if (openId == id) null else id }) { Text(if (openId == id) "閉じる" else "変更前後を確認", fontSize = 11.sp) }
            if (openId == id) {
                Boxed {
                    Text("変更前", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg)
                    Muted("日付：${before.str("date") ?: "—"}　時刻：${before.str("depart_at") ?: "—"} → ${before.str("return_by") ?: "—"}", 11)
                    Muted("滞在：${before.dbl("stay_min")?.let { OutingTime.durationLabel(it) } ?: "—"}　経路：${before.objAt("route")?.let { "${it.str("departure") ?: "—"}→${it.str("arrival") ?: "—"}" } ?: "未設定"}", 11)
                }
                Boxed(tone = INFO) {
                    Text("変更案（未反映）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = INFO)
                    val sch = after.list("schedule")
                    if (sch.isEmpty()) Muted("時刻案はありません", 11)
                    sch.forEach { r -> Text("${r.str("time").orEmpty()} ${r.str("title").orEmpty()}${r.str("note")?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}", fontSize = 11.sp) }
                }
                after.str("reason")?.takeIf { it.isNotBlank() }?.let { Muted("理由：$it", 11) }
                after.list("impacts").forEach { i -> Muted("・${i.str("area")}：${i.str("change")}${i.str("note")?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}", 11) }
                if (alts.isNotEmpty()) Boxed {
                    Text("代替経路（経路検索APIの実データ）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg)
                    alts.forEach { o ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (c.str("status") == "proposed") RadioButton(chosen == o.str("id"), { choice.value = choice.value + (id to o.str("id").orEmpty()) })
                            Text("${o.str("departure") ?: "—"}→${o.str("arrival") ?: "—"}・所要${o.dbl("durationMin")?.let { OutingTime.durationLabel(it) } ?: "不明"}・乗換${o.str("transfers") ?: "不明"}回・${o.long("fare")?.let { "${it}円" } ?: "運賃不明"}", fontSize = 11.sp)
                        }
                    }
                }
                after.str("routeError")?.takeIf { it.isNotBlank() }?.let { Text("代替経路を取得できませんでした（$it）。", fontSize = 11.sp, color = Brand.Warning) }
                after.str("batteryNote")?.takeIf { it.isNotBlank() }?.let { Muted("電池：$it", 11) }
                after.list("packingAdd").takeIf { it.isNotEmpty() }?.let { l -> Muted("持ち物の追加候補：${l.joinToString("・") { it.text }}", 11) }
                after.list("cautions").forEach { Text("・${it.text}", fontSize = 11.sp, color = Brand.Warning) }
                after.list("dataGaps").takeIf { it.isNotEmpty() }?.let { l -> Muted("取得できなかった情報：${l.joinToString(" / ") { it.text }}", 10) }
                if (c.str("status") == "proposed") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SmallButton("✕ 却下", { onDecide(id, "reject", null) }, enabled = !busy)
                    SmallButton("✓ 採用して予定に反映", { onDecide(id, "adopt", chosen.ifBlank { null }) }, enabled = !busy, primary = true, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
    Panel(title = "✨ AI予定変更アシスタント", subtitle = "状況を入力すると、取得できているデータ（予報・経路検索・電池残量）だけを根拠に変更案を作ります。採用するまで予定は変更しません。") {
        SelectField(TRIGGERS, trigger, { trigger = it }, label = "きっかけ")
        OutlinedTextField(detail, { detail = it }, label = { Text("状況（任意）", fontSize = 11.sp) }, placeholder = { Text("例：予定していたバスが運休。次の便まで40分。", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
        SmallButton(if (busy) "作成中…" else "変更案をつくる", { onPropose(trigger, detail) }, enabled = !busy, icon = Icons.Outlined.AutoAwesome, primary = true)
        if (proposed.isNotEmpty()) { Text("承認待ちの提案（${proposed.size}件）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = INFO); proposed.forEach { Change(it) } }
        if (decided.isNotEmpty()) { Text("決定済み", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg); decided.take(5).forEach { Change(it) } }
    }
}

private val MODE_LABEL = mapOf("rail" to "鉄道", "subway" to "地下鉄", "bus" to "バス", "tram" to "路面電車", "ferry" to "航路", "air" to "航空", "monorail" to "モノレール", "walk" to "徒歩")
private val BADGES = listOf("fastest" to "最短時間", "cheapest" to "最安", "fewestTransfers" to "乗換最少", "leastWalk" to "徒歩最少")

/** 1つの経路候補（Web: RouteOptionCard.jsx）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteOptionCard(option: JsonElement, badges: JsonElement?, explanation: JsonElement?, selected: Boolean, onSelect: ((JsonElement) -> Unit)?) {
    Boxed(tone = if (selected) Brand.Orange else null) {
        Text("${option.str("departure")?.ifBlank { null } ?: "—"} → ${option.str("arrival")?.ifBlank { null } ?: "—"}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Muted("所要 ${option.dbl("durationMin")?.let { OutingTime.durationLabel(it) } ?: "不明"}　乗換 ${option.str("transfers") ?: "不明"}回　徒歩 ${option.int("walkMin")?.let { "${it}分" } ?: "不明"}　運賃 ${option.long("fare")?.let { "${it}円" } ?: "不明"}", 11)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            BADGES.filter { (k, _) -> badges.str(k) != null && badges.str(k) == option.str("id") }.forEach { Tag(it.second, Brand.Orange) }
            onSelect?.let { s -> SmallButton(if (selected) "選択中" else "この経路を選ぶ", { s(option) }) }
        }
        option.list("legs").forEach { l ->
            Text("${if (l.str("kind") == "walk") "🚶" else if (l.str("mode") == "bus") "🚌" else "🚆"} ${l.str("depart")?.ifBlank { null } ?: "—"} ${l.str("from")?.ifBlank { null } ?: "—"} → ${l.str("arrive")?.ifBlank { null } ?: "—"} ${l.str("to")?.ifBlank { null } ?: "—"}${l.str("route")?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: if (l.str("kind") == "walk") "（徒歩）" else ""}", fontSize = 11.sp, color = Brand.MutedFg)
        }
        explanation?.let { e -> Boxed(tone = INFO) { Text("○ ${e.str("merit").orEmpty()}", fontSize = 11.sp, color = INFO); Text("△ ${e.str("demerit").orEmpty()}", fontSize = 11.sp, color = INFO); e.str("suitedFor")?.takeIf { it.isNotBlank() }?.let { Text("向く用途：$it", fontSize = 11.sp) } } }
        option.list("modes").takeIf { it.isNotEmpty() }?.let { m -> Muted("手段：${m.joinToString("・") { MODE_LABEL[it.text] ?: it.text }}", 10) }
    }
}

/** 経路検索・比較（Web: RouteSearchPanel.jsx。既存の経路検索APIをそのまま使用し、取得できない情報は補完しない）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RouteSearchPanel(initialFrom: JsonObject?, initialTo: JsonObject?, trips: List<JsonElement>, onSave: ((String, JsonElement) -> Unit)?, saving: Boolean) {
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf(initialFrom) }
    var to by remember { mutableStateOf(initialTo) }
    var date by remember { mutableStateOf(OutingTime.now().date) }
    var time by remember { mutableStateOf("08:00") }
    var type by remember { mutableStateOf("departure") }
    var avoidWalk by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JsonElement?>(null) }
    var options by remember { mutableStateOf(listOf<JsonElement>()) }
    var badges by remember { mutableStateOf<JsonElement?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var ai by remember { mutableStateOf<JsonElement?>(null) }
    var aiError by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<JsonElement?>(null) }
    var tripId by remember(trips) { mutableStateOf(trips.firstOrNull()?.str("id").orEmpty()) }
    LaunchedEffect(result) {
        val raw = JsonArray(result.items("options"))
        options = runCatching { raw.map { Logic.call("outing/schedule", "routeSummary", it) } }.getOrDefault(emptyList())
        badges = runCatching { Logic.call("outing/schedule", "compareRoutes", raw) }.getOrNull()
    }
    fun run() {
        if (from.str("endpoint").isNullOrBlank() || to.str("endpoint").isNullOrBlank()) { error = "出発地と目的地を候補から選んでください（緯度経度でも指定できます）"; return }
        loading = true; error = ""; ai = null; aiError = ""; selected = null
        scope.launch {
            try {
                val r = Api.rpc("transit/server-fns#searchTransitJourneys", jsonOf("from" to from.str("endpoint"), "to" to to.str("endpoint"), "fromLabel" to from.str("label"), "toLabel" to to.str("label"),
                    "date" to date, "time" to time, "type" to type, "avoidWalk" to (if (avoidWalk) true else null), "numItineraries" to 4))
                if (r.bool("ok") == false) { result = null; error = r.str("error") ?: "経路を取得できませんでした" } else result = r
            } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "経路を取得できませんでした" } } finally { loading = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Panel {
            PlaceInput("出発地", from, { from = it }, "自宅の最寄駅など", allowGeolocate = true)
            PlaceInput("目的地", to, { to = it }, "目的地の駅・施設名")
            SmallButton("入れ替え", { val f = to; to = from; from = f }, icon = Icons.Outlined.SwapHoriz)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                DateField(date, { date = it }); TimeField(time, { time = it }); SelectField(listOf("departure" to "出発時刻", "arrival" to "到着希望"), type, { type = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(avoidWalk, { avoidWalk = it }); Text("徒歩を避ける", fontSize = 12.sp, modifier = Modifier.weight(1f))
                SmallButton(if (loading) "検索中…" else "経路を検索", { run() }, enabled = !loading, primary = true)
            }
            if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
        }
        result?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Muted("取得 ${stamp(r.long("fetchedAt"))}・${if (r.bool("cached") == true) "キャッシュ" else "最新の取得"}${if (r.str("mode") == "plan") "（地図・順位付けは取得できませんでした）" else ""}", 10)
                Column(Modifier.weight(1f)) {}
                if (options.isNotEmpty()) SmallButton(if (aiBusy) "依頼中…" else "AIに比較の説明を依頼", {
                    aiBusy = true; ai = null; aiError = ""
                    scope.launch {
                        try {
                            val res = Api.rpc("outing/server-fns#explainRouteOptions", jsonOf("from" to (from.str("label") ?: r.str("from.name").orEmpty()), "to" to (to.str("label") ?: r.str("to.name").orEmpty()), "date" to date, "time" to time,
                                "options" to JsonArray(options.take(4).map { o -> jsonOf("id" to o.str("id"), "departure" to o.str("departure"), "arrival" to o.str("arrival"), "durationMin" to o.dbl("durationMin"), "walkMin" to o.dbl("walkMin"),
                                    "transfers" to o.dbl("transfers"), "fare" to o.dbl("fare"), "modes" to (o as JsonObject)["modes"], "legs" to o.list("legs").map { l -> "${l.str("from")}→${l.str("to")}${l.str("route")?.takeIf { it.isNotBlank() }?.let { "($it)" } ?: ""}" }) })))
                            if (res.bool("ok") == true) ai = res.objAt("result") else aiError = "比較の説明を取得できませんでした"
                        } catch (e: Throwable) { if (e is CancellationException) throw e; aiError = e.userMessage().ifBlank { "比較の説明を取得できませんでした" } } finally { aiBusy = false }
                    }
                }, enabled = !aiBusy, icon = Icons.Outlined.AutoAwesome)
            }
            ai?.let { a -> Boxed(tone = INFO) {
                Text(a.str("summary").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = INFO)
                a.str("bestId")?.takeIf { it.isNotBlank() }?.let { Text("おすすめ：候補 $it。${a.str("bestReason").orEmpty()}", fontSize = 11.sp) }
                a.str("caveats")?.takeIf { it.isNotBlank() }?.let { Text("注意：$it", fontSize = 11.sp, color = INFO) }
                Muted("${r.str("source") ?: "経路検索API"}の実データのみを根拠に生成しています。", 10)
            } }
            if (aiError.isNotBlank()) Text(aiError, fontSize = 11.sp, color = Brand.Destructive)
            r.list("coverage.notices").forEach { n -> Boxed(tone = Brand.Warning) { Text("⚠ ${n.str("message").orEmpty()}", fontSize = 11.sp, color = Brand.Warning) } }
            if (options.isEmpty()) Boxed { Muted("取得できる経路がありませんでした。時刻表データが未収録の区間は検索できません（推測の経路は表示しません）。", 11) }
            options.forEach { o -> RouteOptionCard(o, badges, ai.list("options").firstOrNull { it.str("id") == o.str("id") }, selected?.str("id") == o.str("id"), { selected = it }) }
            val sel = selected
            if (sel != null && onSave != null) Boxed(tone = Brand.Orange) {
                SelectField(listOf("" to "予定を選択") + trips.map { it.str("id").orEmpty() to "${it.str("date")} ${it.str("title")}" }, tripId, { tripId = it }, label = "この経路を保存する予定")
                SmallButton(if (saving) "保存中…" else "予定に保存", { scope.launch { val raw = result.items("options").getOrNull(options.indexOf(sel)) ?: return@launch
                    runCatching { Logic.call("outing/schedule", "routeForStore", raw) }.getOrNull()?.let { onSave(tripId, it) } } }, enabled = !saving && tripId.isNotBlank(), primary = true)
                Muted("予定の既存の内容は上書きしません（経路のみ更新します）。", 10)
            }
        }
    }
}

@Suppress("unused") private val keepIcons = listOf(Icons.Outlined.BatteryChargingFull, Icons.Outlined.ElectricalServices, Icons.Outlined.EditNote)
@Suppress("unused") private fun keepDbl(e: JsonElement?) = e.dbl("x")
